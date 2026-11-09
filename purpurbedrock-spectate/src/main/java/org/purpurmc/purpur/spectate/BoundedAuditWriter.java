package org.purpurmc.purpur.spectate;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded background writer. No Bukkit objects or callbacks may be touched on its worker.
 * This is not integrated with the admission coordinator or a plugin yet.
 */
public final class BoundedAuditWriter implements AutoCloseable {
    public record Entry(SpectateAudit.Event event, UUID session, UUID observer, String observerName, UUID target, String targetName) {
        public Entry {
            Objects.requireNonNull(event); Objects.requireNonNull(session); Objects.requireNonNull(observer);
            Objects.requireNonNull(observerName); Objects.requireNonNull(target); Objects.requireNonNull(targetName);
        }
    }
    @FunctionalInterface public interface Writer { void write(Entry entry) throws IOException; }
    private final Writer writer;
    private final ThreadPoolExecutor worker;
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    public BoundedAuditWriter(SpectateAudit audit, int capacity) {
        this(entry -> audit.record(entry.event(), entry.session(), entry.observer(), entry.observerName(), entry.target(), entry.targetName()), capacity);
        Objects.requireNonNull(audit);
    }
    public BoundedAuditWriter(Writer writer, int capacity) {
        this.writer = Objects.requireNonNull(writer);
        if (capacity < 1 || capacity > 1024) throw new IllegalArgumentException("Invalid audit queue capacity");
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            Thread thread = new Thread(task, "spectate-audit");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Rejected, duplicate and failed records complete exceptionally; never silently drop an event. */
    public CompletionStage<Void> submit(Entry entry) {
        Objects.requireNonNull(entry);
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (pending.putIfAbsent(entry.observer(), future) != null)
            return CompletableFuture.failedStage(new RejectedExecutionException("Observer already has a pending audit"));
        try {
            worker.execute(() -> {
                Throwable failure = null;
                try { writer.write(entry); }
                catch (IOException | RuntimeException ex) { failure = ex; }
                catch (Error ex) { future.completeExceptionally(ex); throw ex; }
                finally { pending.remove(entry.observer(), future); }
                if (failure == null) future.complete(null); else future.completeExceptionally(failure);
            });
        } catch (RejectedExecutionException ex) {
            pending.remove(entry.observer(), future);
            future.completeExceptionally(ex);
        }
        // Never expose the mutable completion: callers must not claim an audit finished early.
        return future.minimalCompletionStage();
    }

    /** Reject future submissions and drain queued work; does not block the caller. */
    @Override public void close() { worker.shutdown(); }

    /** Wait for a prior close to drain; never call this blocking method from the game thread. */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return worker.awaitTermination(timeout, Objects.requireNonNull(unit));
    }
}

package org.purpurmc.purpur.spectate;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Synthetic bounded-I/O tests; no Bukkit/server integration is enabled. */
public final class AsyncAuditTest {
    public static void main(String[] args) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var firstEntry = entry(UUID.randomUUID());
        var secondEntry = entry(UUID.randomUUID());
        var thirdEntry = entry(UUID.randomUUID());
        try (var audit = new BoundedAuditWriter((BoundedAuditWriter.Writer) entry -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
        }, 1)) {
            CompletionStage<Void> first = audit.submit(firstEntry);
            if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker did not start");
            CompletionStage<Void> queued = audit.submit(secondEntry);
            // Completing a caller-owned copy must not signal the real durable operation.
            first.toCompletableFuture().complete(null);
            if (first.toCompletableFuture().isDone()) throw new AssertionError("caller forged audit completion");
            first.toCompletableFuture().cancel(false);
            if (first.toCompletableFuture().isDone()) throw new AssertionError("caller canceled actual audit");
            expectFailure(audit.submit(firstEntry), "duplicate must fail");
            expectFailure(audit.submit(thirdEntry), "full queue must fail");
            release.countDown();
            first.toCompletableFuture().get(5, TimeUnit.SECONDS); queued.toCompletableFuture().get(5, TimeUnit.SECONDS);
            audit.close();
            if (!audit.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("writer did not drain");
            expectFailure(audit.submit(thirdEntry), "closed writer must fail");
        } finally { release.countDown(); }
        try (var audit = new BoundedAuditWriter((BoundedAuditWriter.Writer) entry -> { throw new IOException("disk failure"); }, 1)) {
            expectFailure(audit.submit(entry(UUID.randomUUID())), "disk failure must reach caller");
        }
        System.out.println("Bounded asynchronous audit tests passed (no server integration)");
    }
    private static BoundedAuditWriter.Entry entry(UUID observer) {
        return new BoundedAuditWriter.Entry(SpectateAudit.Event.START, UUID.randomUUID(), observer, "Moderator", UUID.randomUUID(), "Player");
    }
    private static void expectFailure(CompletionStage<Void> result, String message) throws Exception {
        try { result.toCompletableFuture().get(5, TimeUnit.SECONDS); throw new AssertionError(message); }
        catch (ExecutionException expected) { }
    }
}

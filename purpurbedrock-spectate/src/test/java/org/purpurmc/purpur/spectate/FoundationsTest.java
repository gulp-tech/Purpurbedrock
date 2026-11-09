package org.purpurmc.purpur.spectate;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class FoundationsTest {
    public static void main(String[] args) throws Exception {
        CoordinateOffset offset = new CoordinateOffset(-123, 89);
        for (int x : new int[] {-30_000_000, -33, -17, -16, -15, -1, 0, 1, 15, 16, 17, 30_000_000}) {
            check(offset.realBlockX(offset.blockX(x)) == x, "block X round trip");
            check(offset.realBlockZ(offset.blockZ(x)) == x, "block Z round trip");
            check(Math.floorDiv(offset.blockX(x), 16) == offset.chunkX(Math.floorDiv(x, 16)), "negative X chunk alignment");
            check(Math.floorDiv(offset.blockZ(x), 16) == offset.chunkZ(Math.floorDiv(x, 16)), "negative Z chunk alignment");
            check(offset.realChunkX(offset.chunkX(x)) == x, "chunk X round trip");
            check(offset.realChunkZ(offset.chunkZ(x)) == x, "chunk Z round trip");
        }
        try { offset.blockZ(Integer.MAX_VALUE); throw new AssertionError("overflow accepted"); }
        catch (ArithmeticException expected) { }
        try { new CoordinateOffset(Integer.MAX_VALUE, 0); throw new AssertionError("invalid offset accepted"); }
        catch (ArithmeticException expected) { }

        var directory = Files.createTempDirectory("spectate-audit-test");
        var file = directory.resolve("audit.jsonl");
        var audit = new SpectateAudit(file);
        UUID observer = UUID.randomUUID(), target = UUID.randomUUID();
        audit.record(SpectateAudit.Event.START, observer, "Mod\"\\\n", target, "Target");
        String first = Files.readString(file);
        check(first.contains("\\\"\\\\\\n"), "JSON escaping");
        check(first.contains(observer.toString()) && first.contains(target.toString()), "identities recorded");
        check(!first.contains("coordinates") && !first.contains("position"), "no coordinates in audit");
        int workers = 8, entries = 10;
        CountDownLatch ready = new CountDownLatch(workers), go = new CountDownLatch(1), done = new CountDownLatch(workers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < workers; i++) new Thread(() -> {
            ready.countDown();
            try { go.await(); for (int j = 0; j < entries; j++) audit.record(SpectateAudit.Event.STOP, observer, "Mod", target, "Target"); }
            catch (Throwable ex) { failure.compareAndSet(null, ex); }
            finally { done.countDown(); }
        }).start();
        ready.await(); go.countDown(); done.await();
        if (failure.get() != null) throw new AssertionError(failure.get());
        var lines = Files.readAllLines(file);
        check(lines.size() == 1 + workers * entries, "concurrent records lost or interleaved");
        check(lines.stream().allMatch(line -> line.startsWith("{") && line.endsWith("}")), "invalid record framing");
        try {
            new SpectateAudit(file.resolve("invalid.jsonl")).record(SpectateAudit.Event.START, observer, "Mod", target, "Target");
            throw new AssertionError("audit write failure not surfaced");
        } catch (java.io.IOException expected) { }
        System.out.println("Spectate foundations: arithmetic, overflow and audit tests passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

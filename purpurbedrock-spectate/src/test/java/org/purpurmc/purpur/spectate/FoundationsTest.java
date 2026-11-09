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

        UUID world = UUID.randomUUID();
        var base = new SpectatePolicy.Position(world, 0, 64, 0);
        check(SpectatePolicy.follow(base, new SpectatePolicy.Position(world, 6, 72, 0)) == SpectatePolicy.Follow.STAY, "distance exactly 10");
        check(SpectatePolicy.follow(base, new SpectatePolicy.Position(world, 6, 72.001, 0)) == SpectatePolicy.Follow.FOLLOW, "distance over 10");
        check(SpectatePolicy.follow(base, new SpectatePolicy.Position(UUID.randomUUID(), 0, 64, 0)) == SpectatePolicy.Follow.FOLLOW, "dimension change");
        check(SpectatePolicy.follow(base, null) == SpectatePolicy.Follow.STOP, "offline target");
        check(SpectatePolicy.follow(base, new SpectatePolicy.Position(world, Double.NaN, 64, 0)) == SpectatePolicy.Follow.STOP, "invalid position");
        check(SpectatePolicy.isExitCommand("/pbspec off") && SpectatePolicy.isExitCommand(" /PBSpec   off "), "exit must be usable");
        for (String command : new String[]{"/tp 1 2 3", "/pbspec", "/pbspec off extra", "/other:pbspec off", "/pbspec off;op", "/pbspec off\n/tp 1 2 3", "/pbspec \noff"})
            check(!SpectatePolicy.isExitCommand(command), "unexpected command allowed: " + command);
        var state = new SpectateState();
        try { state.activate(); throw new AssertionError("activated without protection"); }
        catch (IllegalStateException expected) { }
        state.prepare();
        try { state.activate(); throw new AssertionError("activated without audit"); }
        catch (IllegalStateException expected) { }
        state.armedAfterDurableSave(); state.auditCommitted(); state.activate();
        check(state.mayMoveToTarget(), "active session cannot follow");
        state.failedOrDisconnected();
        check(!state.mayMoveToTarget() && state.phase() == SpectateState.Phase.RECOVERY_REQUIRED, "disconnect must stop observation");
        try { state.restoredAndCleared(); throw new AssertionError("recovery skipped restoration"); }
        catch (IllegalStateException expected) { }
        state.beginRecovery(); state.restoredAndCleared();
        check(state.phase() == SpectateState.Phase.CLOSED, "recovery not closed");

        var directory = Files.createTempDirectory("spectate-audit-test");
        var returns = new ReturnStore(directory.resolve("returns"));
        UUID recovering = UUID.randomUUID();
        var original = new ReturnStore.Snapshot(recovering, world, -0.125, 70.5, -32.0, 175.0f, -42.0f, "SURVIVAL", false, false);
        check(returns.load(recovering).isEmpty(), "unexpected saved return state");
        returns.save(original);
        check(returns.load(recovering).orElseThrow().equals(original), "return state did not survive reload");
        try {
            returns.save(new ReturnStore.Snapshot(recovering, world, 1, 2, 3, 0, 0, "CREATIVE", true, false));
            throw new AssertionError("pending return state was overwritten");
        } catch (java.nio.file.FileAlreadyExistsException expected) { }
        check(returns.load(recovering).orElseThrow().equals(original), "duplicate start changed return state");
        try {
            new ReturnStore.Snapshot(recovering, world, Double.NaN, 0, 0, 0, 0, "SURVIVAL", false, false);
            throw new AssertionError("invalid return position accepted");
        } catch (IllegalArgumentException expected) { }
        returns.clear(recovering);
        check(returns.load(recovering).isEmpty(), "restored return state was not cleared");
        Files.writeString(directory.resolve("returns").resolve(recovering + ".properties"), "observer=broken\n");
        try { returns.load(recovering); throw new AssertionError("corrupt recovery data accepted"); }
        catch (java.io.IOException expected) { }
        returns.clear(recovering);
        Files.writeString(directory.resolve("returns").resolve(recovering + ".properties"), "x=\\u12XX\n");
        try { returns.load(recovering); throw new AssertionError("malformed properties accepted"); }
        catch (java.io.IOException expected) { }
        returns.clear(recovering);
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

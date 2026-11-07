package com.pexserver.completions;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.pexserver.completions.CompletionModel.*;

class CompletionScanTest {
    private static final Limits LIMITS = new Limits(3, 32, 64, 64, 96);
    private static Command finish(CompletionScan scan) {
        for (int i = 0; !scan.done() && i < 1000; i++) scan.step();
        assertTrue(scan.done(), "Bounded scan must finish"); return scan.result();
    }
    @Test void capturesContextDependentPrivateNamesWithoutMixingPlayers() {
        Provider alice = (prefix, partial) -> prefix.isEmpty() ? List.of("delete", "set") :
                prefix.equals(List.of("delete")) ? List.of("alice_base") : List.of();
        Provider bob = (prefix, partial) -> prefix.isEmpty() ? List.of("delete", "set") :
                prefix.equals(List.of("delete")) ? List.of("bob_base") : List.of();
        Command a = finish(new CompletionScan("home", LIMITS, alice, List.of(), List.of()));
        Command b = finish(new CompletionScan("home", LIMITS, bob, List.of(), List.of()));
        assertEquals(List.of("alice_base"), a.contexts().get(List.of("delete")));
        assertEquals(List.of("bob_base"), b.contexts().get(List.of("delete")));
        assertFalse(a.toString().contains("bob_base"));
    }
    @Test void repeatedListsAndHugeBranchingCannotCauseUnboundedQueries() {
        AtomicInteger calls = new AtomicInteger();
        List<String> many = java.util.stream.IntStream.range(0, 10000).mapToObj(i -> "name" + i).toList();
        CompletionScan scan = new CompletionScan("bad", LIMITS, (p, s) -> {
            calls.incrementAndGet(); return many;
        }, List.of(), List.of());
        Command result = finish(scan);
        assertEquals(1, result.nodes().size());
        assertEquals(64, result.nodes().getFirst().candidates().size());
        assertTrue(calls.get() <= 32);
    }
    @Test void eachStepMakesAtMostOneCallAndPrefixProbesAreOptIn() {
        AtomicInteger calls = new AtomicInteger();
        Provider provider = (p, s) -> { calls.incrementAndGet(); return s.equals("b") ? List.of("base") : List.of(); };
        assertTrue(finish(new CompletionScan("warp", LIMITS, provider, List.of(), List.of())).nodes().isEmpty());
        CompletionScan scan = new CompletionScan("warp", new Limits(1, 8, 10, 8, 96), provider, List.of("a", "b"), List.of());
        while (!scan.done()) { int before = calls.get(); scan.step(); assertEquals(before + 1, calls.get()); }
        assertEquals(List.of("base"), scan.result().contexts().get(List.of()));
    }
    @Test void extraContextsFindBranchesThatEmptyCompletionCannotDiscover() {
        CompletionScan scan = new CompletionScan("cmd", LIMITS,
                (p, s) -> p.equals(List.of("hidden", "delete")) ? List.of("record") : List.of(),
                List.of(), List.of(List.of("hidden", "delete")));
        assertEquals(List.of("record"), finish(scan).contexts().get(List.of("hidden", "delete")));
    }
    @Test void asynchronousHandlingFallsBackOnlyWhenUnhandledAndDoesNotBlock() {
        var pending = new java.util.concurrent.CompletableFuture<List<String>>();
        AtomicInteger resolveCalls = new AtomicInteger();
        CompletionScan scan = new CompletionScan("warp", new Limits(1, 8, 10, 8, 96),
                (prefix, partial) -> pending,
                (prefix, partial, values) -> { resolveCalls.incrementAndGet(); return values == null ? List.of("fallback") : values; },
                List.of(), List.of());
        scan.step(); scan.step(); assertFalse(scan.done()); assertEquals(0, resolveCalls.get());
        pending.complete(List.of("async"));
        assertEquals(List.of("async"), finish(scan).contexts().get(List.of()));
        assertEquals(1, resolveCalls.get());
        var unhandled = new CompletionScan("warp", new Limits(1, 8, 10, 8, 96),
                (prefix, partial) -> java.util.concurrent.CompletableFuture.completedFuture(null),
                (prefix, partial, values) -> values == null ? List.of("fallback") : values, List.of(), List.of());
        assertEquals(List.of("fallback"), finish(unhandled).contexts().get(List.of()));
        AtomicInteger forbiddenFallback = new AtomicInteger();
        var blocked = new CompletionScan("warp", new Limits(1, 8, 10, 8, 96),
                (prefix, partial) -> java.util.concurrent.CompletableFuture.completedFuture(List.of()),
                (prefix, partial, values) -> { forbiddenFallback.incrementAndGet(); return List.of("secret"); }, List.of(), List.of());
        assertTrue(finish(blocked).nodes().isEmpty());
        assertEquals(0, forbiddenFallback.get());
    }
    @Test void fullAsyncQueueYieldsAndRetriesWithoutSkippingPrefix() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> observed = new ArrayList<>();
        var scan = new CompletionScan("warp", new Limits(1, 8, 10, 8, 96),
                (prefix, partial) -> {
                    observed.add(partial);
                    if (attempts.getAndIncrement() == 1) return null; // saturated on probe a
                    return java.util.concurrent.CompletableFuture.completedFuture(partial.equals("a") ? List.of("alpha") : List.of());
                }, (prefix, partial, values) -> values,
                List.of("a", "b"), List.of());
        var result = finish(scan);
        assertEquals(List.of("", "a", "a", "b"), observed);
        assertEquals(List.of("alpha"), result.contexts().get(List.of()));
    }
    @Test void skipsInvalidCandidatesAndDoesNotExecuteAnything() {
        Command result = finish(new CompletionScan("cmd", new Limits(1, 8, 10, 8, 10),
                (p, s) -> Arrays.asList(null, "", "two words", "bad\n", "\"quote", "a\\b", "toolong_name", "ok", "ok"),
                List.of(), List.of()));
        assertEquals(List.of("ok"), result.nodes().getFirst().candidates());
    }
}

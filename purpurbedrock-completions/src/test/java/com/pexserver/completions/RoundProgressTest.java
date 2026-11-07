package com.pexserver.completions;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.pexserver.completions.CompletionModel.*;
import static org.junit.jupiter.api.Assertions.*;

class RoundProgressTest {
    @Test void partialScansKeepOldUnvisitedBranchesAndFinalResultsRemoveStaleEntries() {
        var old = new Command("home", List.of(new Node(List.of(), List.of("delete", "set")),
                new Node(List.of("delete"), List.of("old"))));
        var progress = new RoundProgress(Map.of("home", old), Set.of("home"));
        assertTrue(progress.update(new Command("home", List.of(new Node(List.of(), List.of("delete")))), false));
        assertEquals(List.of("old"), progress.bounded(32, 32, 100).get("home").contexts().get(List.of("delete")));
        assertTrue(progress.update(new Command("home", List.of(new Node(List.of(), List.of("delete")))), true));
        assertFalse(progress.bounded(32, 32, 100).get("home").contexts().containsKey(List.of("delete")));
        assertTrue(progress.update(new Command("home", List.of()), true));
        assertTrue(progress.bounded(32, 32, 100).isEmpty());
    }
    @Test void revokedCommandsAreRemovedFromRetainedModels() {
        var model = new Command("private", List.of(new Node(List.of(), List.of("secret"))));
        var progress = new RoundProgress(Map.of("private", model), Set.of());
        assertTrue(progress.bounded(32, 32, 100).isEmpty());
    }
    @Test void largeDomainsShareTheCandidateBudgetAcrossCommands() {
        var values = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "v" + i).toList();
        var first = new Command("a", List.of(new Node(List.of(), values)));
        var second = new Command("b", List.of(new Node(List.of(), values)));
        var result = CompletionBudget.bound(Map.of("a", first, "b", second), 32, 2, 8);
        assertEquals(4, result.get("a").nodes().getFirst().candidates().size());
        assertEquals(4, result.get("b").nodes().getFirst().candidates().size());
    }
    @Test void budgetGivesEachCommandARootBeforeDeepBranches() {
        var deep = new Command("a", List.of(new Node(List.of(), List.of("root")),
                new Node(List.of("root"), List.of("one")), new Node(List.of("root", "one"), List.of("two"))));
        var other = new Command("b", List.of(new Node(List.of(), List.of("other")),
                new Node(List.of("other"), List.of("three"))));
        var result = CompletionBudget.bound(Map.of("a", deep, "b", other), 32, 2, 100);
        assertEquals(1, result.get("a").nodes().size());
        assertEquals(1, result.get("b").nodes().size());
    }
}

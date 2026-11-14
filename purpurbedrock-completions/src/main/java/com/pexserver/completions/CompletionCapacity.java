package com.pexserver.completions;

/** Capacity migration for the unchanged, originally shipped conservative limits. */
final class CompletionCapacity {
    record Values(int commands, int nodes, int candidates) {}
    private CompletionCapacity() {}

    static Values effective(int commands, int nodes, int candidates, boolean upgradeLegacyDefaults) {
        // Do not replace customized capacities. The switch can also retain exact old defaults.
        if (upgradeLegacyDefaults && commands == 128 && nodes == 128 && candidates == 1024)
            return new Values(512, 512, 4096);
        return new Values(commands, nodes, candidates);
    }
}

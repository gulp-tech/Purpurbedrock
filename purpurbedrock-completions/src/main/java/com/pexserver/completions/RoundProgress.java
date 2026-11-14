package com.pexserver.completions;

import java.util.*;
import static com.pexserver.completions.CompletionModel.*;

/** Incremental, per-player scan results; unfinished contexts retain their last known values. */
final class RoundProgress {
    private final Map<String, Command> commands = new LinkedHashMap<>();
    RoundProgress(Map<String, Command> previous, Set<String> permitted) {
        previous.forEach((label, model) -> { if (permitted.contains(label)) commands.put(label, model); });
    }
    boolean update(Command observed, boolean finished) {
        Command previous = commands.get(observed.label());
        Command next = observed;
        if (!finished && previous != null && previous.typedPlugin() == observed.typedPlugin()) {
            Map<List<String>, Node> nodes = new LinkedHashMap<>();
            previous.nodes().forEach(node -> nodes.put(node.prefix(), node));
            observed.nodes().forEach(node -> nodes.put(node.prefix(), node));
            Map<ArgumentKey, Argument> args = new LinkedHashMap<>();
            previous.arguments().forEach(arg -> args.put(arg.key(), arg));
            observed.arguments().forEach(arg -> args.put(arg.key(), arg));
            next = new Command(observed.label(), List.copyOf(nodes.values()), List.copyOf(args.values()), observed.typedPlugin());
        }
        if (!next.hasCandidates()) next = null;
        if (Objects.equals(previous, next)) return false;
        if (next == null) commands.remove(observed.label()); else commands.put(observed.label(), next);
        return true;
    }
    Map<String, Command> bounded(int perCommand, int nodes, int values) {
        return CompletionBudget.bound(commands, perCommand, nodes, values);
    }
}

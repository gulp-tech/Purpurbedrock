package com.pexserver.completions;

import java.util.*;
import static com.pexserver.completions.CompletionModel.*;

/** Allocate one context per command per pass so large trees cannot starve other roots. */
final class CompletionBudget {
    private CompletionBudget() {}
    static Map<String, Command> bound(Map<String, Command> input, int perCommand, int maxNodes, int maxValues) {
        List<Command> commands = new ArrayList<>(input.values());
        commands.sort(Comparator.comparing((Command c) -> c.nodes().isEmpty())
                .thenComparingInt(c -> cost(c, perCommand)).thenComparing(Command::label));
        Map<String, List<Node>> nodesByCommand = new LinkedHashMap<>();
        Map<String, List<Argument>> argsByCommand = new LinkedHashMap<>();
        int nodeCount = 0, valueCount = 0;
        for (int index = 0; index < perCommand && nodeCount < maxNodes && valueCount < maxValues; index++) {
            boolean found = false;
            int pass = index;
            int remainingSlots = (int) commands.stream().filter(c -> c.nodes().size() + c.arguments().size() > pass).count();
            remainingSlots = Math.min(remainingSlots, maxNodes - nodeCount);
            for (Command command : commands) {
                if (nodeCount >= maxNodes || valueCount >= maxValues) break;
                int total = command.nodes().size() + command.arguments().size();
                if (index >= total) continue;
                found = true;
                int share = Math.max(1, (maxValues - valueCount + remainingSlots - 1) / remainingSlots);
                remainingSlots--;
                if (index < command.nodes().size()) {
                    Node node = command.nodes().get(index);
                    var values = node.candidates().subList(0, Math.min(node.candidates().size(), share));
                    nodesByCommand.computeIfAbsent(command.label(), ignored -> new ArrayList<>()).add(new Node(node.prefix(), values));
                    valueCount += values.size();
                } else {
                    Argument arg = command.arguments().get(index - command.nodes().size());
                    var values = arg.candidates().subList(0, Math.min(arg.candidates().size(), share));
                    argsByCommand.computeIfAbsent(command.label(), ignored -> new ArrayList<>()).add(new Argument(arg.key(), values));
                    valueCount += values.size();
                }
                nodeCount++;
            }
            if (!found) break;
        }
        Map<String, Command> result = new LinkedHashMap<>();
        for (Command command : commands) {
            Command bounded = new Command(command.label(), nodesByCommand.getOrDefault(command.label(), List.of()),
                    argsByCommand.getOrDefault(command.label(), List.of()), command.typedPlugin());
            if (bounded.hasCandidates()) result.put(command.label(), bounded);
        }
        return Map.copyOf(result);
    }
    private static int cost(Command command, int perCommand) {
        return java.util.stream.Stream.concat(command.nodes().stream().map(Node::candidates),
                command.arguments().stream().map(Argument::candidates)).limit(perCommand).mapToInt(List::size).sum();
    }
}

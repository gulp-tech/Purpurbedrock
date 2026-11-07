package com.pexserver.completions;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.pexserver.completions.CompletionModel.*;

/** One step makes at most one provider call. No unbounded recursive probing. */
public final class CompletionScan implements CompletionTask {
    private final String label;
    private final Limits limits;
    private final Provider provider;
    @FunctionalInterface interface AsyncProvider {
        CompletableFuture<List<String>> complete(List<String> prefix, String partial);
    }
    @FunctionalInterface interface Resolver {
        List<String> resolve(List<String> prefix, String partial, List<String> asyncValues);
    }
    private final AsyncProvider asyncProvider;
    private final Resolver resolver;
    private CompletableFuture<List<String>> waiting;
    private long expires;
    private String waitingPartial;
    private final List<String> probes;
    private final Queue<List<String>> pending = new ArrayDeque<>();
    private final Set<List<String>> visited = new HashSet<>();
    private final Map<List<String>, List<String>> discovered = new LinkedHashMap<>();
    private List<String> current;
    private final LinkedHashSet<String> currentValues = new LinkedHashSet<>();
    private int probeIndex;
    private boolean probing;
    private int calls;

    public CompletionScan(String label, Limits limits, Provider provider,
                          List<String> probes, List<List<String>> extraContexts) {
        this.label = label; this.limits = limits; this.provider = provider;
        this.asyncProvider = null; this.resolver = null;
        this.probes = List.copyOf(probes);
        enqueue(List.of());
        for (List<String> p : extraContexts) {
            if (p.stream().allMatch(this::validWord)) enqueue(List.copyOf(p));
        }
    }
    CompletionScan(String label, Limits limits, AsyncProvider asyncProvider, Resolver resolver,
                   List<String> probes, List<List<String>> extraContexts) {
        this.label = label; this.limits = limits; this.provider = null;
        this.asyncProvider = asyncProvider; this.resolver = resolver;
        this.probes = List.copyOf(probes);
        enqueue(List.of());
        for (List<String> p : extraContexts) {
            if (p.stream().allMatch(this::validWord)) enqueue(List.copyOf(p));
        }
    }
    private void enqueue(List<String> prefix) {
        if (prefix.size() >= limits.depth() || visited.size() >= limits.nodes()) return;
        if (visited.add(prefix)) pending.add(prefix);
    }
    public boolean done() { return current == null && pending.isEmpty(); }
    @Override public void cancel() { if (waiting != null) waiting.cancel(false); }
    public int calls() { return calls; }
    public void step() {
        if (done()) return;
        if (current == null) {
            current = pending.remove(); currentValues.clear(); probeIndex = 0; probing = false;
        }
        List<String> returned;
        if (asyncProvider == null) {
            String partial = probing ? probes.get(probeIndex++) : "";
            returned = provider.complete(current, partial);
            calls++;
        } else if (waiting == null) {
            String partial = probing ? probes.get(probeIndex) : "";
            CompletableFuture<List<String>> submitted = asyncProvider.complete(current, partial);
            // A full bounded executor is temporary, not an empty completion result.
            // Yield to other work and retry this exact context later.
            if (submitted == null) return;
            if (probing) probeIndex++;
            waitingPartial = partial;
            waiting = submitted;
            expires = System.nanoTime() + 2_000_000_000L;
            calls++;
            return;
        } else {
            if (!waiting.isDone() && System.nanoTime() < expires) return;
            if (!waiting.isDone()) {
                waiting.cancel(false);
                // A timed-out listener may have cancelled the completion: fail closed.
                returned = List.of();
            } else {
                List<String> asyncValues = waiting.join(); // null means not handled
                // Cancellation, a handled empty result, and queue rejection must not fall
                // through to other callbacks that could reveal denied suggestions.
                returned = asyncValues != null && asyncValues.isEmpty()
                        ? List.of() : resolver.resolve(current, waitingPartial, asyncValues);
            }
            waiting = null;
        }
        if (returned != null) for (String s : returned) {
            if (currentValues.size() >= limits.candidates()) break;
            if (validWord(s)) currentValues.add(s);
        }
        // Only probe prefixes when the empty-input query yielded no candidates.
        if (!probing && currentValues.isEmpty() && !probes.isEmpty()) {
            probing = true; return;
        }
        if (probing && probeIndex < probes.size()) return;
        List<String> values = List.copyOf(currentValues);
        // Many Bukkit completers return the same list at every depth. Stop that expansion.
        boolean repeats = false;
        for (int i = 0; i < current.size(); i++) {
            if (values.equals(discovered.get(current.subList(0, i)))) { repeats = true; break; }
        }
        if (!values.isEmpty() && !repeats) {
            discovered.put(current, values);
            int count = 0;
            for (String s : values) {
                if (count++ >= limits.branches()) break;
                List<String> child = new ArrayList<>(current); child.add(s);
                enqueue(List.copyOf(child));
            }
        }
        current = null;
    }
    private boolean validWord(String s) {
        return CompletionModel.validWord(s, limits.wordLength());
    }
    public Command result() {
        if (!done()) throw new IllegalStateException("Scan is still running");
        return snapshot();
    }
    @Override public Command snapshot() {
        return new Command(label, discovered.entrySet().stream().map(e -> new Node(e.getKey(), e.getValue())).toList());
    }
}

package com.pexserver.completions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Only accepts suggestions for the requested argument, not an earlier failed parser. */
final class JavaSuggestions<S> implements NativeArgumentScan.Provider {
    private final CommandDispatcher<S> dispatcher;
    private final S source;
    JavaSuggestions(CommandDispatcher<S> dispatcher, S source) {
        this.dispatcher = dispatcher; this.source = source;
    }
    @Override public CompletableFuture<List<String>> complete(String input, String argument) {
        var parsed = dispatcher.parse(input, source);
        var context = parsed.getContext().findSuggestionContext(input.length());
        var child = context.parent.getChild(argument);
        if (context.startPos != input.length() || !(child instanceof ArgumentCommandNode<?, ?>) || !child.canUse(source))
            return CompletableFuture.completedFuture(List.of());
        // Brigadier's dispatcher queries every sibling, including unrelated expensive providers.
        // Only the requested argument's choices belong to this sampled Bedrock argument.
        try {
            return child.listSuggestions(parsed.getContext().build(input), new SuggestionsBuilder(input, input.length()))
                    .thenApply(suggestions -> suggestions.getList().stream()
                            .filter(s -> s.getRange().getStart() == input.length())
                            .map(s -> s.getText()).distinct().toList());
        } catch (CommandSyntaxException invalid) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}

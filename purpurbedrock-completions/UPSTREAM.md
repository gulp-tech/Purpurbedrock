This component incorporates MIT-licensed code from gamelist1990/GeyserNativeCompletions,
commit ee49fb91876137856cf6c2bbc4f2a6252a84cfaa; see LICENSE.

Purpurbedrock changes: embedded loading, lower default budgets, rescan following a
command, no command rewriting, preserve the existing /help advertisement, compile
against the exact bundled protocol jar. Client behavior and compatibility with
third-party completion providers still require testing on the target server.

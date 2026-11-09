# Spectator privacy: foundations only

This directory is **not a Bukkit plugin and must not be installed or treated as privacy protection**. No moderator mode or packet masking is enabled. It contains only independently testable building blocks for a future implementation:

* chunk-aligned reversible X/Z offset arithmetic with checked integer overflow;
* a durable append-only JSONL observation audit containing identities and events, but no coordinates.

Before any spectator session is enabled, an implementation must establish and validate a secret, session-scoped offset before any true target coordinates or cached chunks reach the client; consistently transform every position-bearing outbound and inbound path, including chunk coordinates and position-bearing nested NBT, waypoints, events, metadata and plugin messages; and safely handle changing dimensions, teleports, reconnects and all supported client protocols. It must fail closed if masking or durable audit is unavailable, without affecting ordinary players. Previous real packets and familiar landmarks may still reveal a location. A server must not claim protection based on these helpers or HUD hiding alone.

Tests here are arithmetic and file-write tests only. Real packet coverage, state transitions, full server integration, authorization and Bedrock client performance/rendering tests remain necessary.

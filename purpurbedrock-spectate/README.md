# Spectator privacy: foundations only

This directory is **not a Bukkit plugin and must not be installed or treated as privacy protection**. No moderator mode or packet masking is enabled. It contains only independently testable building blocks for a future implementation:

* chunk-aligned reversible X/Z offset arithmetic with checked integer overflow;
* a durable append-only JSONL observation audit containing identities and events, but no coordinates.

Before any spectator session is enabled, an implementation must establish and validate a secret, session-scoped offset before any true target coordinates or cached chunks reach the client; consistently transform every position-bearing outbound and inbound path, including chunk coordinates and position-bearing nested NBT, waypoints, events, metadata and plugin messages; and safely handle changing dimensions, teleports, reconnects and all supported client protocols. It must fail closed if masking or durable audit is unavailable, without affecting ordinary players. Previous real packets and familiar landmarks may still reveal a location. A server must not claim protection based on these helpers or HUD hiding alone.

Tests here are arithmetic and file-write tests only. Real packet coverage, state transitions, full server integration, authorization and Bedrock client performance/rendering tests remain necessary.

Additional standalone components model the ten-block tether, strict exit-command allowlist,
fail-closed lifecycle transitions and atomic storage of the original return state. The return
files necessarily contain the original coordinates and must be private to the server operator;
only the audit avoids coordinates. None of these components are wired to Bukkit or the
protocol. In particular, a plugin join listener is too late to protect the initial login/world
packets after a disconnect: recovery needs a transport-level gate before those packets.

See [docs/protocol-security-plan.md](docs/protocol-security-plan.md) for the blocking
integration work and acceptance criteria. `ReturnStore.save` deliberately refuses to
overwrite an existing recovery record and fails rather than silently writing without
atomic no-overwrite support. It must not be used as a security claim by itself.

The admission coordinator only exercises sequencing through interfaces. Its privacy
bridge has no production implementation; the fake bridge used in tests does not
validate protocol privacy. Audit records and recovery records now share a session ID
and target identity, allowing recovery to identify who was being observed. An
incomplete audit tail or failed durable write blocks admission rather than silently
joining records or exposing the target.

A fixed offset has a known-anchor attack: if a client learns the same point both
before and after transformation, it can calculate the shift. The automated threat
example intentionally demonstrates this vulnerability; it is not a mitigation.
Current storage experiments require POSIX owner-only permissions and fail closed when
that cannot be verified; Windows ACL support has not yet been implemented. Plugins
or administrators sharing the server's operating-system account remain trusted.

`protocol-test` is an isolated security regression demonstration, not a protection:
with the pinned 26.60 codec it shows that changing a chunk header alone leaves the
real coordinates in a synthetic encoded block-entity NBT payload. It does not model
a full renderable chunk or the real client. The working server code is untouched.

A standalone bounded asynchronous audit writer has been added to explore avoiding
server-tick I/O stalls. It rejects duplicate pending events and queue overflow rather
than silently losing entries. It is not connected to admission or Bukkit. A future
adapter must return to the server thread for game operations and keep sessions
quarantined until audit completion, including on timeout and shutdown.

# Spectator coordinate privacy: integration plan (not implemented)

## Scope and guarantee

An enabled session aims to prevent a spectator from reading the target's real X/Z
coordinates from protocol traffic, including with a modified client. An observer may
still infer locations from terrain, signs, prior knowledge, screenshots or external
communications. This feature cannot protect a location a client already received.
A text-HUD toggle alone is not a security boundary. No claim of protection may be
made while the following work is incomplete.

## Lifecycle: fail closed

1. Check permission, explicit target and session preconditions. Persist the moderator's
   original world, position, orientation and game mode before changing anything.
2. Durably record the observation start. If the audit or recovery state cannot be
   stored, do not start. No self-observation, duplicate start or pending recovery.
3. The core must establish the observer's masked world view **before** sending any
   real target location. A simple listener after teleport or player join is too late.
   The core must positively acknowledge readiness; absent/failed/unsupported means
   deny start. It must preserve per-viewer isolation and never alter other sessions.
4. Masking remains in force through target movement, world change, departure and
   restoration. If the target disconnects or a required packet cannot be sanitized,
   stop and recover safely rather than sending unmasked data. Stop all interactions,
   damage, pickups, inventories and commands except the explicit exit path.
5. Restore the saved state, record the stop, clear the protection and finally clear
   the recovery record, in a verified order. If an operation fails, retain recovery
   data and deny new observation. Startup/reconnection must protect initial packets
   before a plugin join event: receiving real coordinates at login is already a leak.

## Protocol and state audit required

The existing code has ordinary and immediate send paths, a delayed post-start queue,
plus code that sends through the upstream session directly. Intercepting only one
`sendUpstreamPacket` method is therefore insufficient. Audit the actual bundled
protocol version and all supported codecs rather than using a list copied from a
newer library. Categorize every packet as handled, proven position-free, or forbidden
while masking is active. Unknown packet classes or unclassified nested data must not
silently pass. Ensure packets shared between viewers are not mutated in place.

Coordinates appear in many forms, not just the displayed player position: X/Z blocks,
negative chunk coordinates, chunk publisher ranges, entity and player spawns and
movement, dimension and spawn changes, block updates, block entities and nested NBT,
sounds, particles, maps, structures, waypoints, world-border updates, forms and
server/plugin text. Each must be transformed, safely suppressed or proven harmless.
Inputs (movement, teleports, block/entity actions) need the inverse transform before
server-side interpretation. Relative vectors, velocities, rotations and local block
coordinates must not be shifted as if they were absolute positions.

Chunk-aligned offsets preserve block-to-chunk relationships. Safe bounds, floating
point precision, world border, teleport range and target travel must be considered;
secret per-session selection alone does not make a transform correct. Changing an
active offset without resetting client caches can corrupt the visible world or reveal
its true location. Concurrency, plugin messages and cached world state are part of the
security model, not just rendering details.

## Acceptance tests before a test candidate

* Automated two-client packet capture: join, observe, move in all directions including
  negative and chunk-boundary coordinates, target teleport/dimension change,
  disconnect/reconnect, stop and restart. Verify actual encoded outgoing data and
  inbound inverse translation, not only object-level calculations or HUD settings.
* Inject known true coordinates into relevant packet fields and nested data. An
  adversarial collector must not find them during any transition. Test both transport
  send paths and all registered supported protocol families; fail closed on unknowns.
* Validate command/interact/damage/inventory restrictions, permission boundaries,
  durable audit and restoration after crash, disk error, missing world, target logout,
  plugin disable and server restart. A recovery record must not be overwritten.
* Regression tests for ordinary non-observers, existing connectivity, world/chunk
  loading, menus and inventory, commands, NPCs, and server performance.
* Real Bedrock phone and PC tests on an isolated server before production. Synthetic
  clients and source inspection are not proof of visual correctness or secrecy.

Do not publish a security-ready plugin until the core gate, plugin adapters and the
above tests actually exist. The current directory deliberately contains no plugin
entry point and no enabled masking path.

## Concrete findings from source review

* A fixed additive shift is recoverable if a client knows one real and shifted
  coordinate pair: subtract the two to obtain the offset, then recover all target
  positions. A previously known world spawn, border or other anchor can expose it.
  `OffsetThreatTest` intentionally reproduces this attack; its passing result means
  the attack works, not that it has been prevented. Anchors must be suppressed or
  independently handled, and even then environmental inference remains possible.
* The chunk serializer builds block-entity tags with absolute X/Z and writes those
  tags inside the chunk payload. Rewriting only the chunk's outer X/Z leaks real
  coordinates in the payload. The chunk packet is reference-counted and its normal
  clone operation is unsupported, so safe per-viewer ownership also needs testing.
* Recovery and audit files may reveal private information to other operating-system
  users if their directories or files are world/group-readable. The current helper
  requires POSIX owner-only permissions and fails closed if it cannot verify them.
  Windows ACL support has not been implemented. Other plugins in the same JVM/OS
  account, administrators and filesystem backups remain trusted; these permissions
  cannot isolate them. Recovery records necessarily include the observer's original
  position; audit entries do not contain coordinates.
* A saved recovery record cannot alone prove that no record was deleted or lost.
  Detecting missing storage/ledger data after a crash or remount needs an independent
  startup gate. A Bukkit join callback cannot implement that gate before login data.
* The current admission and audit examples perform filesystem I/O and `force()`
  synchronously. Hooking them directly into the game tick would let slow storage or
  repeated staff requests stall the server. The real adapter needs a bounded worker,
  deadlines and per-observer rate limits; it must stay quarantined until durable
  completion and avoid touching Bukkit objects from a worker thread.
* A mutable completion future could be completed or cancelled by its caller before the
  audit is durable. The bounded writer exposes a minimal completion stage and tests
  forged completion/cancellation. Its prototype is still not wired into admission;
  callbacks must be scheduled on the game thread, and shutdown must retain quarantine
  until pending work drains or recovery has been made safe.

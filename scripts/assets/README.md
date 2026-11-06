# Bundled remote-player texture

`steve-wide.png`: vanilla 64×64 wide Steve texture, copied from
https://github.com/PrismarineJS/minecraft-assets/blob/master/data/1.21.4/entity/player/wide/steve.png

The engine loads this directly from its JAR once. It must not depend on the
asynchronous default-player-skin download/cache: an immutable entry must never
capture the missing-texture fallback before that cache is ready.

Remote equipment is translated from item ID/count only. Per-stack custom
components (including profile heads) are intentionally omitted from remote
visuals; local inventory and non-player equipment are unchanged.

Remote armor and both hand visuals are coalesced on the viewer event loop over
75 ms, retaining the latest state for every changed slot. Pending work is
invalidated on despawn; local inventory, movement, and non-player equipment
are not delayed. Player-list skin tracking is cleared whenever an entry is
actually removed, so a later spawn can install the bundled texture again.

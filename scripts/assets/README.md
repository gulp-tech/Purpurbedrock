# Remote player appearance and equipment

The optional `steve-wide.png` is retained in the source tree for future use; the current
engine does not bundle or send it for remote players. Bedrock player-list entries require
skin data, so the engine sends one shared built-in black/purple missing-texture placeholder,
without remote capes, persona, profile downloads, or post-spawn skin replacements. The
local viewer's own appearance is not changed. Head textures that require replacing a remote
player's skin cannot be rendered in this mode.

Remote armor and both hand visuals use the full item data and are coalesced on the viewer's
event loop over 75 ms, retaining the latest state for every changed slot. Pending work is
invalidated on despawn. Local inventory, movement, and non-player equipment are not delayed.
Player-list tracking is cleared whenever an entry is removed so later spawns can be restored.
Packet-level tests do not establish real client appearance or frame rate.

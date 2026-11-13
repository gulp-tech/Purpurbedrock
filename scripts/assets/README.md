# Remote player appearance and equipment

The optional `steve-wide.png` is retained in the source tree for future use; the current
engine does not bundle or send it for remote players. Bedrock player-list entries require
skin data, so the engine sends one shared built-in black/purple missing-texture placeholder,
without remote capes, persona, profile downloads, or post-spawn skin replacements. The
local viewer's own appearance is not changed. Head textures that require replacing a remote
player's skin cannot be rendered in this mode.

The `default_player_skins/{slim,wide}` PNGs seed the engine's ordinary offline asset
cache if files are missing. Existing cache files are not replaced by the seeding step;
read/permission errors are reported and the existing empty-skin fallback remains. This is
independent of the remote-player missing-texture policy above. These unmodified vanilla
textures were retrieved from PrismarineJS/minecraft-assets commit
`6c571b0825ee27b81f01127c2bd0d4ba2c89abb9`, under
`data/1.21.4/entity/player/{slim,wide}`; they are Minecraft assets, not original project art.

Remote armor and both hand visuals use the full item data and are coalesced on the viewer's
event loop over 75 ms, retaining the latest state for every changed slot. Pending work is
invalidated on despawn. Local inventory, movement, and non-player equipment are not delayed.
Player-list tracking is cleared whenever an entry is removed so later spawns can be restored.
Packet-level tests do not establish real client appearance or frame rate.

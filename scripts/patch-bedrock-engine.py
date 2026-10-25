#!/usr/bin/env python3
"""
Transforms GeyserMC/Geyser (commit bbd7e67, native Minecraft 26.2) into
Purpurbedrock's self-contained internal Bedrock 26.2 protocol engine:
1. Dedicated 'bedrock/' folder for cache (including cache/player_skulls for HeadDatabase +
   player_skulls.mcpack + GeyserIntegratedPack.mcpack), custom_mappings, extensions, and packs,
   while keeping internal engine config.yml/key.pem/nethernet cleanly inside 'cache/purpurbedrock/engine'.
2. Auto-discovers pre-cached HeadDatabase PNG skull textures in 'bedrock/cache/player_skulls/*.png'
   and preserves them across restarts so custom heads work automatically in menus and worlds.
3. Disables bStats metrics and external geysermc.org NewsHandler polling.
4. Offline instant AssetUtils -> loads assets/minecraft/lang/en_us.json directly from Purpur 26.2's
   server classpath in <1ms with zero Mojang client JAR download on startup.
5. Adds NetherNet Local LAN Discovery (NetherNetDiscoverySignaling on UDP 7551) alongside
   RakNet (UDP 19132) + NetherNet HTTP Signaling/WebRTC so the server automatically appears
   in Minecraft Bedrock 26.x's local LAN Worlds ('Миры') tab.
6. Anti-kick protocol resilience: never kicks Bedrock players on PacketViolationWarningPacket
   or recoverable IllegalPacketExceptions.
"""
import pathlib
from pathlib import Path
import subprocess
import sys

HISTORICAL_GIT_ASSETS = [
    # 1.21.0 - 1.21.40 palettes & converters (commit fb868e41ed)
    ("fb868e41ed", "core/src/main/resources/bedrock/block_palette.1_21_0.nbt", "core/src/main/resources/bedrock/block_palette.1_21_0.nbt"),
    ("fb868e41ed", "core/src/main/resources/bedrock/block_palette.1_21_20.nbt", "core/src/main/resources/bedrock/block_palette.1_21_20.nbt"),
    ("fb868e41ed", "core/src/main/resources/bedrock/block_palette.1_21_30.nbt", "core/src/main/resources/bedrock/block_palette.1_21_30.nbt"),
    ("fb868e41ed", "core/src/main/resources/bedrock/block_palette.1_21_40.nbt", "core/src/main/resources/bedrock/block_palette.1_21_40.nbt"),
    ("fb868e41ed", "core/src/main/resources/bedrock/runtime_item_states.1_21_0.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_0.json"),
    ("fb868e41ed", "core/src/main/resources/bedrock/runtime_item_states.1_21_20.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_20.json"),
    ("fb868e41ed", "core/src/main/resources/bedrock/runtime_item_states.1_21_30.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_30.json"),
    ("fb868e41ed", "core/src/main/resources/bedrock/runtime_item_states.1_21_40.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_40.json"),
    ("fb868e41ed", "core/src/main/resources/bedrock/creative_items.1_21_0.json", "core/src/main/resources/bedrock/creative_items.1_21_0.json"),
    ("fb868e41ed", "core/src/main/resources/bedrock/creative_items.1_21_20.json", "core/src/main/resources/bedrock/creative_items.1_21_20.json"),
    ("fb868e41ed", "core/src/main/resources/bedrock/creative_items.1_21_30.json", "core/src/main/resources/bedrock/creative_items.1_21_30.json"),
    ("fb868e41ed", "core/src/main/resources/bedrock/creative_items.1_21_40.json", "core/src/main/resources/bedrock/creative_items.1_21_40.json"),
    ("fb868e41ed", "core/src/main/java/org/geysermc/geyser/registry/populator/Conversion712_685.java", "core/src/main/java/org/geysermc/geyser/registry/populator/Conversion712_685.java"),
    ("fb868e41ed", "core/src/main/java/org/geysermc/geyser/registry/populator/Conversion729_712.java", "core/src/main/java/org/geysermc/geyser/registry/populator/Conversion729_712.java"),
    # 1.21.50 - 1.21.80 palettes, item_components, and tags (commit 871390b803)
    ("871390b803", "core/src/main/resources/bedrock/block_palette.1_21_50.nbt", "core/src/main/resources/bedrock/block_palette.1_21_50.nbt"),
    ("871390b803", "core/src/main/resources/bedrock/block_palette.1_21_60.nbt", "core/src/main/resources/bedrock/block_palette.1_21_60.nbt"),
    ("871390b803", "core/src/main/resources/bedrock/block_palette.1_21_70.nbt", "core/src/main/resources/bedrock/block_palette.1_21_70.nbt"),
    ("871390b803", "core/src/main/resources/bedrock/block_palette.1_21_80.nbt", "core/src/main/resources/bedrock/block_palette.1_21_80.nbt"),
    ("871390b803", "core/src/main/resources/bedrock/runtime_item_states.1_21_50.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_50.json"),
    ("871390b803", "core/src/main/resources/bedrock/runtime_item_states.1_21_60.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_60.json"),
    ("871390b803", "core/src/main/resources/bedrock/runtime_item_states.1_21_70.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_70.json"),
    ("871390b803", "core/src/main/resources/bedrock/runtime_item_states.1_21_80.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_80.json"),
    ("871390b803", "core/src/main/resources/bedrock/creative_items.1_21_50.json", "core/src/main/resources/bedrock/creative_items.1_21_50.json"),
    ("871390b803", "core/src/main/resources/bedrock/creative_items.1_21_60.json", "core/src/main/resources/bedrock/creative_items.1_21_60.json"),
    ("871390b803", "core/src/main/resources/bedrock/creative_items.1_21_70.json", "core/src/main/resources/bedrock/creative_items.1_21_70.json"),
    ("871390b803", "core/src/main/resources/bedrock/creative_items.1_21_80.json", "core/src/main/resources/bedrock/creative_items.1_21_80.json"),
    ("871390b803", "core/src/main/resources/bedrock/item_components.nbt", "core/src/main/resources/bedrock/item_components.1_21_80.nbt"),
    ("871390b803", "core/src/main/resources/bedrock/item_tags.1_21_50.json", "core/src/main/resources/bedrock/item_tags.1_21_50.json"),
    ("871390b803", "core/src/main/resources/bedrock/item_tags.1_21_60.json", "core/src/main/resources/bedrock/item_tags.1_21_60.json"),
    ("871390b803", "core/src/main/resources/bedrock/item_tags.1_21_70.json", "core/src/main/resources/bedrock/item_tags.1_21_70.json"),
    # 1.21.90 - 1.21.124 palettes, item_components, and tags (commits 53596d05bc & b4e95b9219)
    ("53596d05bc", "core/src/main/resources/bedrock/block_palette.1_21_90.nbt", "core/src/main/resources/bedrock/block_palette.1_21_90.nbt"),
    ("53596d05bc", "core/src/main/resources/bedrock/block_palette.1_21_100.nbt", "core/src/main/resources/bedrock/block_palette.1_21_100.nbt"),
    ("53596d05bc", "core/src/main/resources/bedrock/block_palette.1_21_110.nbt", "core/src/main/resources/bedrock/block_palette.1_21_110.nbt"),
    ("53596d05bc", "core/src/main/resources/bedrock/runtime_item_states.1_21_90.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_90.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/runtime_item_states.1_21_93.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_93.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/runtime_item_states.1_21_100.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_100.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/runtime_item_states.1_21_110.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_110.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/runtime_item_states.1_21_120.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_120.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/creative_items.1_21_90.json", "core/src/main/resources/bedrock/creative_items.1_21_90.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/creative_items.1_21_93.json", "core/src/main/resources/bedrock/creative_items.1_21_93.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/creative_items.1_21_100.json", "core/src/main/resources/bedrock/creative_items.1_21_100.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/creative_items.1_21_110.json", "core/src/main/resources/bedrock/creative_items.1_21_110.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/creative_items.1_21_120.json", "core/src/main/resources/bedrock/creative_items.1_21_120.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/item_components.1_21_90.nbt", "core/src/main/resources/bedrock/item_components.1_21_90.nbt"),
    ("53596d05bc", "core/src/main/resources/bedrock/item_components.1_21_93.nbt", "core/src/main/resources/bedrock/item_components.1_21_93.nbt"),
    ("53596d05bc", "core/src/main/resources/bedrock/item_components.1_21_100.nbt", "core/src/main/resources/bedrock/item_components.1_21_100.nbt"),
    ("53596d05bc", "core/src/main/resources/bedrock/item_components.1_21_110.nbt", "core/src/main/resources/bedrock/item_components.1_21_110.nbt"),
    ("53596d05bc", "core/src/main/resources/bedrock/item_components.1_21_120.nbt", "core/src/main/resources/bedrock/item_components.1_21_120.nbt"),
    ("53596d05bc", "core/src/main/resources/bedrock/item_tags.1_21_90.json", "core/src/main/resources/bedrock/item_tags.1_21_90.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/item_tags.1_21_100.json", "core/src/main/resources/bedrock/item_tags.1_21_100.json"),
    ("53596d05bc", "core/src/main/resources/bedrock/item_tags.1_21_110.json", "core/src/main/resources/bedrock/item_tags.1_21_110.json"),
    ("b4e95b9219", "core/src/main/resources/bedrock/item_tags.1_21_120.json", "core/src/main/resources/bedrock/item_tags.1_21_120.json"),
    # 1.21.130 - 26.23 palettes, item_components, and tags (commit 6075649081)
    ("6075649081", "core/src/main/resources/bedrock/block_palette.1_21_130.nbt", "core/src/main/resources/bedrock/block_palette.1_21_130.nbt"),
    ("6075649081", "core/src/main/resources/bedrock/block_palette.1_26_10.nbt", "core/src/main/resources/bedrock/block_palette.1_26_10.nbt"),
    ("6075649081", "core/src/main/resources/bedrock/block_palette.1_26_20.nbt", "core/src/main/resources/bedrock/block_palette.1_26_20.nbt"),
    ("6075649081", "core/src/main/resources/bedrock/runtime_item_states.1_21_130.json", "core/src/main/resources/bedrock/runtime_item_states.1_21_130.json"),
    ("6075649081", "core/src/main/resources/bedrock/runtime_item_states.1_26_0.json", "core/src/main/resources/bedrock/runtime_item_states.1_26_0.json"),
    ("6075649081", "core/src/main/resources/bedrock/runtime_item_states.1_26_10.json", "core/src/main/resources/bedrock/runtime_item_states.1_26_10.json"),
    ("6075649081", "core/src/main/resources/bedrock/runtime_item_states.1_26_20.json", "core/src/main/resources/bedrock/runtime_item_states.1_26_20.json"),
    ("6075649081", "core/src/main/resources/bedrock/creative_items.1_21_130.json", "core/src/main/resources/bedrock/creative_items.1_21_130.json"),
    ("6075649081", "core/src/main/resources/bedrock/creative_items.1_26_0.json", "core/src/main/resources/bedrock/creative_items.1_26_0.json"),
    ("6075649081", "core/src/main/resources/bedrock/creative_items.1_26_10.json", "core/src/main/resources/bedrock/creative_items.1_26_10.json"),
    ("6075649081", "core/src/main/resources/bedrock/item_components.1_21_130.nbt", "core/src/main/resources/bedrock/item_components.1_21_130.nbt"),
    ("6075649081", "core/src/main/resources/bedrock/item_components.1_26_0.nbt", "core/src/main/resources/bedrock/item_components.1_26_0.nbt"),
    ("6075649081", "core/src/main/resources/bedrock/item_components.1_26_10.nbt", "core/src/main/resources/bedrock/item_components.1_26_10.nbt"),
    ("6075649081", "core/src/main/resources/bedrock/item_components.1_26_20.nbt", "core/src/main/resources/bedrock/item_components.1_26_20.nbt"),
    ("6075649081", "core/src/main/resources/bedrock/item_tags.1_21_130.json", "core/src/main/resources/bedrock/item_tags.1_21_130.json"),
    ("6075649081", "core/src/main/resources/bedrock/item_tags.1_26_0.json", "core/src/main/resources/bedrock/item_tags.1_26_0.json"),
    ("6075649081", "core/src/main/resources/bedrock/item_tags.1_26_10.json", "core/src/main/resources/bedrock/item_tags.1_26_10.json"),
]


MULTI_VERSION_HELPER_JAVA = """package org.geysermc.geyser.registry.populator;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.item.type.Item;
import org.geysermc.geyser.level.block.type.BlockState;
import org.geysermc.geyser.level.block.type.SkullBlock;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.populator.conversion.ChaosCubedConverter;
import org.geysermc.geyser.registry.populator.conversion.ICanHasStates;
import org.geysermc.geyser.registry.type.GeyserBedrockBlock;
import org.geysermc.geyser.registry.type.GeyserMappingItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Purpurbedrock Multi-Version Palette and Mapping Helper (Bedrock 1.21.0 v685 to 26.52 v2193).
public final class MultiVersionPaletteHelper {

    private static final Set<String> PALE_OAK_BLOCKS = Set.of(
        "pale_oak_wood", "pale_oak_planks", "pale_oak_sapling", "pale_oak_log",
        "stripped_pale_oak_log", "stripped_pale_oak_wood", "pale_oak_leaves",
        "pale_oak_hanging_sign", "pale_oak_pressure_plate", "pale_oak_trapdoor",
        "pale_oak_button", "pale_oak_stairs", "pale_oak_slab", "pale_oak_double_slab",
        "pale_oak_fence_gate", "pale_oak_fence", "pale_oak_door",
        "pale_oak_standing_sign", "pale_oak_wall_sign"
    );

    private MultiVersionPaletteHelper() {
    }

    public static NbtMap remapBlockForVersion(int protocolVersion, BlockState javaBlockState, NbtMap tag) {
        if (protocolVersion >= 2193) {
            return tag;
        }
        // < 26.50 (v2193): strip stair corner & fence/pane/bars/tripwire server connections
        tag = ICanHasStates.convertBlock(tag);

        // < 26.30 (v1001): ChaosCubed sulfur/cinnabar fallbacks
        if (protocolVersion < 1001) {
            tag = ChaosCubedConverter.convertBlock(tag);
        }

        // < 26.20 (v975): golden_dandelion -> dandelion
        if (protocolVersion < 975 && tag.getString("name").equals("minecraft:golden_dandelion")) {
            tag = withoutStates("minecraft:dandelion");
        }

        // < 1.21.110 (v844): shelves, copper bars/chains/lanterns/torches/statues, oxidized lightning rods
        if (protocolVersion < 844) {
            String name = tag.getString("name");
            if (name.equals("minecraft:iron_chain") || name.endsWith("copper_chain")) {
                tag = withId(tag, "minecraft:chain");
            } else if (name.endsWith("lightning_rod")) {
                NbtMapBuilder states = tag.getCompound("states").toBuilder();
                states.remove("powered_bit");
                tag = tag.toBuilder().putString("name", "minecraft:lightning_rod").putCompound("states", states.build()).build();
            } else if (name.endsWith("_shelf")) {
                tag = withoutStates("minecraft:bookshelf");
            } else if (name.endsWith("copper_golem_statue")) {
                tag = withoutStates("minecraft:cut_copper");
            } else if (name.equals("minecraft:copper_torch")) {
                tag = withId(tag, "minecraft:torch");
            } else if (name.endsWith("copper_bars")) {
                tag = withId(tag, "minecraft:iron_bars");
            } else if (name.endsWith("copper_lantern")) {
                tag = withId(tag, "minecraft:lantern");
            }
        }

        // < 1.21.100 (v827): copper chests -> chest
        if (protocolVersion < 827 && tag.getString("name").endsWith("copper_chest")) {
            tag = withId(tag, "minecraft:chest");
        }

        // < 1.21.80 (v800): dried_ghast -> bone_block
        if (protocolVersion < 800 && tag.getString("name").equals("minecraft:dried_ghast")) {
            tag = withoutStates("minecraft:bone_block");
        }

        // < 1.21.70 (v786): spring drop vegetation
        if (protocolVersion < 786) {
            String name = tag.getString("name");
            if (name.equals("minecraft:bush")) {
                tag = withId(tag, "minecraft:fern");
            } else if (name.equals("minecraft:firefly_bush")) {
                tag = withId(tag, "minecraft:deadbush");
            } else if (name.equals("minecraft:tall_dry_grass") || name.equals("minecraft:short_dry_grass")) {
                tag = withId(tag, "minecraft:short_grass");
            } else if (name.equals("minecraft:cactus_flower")) {
                tag = withoutStates("minecraft:poppy");
            } else if (name.equals("minecraft:leaf_litter") || name.equals("minecraft:wildflowers")) {
                tag = withoutStates("minecraft:pink_petals");
            }
        }

        // < 1.21.60 (v776): creaking_heart boolean active & door/fence_gate cardinal_direction -> int direction
        if (protocolVersion < 776) {
            String name = tag.getString("name");
            if (name.equals("minecraft:creaking_heart")) {
                NbtMapBuilder states = tag.getCompound("states").toBuilder();
                Object val = states.remove("creaking_heart_state");
                states.putBoolean("active", "awake".equals(val));
                tag = tag.toBuilder().putCompound("states", states.build()).build();
            } else if (name.endsWith("_door") || name.endsWith("fence_gate")) {
                NbtMap curStates = tag.getCompound("states");
                if (curStates.containsKey("minecraft:cardinal_direction")) {
                    NbtMapBuilder states = curStates.toBuilder();
                    String dir = String.valueOf(states.remove("minecraft:cardinal_direction"));
                    int dirInt = switch (dir) {
                        case "south" -> 0;
                        case "west" -> 1;
                        case "north" -> 2;
                        case "east" -> 3;
                        default -> 0;
                    };
                    states.putInt("direction", dirInt);
                    tag = tag.toBuilder().putCompound("states", states.build()).build();
                }
            }
        }

        // < 1.21.50 (v766): Pale Garden & Resin blocks
        if (protocolVersion < 766) {
            String bare = tag.getString("name").replace("minecraft:", "");
            if (PALE_OAK_BLOCKS.contains(bare)) {
                tag = withId(tag, "minecraft:" + bare.replace("pale_oak", "birch"));
            } else {
                tag = switch (bare) {
                    case "resin_brick_double_slab" -> withId(tag, "minecraft:red_sandstone_double_slab");
                    case "pale_moss_block" -> withId(tag, "minecraft:moss_block");
                    case "pale_moss_carpet" -> withoutStates("minecraft:moss_carpet");
                    case "pale_hanging_moss" -> withoutStates("minecraft:hanging_roots");
                    case "open_eyeblossom" -> withoutStates("minecraft:oxeye_daisy");
                    case "closed_eyeblossom" -> withoutStates("minecraft:white_tulip");
                    case "resin_clump" -> withoutStates("minecraft:deadbush");
                    case "resin_block" -> withoutStates("minecraft:red_sandstone");
                    case "resin_bricks" -> withoutStates("minecraft:cut_red_sandstone");
                    case "resin_brick_stairs" -> withId(tag, "minecraft:red_sandstone_stairs");
                    case "resin_brick_slab" -> withId(tag, "minecraft:red_sandstone_slab");
                    case "resin_brick_wall" -> withId(tag, "minecraft:red_sandstone_wall");
                    case "chiseled_resin_bricks" -> withId(tag, "minecraft:chiseled_red_sandstone");
                    case "creaking_heart" -> withoutStates("minecraft:chiseled_polished_blackstone");
                    default -> tag;
                };
            }
        }

        // == 1.21.40 (v748) & < 1.21.40 (< v748): skulls, mushroom_stem, wood stripped_bit
        if (protocolVersion == 748) {
            if (tag.getString("name").endsWith("_wood") && tag.getCompound("states").containsKey("stripped_bit")) {
                NbtMapBuilder states = tag.getCompound("states").toBuilder();
                states.remove("stripped_bit");
                tag = tag.toBuilder().putCompound("states", states.build()).build();
            }
        } else if (protocolVersion < 748) {
            if (javaBlockState.block() instanceof SkullBlock) {
                int facing = 1;
                NbtMap curStates = tag.getCompound("states");
                if (curStates.containsKey("facing_direction")) {
                    facing = curStates.getInt("facing_direction");
                }
                tag = NbtMap.builder()
                    .putString("name", "minecraft:skull")
                    .putCompound("states", NbtMap.builder().putInt("facing_direction", facing).build())
                    .build();
            } else if (tag.getString("name").equals("minecraft:mushroom_stem")) {
                tag = NbtMap.builder()
                    .putString("name", "minecraft:brown_mushroom_block")
                    .putCompound("states", NbtMap.builder().putInt("huge_mushroom_bits", 10).build())
                    .build();
            } else if (tag.getString("name").endsWith("_wood")) {
                boolean stripped = tag.getString("name").startsWith("minecraft:stripped_");
                String baseWood = tag.getString("name").replace("stripped_", "");
                NbtMapBuilder states = tag.getCompound("states").toBuilder();
                states.putBoolean("stripped_bit", stripped);
                tag = tag.toBuilder().putString("name", baseWood).putCompound("states", states.build()).build();
            }
        }

        // < 1.21.30 (v729) & < 1.21.20 (v712)
        if (protocolVersion < 712) {
            tag = Conversion712_685.remapBlock(tag);
        } else if (protocolVersion < 729) {
            tag = Conversion729_712.remapBlock(tag);
        }

        return tag;
    }

    public static GeyserBedrockBlock resolveBlockDefinition(
            NbtMap remappedTag,
            Map<NbtMap, GeyserBedrockBlock> exactMap,
            Map<String, List<GeyserBedrockBlock>> byNameMap) {
        GeyserBedrockBlock direct = exactMap.get(remappedTag);
        if (direct != null) {
            return direct;
        }
        String name = remappedTag.getString("name");
        NbtMap wantedStates = remappedTag.getCompound("states");

        GeyserBedrockBlock sameNameBest = bestStateMatch(byNameMap.get(name), wantedStates);
        if (sameNameBest != null) {
            return sameNameBest;
        }

        // Structural collision-preserving fallback candidates
        String[] candidates;
        if (name.endsWith("_stairs")) {
            candidates = new String[]{"minecraft:oak_stairs", "minecraft:stone_stairs"};
        } else if (name.endsWith("_double_slab")) {
            candidates = new String[]{"minecraft:oak_double_slab", "minecraft:double_wooden_slab", "minecraft:planks", "minecraft:stone"};
        } else if (name.endsWith("_slab")) {
            candidates = new String[]{"minecraft:oak_slab", "minecraft:wooden_slab", "minecraft:stone_block_slab"};
        } else if (name.endsWith("_wall")) {
            candidates = new String[]{"minecraft:cobblestone_wall"};
        } else if (name.endsWith("_fence_gate")) {
            candidates = new String[]{"minecraft:fence_gate"};
        } else if (name.endsWith("_fence")) {
            candidates = new String[]{"minecraft:oak_fence", "minecraft:fence"};
        } else if (name.endsWith("_door")) {
            candidates = new String[]{"minecraft:wooden_door"};
        } else if (name.endsWith("_trapdoor")) {
            candidates = new String[]{"minecraft:trapdoor"};
        } else if (name.endsWith("_button")) {
            candidates = new String[]{"minecraft:wooden_button"};
        } else if (name.endsWith("_pressure_plate")) {
            candidates = new String[]{"minecraft:wooden_pressure_plate"};
        } else if (name.contains("leaves")) {
            candidates = new String[]{"minecraft:oak_leaves", "minecraft:leaves"};
        } else if (name.contains("glass_pane")) {
            candidates = new String[]{"minecraft:glass_pane"};
        } else if (name.contains("carpet")) {
            candidates = new String[]{"minecraft:white_carpet", "minecraft:carpet"};
        } else if (name.contains("bush") || name.contains("grass") || name.contains("flower") || name.contains("litter") || name.contains("petals") || name.contains("moss") || name.contains("clump")) {
            candidates = new String[]{"minecraft:short_grass", "minecraft:tallgrass", "minecraft:deadbush", "minecraft:air"};
        } else {
            candidates = new String[]{"minecraft:stone", "minecraft:unknown", "minecraft:air"};
        }

        for (String candidate : candidates) {
            GeyserBedrockBlock match = bestStateMatch(byNameMap.get(candidate), wantedStates);
            if (match != null) {
                return match;
            }
        }

        for (List<GeyserBedrockBlock> anyList : byNameMap.values()) {
            if (!anyList.isEmpty()) {
                return anyList.getFirst();
            }
        }
        return null;
    }

    private static GeyserBedrockBlock bestStateMatch(List<GeyserBedrockBlock> candidates, NbtMap wantedStates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (wantedStates == null || wantedStates.isEmpty() || candidates.size() == 1) {
            return candidates.getFirst();
        }
        GeyserBedrockBlock best = candidates.getFirst();
        int bestScore = -1;
        for (GeyserBedrockBlock candidate : candidates) {
            NbtMap candStates = candidate.getState().getCompound("states");
            int score = 0;
            for (Map.Entry<String, Object> e : wantedStates.entrySet()) {
                if (Objects.equals(candStates.get(e.getKey()), e.getValue())) {
                    score += 2;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    public static GeyserMappingItem remapItemForVersion(int protocolVersion, Item item, GeyserMappingItem mapping) {
        if (protocolVersion < 844) {
            if (item == Items.IRON_CHAIN || item.javaIdentifier().endsWith("copper_chain")) {
                mapping = mapping.withBedrockIdentifier("minecraft:chain");
            }
        }
        if (protocolVersion < 748) {
            mapping = switch (mapping.getBedrockIdentifier()) {
                case "minecraft:skeleton_skull" -> mapping.withBedrockIdentifier("minecraft:skull").withBedrockData(0);
                case "minecraft:wither_skeleton_skull" -> mapping.withBedrockIdentifier("minecraft:skull").withBedrockData(1);
                case "minecraft:zombie_head" -> mapping.withBedrockIdentifier("minecraft:skull").withBedrockData(2);
                case "minecraft:player_head" -> mapping.withBedrockIdentifier("minecraft:skull").withBedrockData(3);
                case "minecraft:creeper_head" -> mapping.withBedrockIdentifier("minecraft:skull").withBedrockData(4);
                case "minecraft:dragon_head" -> mapping.withBedrockIdentifier("minecraft:skull").withBedrockData(5);
                case "minecraft:piglin_head" -> mapping.withBedrockIdentifier("minecraft:skull").withBedrockData(6);
                case "minecraft:mushroom_stem" -> mapping.withBedrockIdentifier("minecraft:brown_mushroom_block").withBedrockData(10);
                default -> mapping;
            };
        }
        if (protocolVersion < 712) {
            mapping = Conversion712_685.remapItem(item, mapping);
        } else if (protocolVersion < 729) {
            mapping = Conversion729_712.remapItem(item, mapping);
        }
        return mapping;
    }

    public static Map<Item, Item> buildJavaOnlyItemFallbacks(int protocolVersion) {
        Map<Item, Item> out = new HashMap<>();
        if (protocolVersion < 1001) {
            out.putAll(ChaosCubedConverter.convertItem());
        }
        if (protocolVersion < 975) {
            out.put(Items.GOLDEN_DANDELION, Items.DANDELION);
        }
        if (protocolVersion < 898) {
            putById(out, "minecraft:wooden_spear", "minecraft:wooden_sword");
            putById(out, "minecraft:stone_spear", "minecraft:stone_sword");
            putById(out, "minecraft:copper_spear", "minecraft:stone_sword");
            putById(out, "minecraft:iron_spear", "minecraft:iron_sword");
            putById(out, "minecraft:golden_spear", "minecraft:golden_sword");
            putById(out, "minecraft:diamond_spear", "minecraft:diamond_sword");
            putById(out, "minecraft:netherite_spear", "minecraft:netherite_sword");
            putById(out, "minecraft:copper_nautilus_armor", "minecraft:iron_horse_armor");
            putById(out, "minecraft:iron_nautilus_armor", "minecraft:iron_horse_armor");
            putById(out, "minecraft:golden_nautilus_armor", "minecraft:golden_horse_armor");
            putById(out, "minecraft:diamond_nautilus_armor", "minecraft:diamond_horse_armor");
            putById(out, "minecraft:netherite_nautilus_armor", "minecraft:diamond_horse_armor");
            putById(out, "minecraft:netherite_horse_armor", "minecraft:diamond_horse_armor");
            putById(out, "minecraft:nautilus_spawn_egg", "minecraft:dolphin_spawn_egg");
            putById(out, "minecraft:zombie_nautilus_spawn_egg", "minecraft:dolphin_spawn_egg");
            putById(out, "minecraft:camel_husk_spawn_egg", "minecraft:camel_spawn_egg");
            putById(out, "minecraft:parched_spawn_egg", "minecraft:husk_spawn_egg");
        }
        if (protocolVersion < 844) {
            for (Item it : Registries.JAVA_ITEMS.get()) {
                String id = it.javaIdentifier();
                if (id.endsWith("_shelf")) {
                    putById(out, id, "minecraft:bookshelf");
                } else if (id.endsWith("copper_bars")) {
                    putById(out, id, "minecraft:iron_bars");
                } else if (id.endsWith("copper_lantern")) {
                    putById(out, id, "minecraft:lantern");
                } else if (id.endsWith("copper_golem_statue")) {
                    putById(out, id, "minecraft:cut_copper");
                } else if (id.endsWith("copper_chain")) {
                    putById(out, id, "minecraft:iron_chain");
                } else if (id.endsWith("_lightning_rod")) {
                    putById(out, id, "minecraft:lightning_rod");
                }
            }
            putById(out, "minecraft:copper_torch", "minecraft:torch");
            putById(out, "minecraft:copper_sword", "minecraft:stone_sword");
            putById(out, "minecraft:copper_pickaxe", "minecraft:stone_pickaxe");
            putById(out, "minecraft:copper_axe", "minecraft:stone_axe");
            putById(out, "minecraft:copper_shovel", "minecraft:stone_shovel");
            putById(out, "minecraft:copper_hoe", "minecraft:stone_hoe");
            putById(out, "minecraft:copper_helmet", "minecraft:chainmail_helmet");
            putById(out, "minecraft:copper_chestplate", "minecraft:chainmail_chestplate");
            putById(out, "minecraft:copper_leggings", "minecraft:chainmail_leggings");
            putById(out, "minecraft:copper_boots", "minecraft:chainmail_boots");
            putById(out, "minecraft:copper_horse_armor", "minecraft:iron_horse_armor");
            putById(out, "minecraft:copper_nugget", "minecraft:iron_nugget");
            putById(out, "minecraft:copper_golem_spawn_egg", "minecraft:iron_golem_spawn_egg");
        }
        if (protocolVersion < 827) {
            for (Item it : Registries.JAVA_ITEMS.get()) {
                String id = it.javaIdentifier();
                if (id.endsWith("copper_chest")) {
                    putById(out, id, "minecraft:chest");
                }
            }
        }
        if (protocolVersion < 818) {
            putById(out, "minecraft:music_disc_lava_chicken", "minecraft:music_disc_cat");
        }
        if (protocolVersion < 800) {
            for (Item it : Registries.JAVA_ITEMS.get()) {
                String id = it.javaIdentifier();
                if (id.endsWith("_harness")) {
                    putById(out, id, "minecraft:saddle");
                }
            }
            putById(out, "minecraft:dried_ghast", "minecraft:bone_block");
            putById(out, "minecraft:happy_ghast_spawn_egg", "minecraft:ghast_spawn_egg");
            putById(out, "minecraft:music_disc_tears", "minecraft:music_disc_blocks");
        }
        if (protocolVersion < 786) {
            putById(out, "minecraft:bush", "minecraft:fern");
            putById(out, "minecraft:firefly_bush", "minecraft:dead_bush");
            putById(out, "minecraft:short_dry_grass", "minecraft:short_grass");
            putById(out, "minecraft:tall_dry_grass", "minecraft:short_grass");
            putById(out, "minecraft:cactus_flower", "minecraft:poppy");
            putById(out, "minecraft:leaf_litter", "minecraft:pink_petals");
            putById(out, "minecraft:wildflowers", "minecraft:pink_petals");
            putById(out, "minecraft:blue_egg", "minecraft:egg");
            putById(out, "minecraft:brown_egg", "minecraft:egg");
        }
        if (protocolVersion < 766) {
            for (Item it : Registries.JAVA_ITEMS.get()) {
                String id = it.javaIdentifier();
                if (id.contains("pale_oak") && !out.containsKey(it)) {
                    putById(out, id, id.replace("pale_oak", "birch"));
                }
            }
            putById(out, "minecraft:pale_moss_block", "minecraft:moss_block");
            putById(out, "minecraft:pale_moss_carpet", "minecraft:moss_carpet");
            putById(out, "minecraft:pale_hanging_moss", "minecraft:hanging_roots");
            putById(out, "minecraft:open_eyeblossom", "minecraft:oxeye_daisy");
            putById(out, "minecraft:closed_eyeblossom", "minecraft:white_tulip");
            putById(out, "minecraft:resin_clump", "minecraft:orange_dye");
            putById(out, "minecraft:resin_brick", "minecraft:brick");
            putById(out, "minecraft:resin_block", "minecraft:red_sandstone");
            putById(out, "minecraft:resin_bricks", "minecraft:cut_red_sandstone");
            putById(out, "minecraft:resin_brick_stairs", "minecraft:red_sandstone_stairs");
            putById(out, "minecraft:resin_brick_slab", "minecraft:red_sandstone_slab");
            putById(out, "minecraft:resin_brick_wall", "minecraft:red_sandstone_wall");
            putById(out, "minecraft:chiseled_resin_bricks", "minecraft:chiseled_red_sandstone");
            putById(out, "minecraft:creaking_heart", "minecraft:chiseled_polished_blackstone");
            putById(out, "minecraft:creaking_spawn_egg", "minecraft:enderman_spawn_egg");
        }
        if (protocolVersion < 748) {
            for (Item it : Registries.JAVA_ITEMS.get()) {
                String id = it.javaIdentifier();
                if ((id.equals("minecraft:bundle") || id.endsWith("_bundle")) && !out.containsKey(it)) {
                    putById(out, id, "minecraft:chest");
                }
            }
            putById(out, "minecraft:field_masoned_banner_pattern", "minecraft:flower_banner_pattern");
            putById(out, "minecraft:bordure_indented_banner_pattern", "minecraft:flower_banner_pattern");
        }
        // Resolve transitive fallback chains (e.g. pale_oak_shelf -> oak_shelf -> bookshelf)
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Item, Item> e : new HashMap<>(out).entrySet()) {
                Item next = out.get(e.getValue());
                if (next != null && next != e.getValue()) {
                    out.put(e.getKey(), next);
                    changed = true;
                }
            }
        }
        return out;
    }

    private static void putById(Map<Item, Item> map, String srcId, String dstId) {
        Item src = Registries.JAVA_ITEM_IDENTIFIERS.get(srcId);
        Item dst = Registries.JAVA_ITEM_IDENTIFIERS.get(dstId);
        if (src != null && dst != null) {
            map.put(src, dst);
        }
    }

    private static NbtMap withId(NbtMap tag, String id) {
        NbtMapBuilder b = tag.toBuilder();
        b.replace("name", id);
        return b.build();
    }

    private static NbtMap withoutStates(String id) {
        return NbtMap.builder()
            .putString("name", id)
            .putCompound("states", NbtMap.EMPTY)
            .build();
    }
}
"""


def extract_historical_git_assets(root: Path) -> None:
    if "--skip-git-assets" in sys.argv:
        print("Skipping historical git asset extraction (--skip-git-assets).")
        return
    git_dir = root / ".git"
    if not git_dir.exists():
        raise RuntimeError(f"Expected git repository at {root} to extract historical multi-version palettes!")
    for sha, src_rel, dst_rel in HISTORICAL_GIT_ASSETS:
        raw = subprocess.check_output(["git", "-C", str(root), "show", f"{sha}:{src_rel}"])
        dst = root / dst_rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(raw)
    print(f"Extracted {len(HISTORICAL_GIT_ASSETS)} historical Bedrock multi-version assets (1.21.0 -> 26.23) from git.")




def replace_exact(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"[{label}] Expected snippet not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"[OK] Patched {label} in {path.name}")


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("Usage: patch-bedrock-engine.py <geyser-root> [--skip-git-assets]")

    root = pathlib.Path(sys.argv[1]).resolve()

    # 0. Trim settings.gradle.kts to only build :ap, :api, :common, :core, :spigot (skip Loom/NeoForge)
    replace_exact(
        root / "settings.gradle.kts",
        """include(":ap")
include(":api")
include(":bungeecord")
include(":fabric")
include(":gametest")
include(":neoforge")
include(":mod")
include(":spigot")
include(":standalone")
include(":velocity")
include(":viaproxy")
include(":common")
include(":core")

// Specify project dirs
project(":bungeecord").projectDir = file("bootstrap/bungeecord")
project(":fabric").projectDir = file("bootstrap/mod/fabric")
project(":gametest").projectDir = file("bootstrap/mod/gametest")
project(":neoforge").projectDir = file("bootstrap/mod/neoforge")
project(":mod").projectDir = file("bootstrap/mod")
project(":spigot").projectDir = file("bootstrap/spigot")
project(":standalone").projectDir = file("bootstrap/standalone")
project(":velocity").projectDir = file("bootstrap/velocity")
project(":viaproxy").projectDir = file("bootstrap/viaproxy")""",
        """include(":ap")
include(":api")
include(":common")
include(":core")
include(":spigot")

project(":spigot").projectDir = file("bootstrap/spigot")""",
        "settings.gradle.kts minimal spigot build",
    )

    # 1. GeyserSpigotPlugin.java: point getConfigFolder() to bedrock/ (so cache, player_skulls, packs,
    #    extensions, custom_mappings all live cleanly in bedrock/), keep Floodgate fallback key in engineDir,
    #    and disable bStats telemetry.
    spigot_plugin = root / "bootstrap/spigot/src/main/java/org/geysermc/geyser/platform/spigot/GeyserSpigotPlugin.java"
    replace_exact(
        spigot_plugin,
        """    @Override
    public Path getConfigFolder() {
        return getDataFolder().toPath();
    }""",
        """    @Override
    public Path getConfigFolder() {
        Path bedrockDataDir = Path.of(System.getProperty("purpurbedrock.dataDir", "bedrock")).toAbsolutePath();
        try {
            java.nio.file.Files.createDirectories(bedrockDataDir);
        } catch (java.io.IOException ignored) {
        }
        return bedrockDataDir;
    }""",
        "GeyserSpigotPlugin.getConfigFolder",
    )
    replace_exact(
        spigot_plugin,
        """        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        Path geyserDataFolder = getDataFolder().toPath();
        Path floodgateDataFolder = floodgate != null ? floodgate.getDataFolder().toPath() : null;""",
        """        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        Path geyserDataFolder = Path.of(System.getProperty("purpurbedrock.engineDir", "cache/purpurbedrock/engine")).toAbsolutePath();
        Path floodgateDataFolder = floodgate != null ? floodgate.getDataFolder().toPath() : null;""",
        "GeyserSpigotPlugin.getFloodgateKeyPath",
    )
    replace_exact(
        spigot_plugin,
        """    @Override
    public MetricsPlatform createMetricsPlatform() {
        return new SpigotMetrics(this);
    }""",
        """    @Override
    public MetricsPlatform createMetricsPlatform() {
        return null; // Purpurbedrock: disable external bStats telemetry
    }""",
        "GeyserSpigotPlugin.createMetricsPlatform",
    )

    # 1b. ConfigLoader.java: read internal config.yml from purpurbedrock.engineDir so bedrock/ only holds user content
    config_loader = root / "core/src/main/java/org/geysermc/geyser/configuration/ConfigLoader.java"
    replace_exact(
        config_loader,
        """    public ConfigLoader(GeyserBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.platformType = bootstrap.platformType();
        configFile = new File(bootstrap.getConfigFolder().toFile(), "config.yml");
    }""",
        """    public ConfigLoader(GeyserBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.platformType = bootstrap.platformType();
        Path engineDir = Path.of(System.getProperty("purpurbedrock.engineDir", "cache/purpurbedrock/engine")).toAbsolutePath();
        try {
            java.nio.file.Files.createDirectories(engineDir);
        } catch (java.io.IOException ignored) {
        }
        configFile = engineDir.resolve("config.yml").toFile();
    }""",
        "ConfigLoader.constructor",
    )

    # 2. NewsHandler.java: disable external geysermc.org polling
    news_handler = root / "core/src/main/java/org/geysermc/geyser/util/NewsHandler.java"
    replace_exact(
        news_handler,
        """    public NewsHandler(String branch, int build) {
        this.branch = branch;
        this.build = build;

        executorService.scheduleWithFixedDelay(this::checkNews, 0, 30, TimeUnit.MINUTES);
    }""",
        """    public NewsHandler(String branch, int build) {
        this.branch = branch;
        this.build = build;
        // Purpurbedrock: disabled external news polling to geysermc.org
    }""",
        "NewsHandler.constructor",
    )

    # 3. AssetUtils.java: load en_us.json directly from Purpur 26.2 server classpath without 50MB download
    asset_utils = root / "core/src/main/java/org/geysermc/geyser/util/AssetUtils.java"
    replace_exact(
        asset_utils,
        """    public static CompletableFuture<Void> generateAssetCache() {
        return CompletableFuture.supplyAsync(() -> {""",
        """    public static CompletableFuture<Void> generateAssetCache() {
        if (Boolean.parseBoolean(System.getProperty("purpurbedrock.offlineAssets", "true"))) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {""",
        "AssetUtils.generateAssetCache",
    )
    replace_exact(
        asset_utils,
        """    public static void downloadAndRunClientJarTasks() {
        if (CLIENT_JAR_INFO == null) {
            // Likely failed to download
            GeyserImpl.getInstance().getLogger().debug("Skipping en_US hash check as client jar is null.");
            return;
        }""",
        """    public static void downloadAndRunClientJarTasks() {
        if (Boolean.parseBoolean(System.getProperty("purpurbedrock.offlineAssets", "true"))) {
            ClientJarTask task;
            while ((task = CLIENT_JAR_TASKS.poll()) != null) {
                try {
                    InputStream classpathStream = AssetUtils.class.getClassLoader().getResourceAsStream(task.asset);
                    if (classpathStream == null && Thread.currentThread().getContextClassLoader() != null) {
                        classpathStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(task.asset);
                    }
                    if (classpathStream != null) {
                        try (InputStream in = classpathStream) {
                            task.ifNewDownload.accept(in);
                        }
                        task.whenDone.run();
                    }
                } catch (Exception ignored) {
                }
            }
            return;
        }
        if (CLIENT_JAR_INFO == null) {
            // Likely failed to download
            GeyserImpl.getInstance().getLogger().debug("Skipping en_US hash check as client jar is null.");
            return;
        }""",
        "AssetUtils.downloadAndRunClientJarTasks",
    )

    # 4. NetherNetServer.java: keep internal nethernet temp dir in engineDir + add NetherNetDiscoverySignaling on UDP 7551
    nethernet_server = root / "core/src/main/java/org/geysermc/geyser/network/bedrock/nethernet/NetherNetServer.java"
    replace_exact(
        nethernet_server,
        """import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;""",
        """import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetDiscoverySignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;""",
        "NetherNetServer.imports",
    )
    replace_exact(
        nethernet_server,
        """    private EventLoopGroup inbuiltEventLoopGroup;
    private Channel inbuiltChannel;
    private NetherNetChannelInitialiser inbuiltInitialiser;""",
        """    private EventLoopGroup inbuiltEventLoopGroup;
    private Channel inbuiltChannel;
    private NetherNetChannelInitialiser inbuiltInitialiser;
    private NetherNetDiscoverySignaling lanDiscoverySignaling;
    private EventLoopGroup lanDiscoveryEventLoopGroup;
    private Channel lanDiscoveryChannel;
    private NetherNetChannelInitialiser lanDiscoveryInitialiser;""",
        "NetherNetServer.fields",
    )
    replace_exact(
        nethernet_server,
        """        this.dataFolder = geyser.getBootstrap().getConfigFolder().resolve("nethernet");""",
        """        this.dataFolder = java.nio.file.Path.of(System.getProperty("purpurbedrock.engineDir", "cache/purpurbedrock/engine")).toAbsolutePath().resolve("nethernet");""",
        "NetherNetServer.dataFolder",
    )
    replace_exact(
        nethernet_server,
        """        if (inbuilt) startInbuilt();
        if (provider) startProvider();
    }""",
        """        if (inbuilt) startInbuilt();
        if (Boolean.parseBoolean(System.getProperty("purpurbedrock.lanDiscovery", "true"))) {
            startLanDiscovery();
        }
        if (provider) startProvider();
    }

    private PongData buildLanPongData() {
        BedrockPong pong = pingResponder.onQuery(GUID, new InetSocketAddress("127.0.0.1", 0), null);
        return new PongData.Builder()
                .setServerName(pong.motd() != null ? pong.motd() : "Purpurbedrock 26.2")
                .setLevelName(pong.subMotd() != null ? pong.subMotd() : "Purpurbedrock LAN")
                .setProtocol(pong.protocolVersion())
                .setVersion(pong.version())
                .setPlayerCount(pong.playerCount())
                .setMaxPlayerCount(Math.max(1, pong.maximumPlayerCount()))
                .build();
    }

    private void startLanDiscovery() {
        BedrockListener listener = geyser.config().bedrock();
        int discoveryPort = Integer.getInteger("purpurbedrock.lanDiscoveryPort", NetherNetConstants.DISCOVERY_PORT);
        int lanIcePort = Integer.getInteger("purpurbedrock.lanIcePort", webrtcPort + 1);
        try {
            NetherNetLogging.setNativeLogLevel("WARN");
            InetSocketAddress bindAddr = new InetSocketAddress(listener.address(), discoveryPort);
            this.lanDiscoverySignaling = new NetherNetDiscoverySignaling(GUID ^ 0x4C414EL, bindAddr);
            this.lanDiscoverySignaling.setAdvertisementData(buildLanPongData());

            this.lanDiscoveryEventLoopGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
            this.lanDiscoveryInitialiser = new NetherNetChannelInitialiser(geyser);

            ServerBootstrap lanBootstrap = new ServerBootstrap()
                    .group(this.lanDiscoveryEventLoopGroup)
                    .channelFactory(NetherNetChannelFactory.server(this.lanDiscoverySignaling))
                    .childHandler(this.lanDiscoveryInitialiser)
                    .option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS, new InetSocketAddress(listener.address(), lanIcePort));

            this.lanDiscoveryChannel = lanBootstrap.bind(bindAddr).sync().channel();
            this.lanDiscoveryEventLoopGroup.next().scheduleAtFixedRate(() -> {
                if (!stopping && this.lanDiscoverySignaling != null) {
                    try {
                        this.lanDiscoverySignaling.setAdvertisementData(buildLanPongData());
                    } catch (Throwable ignored) {
                    }
                }
            }, 3, 3, TimeUnit.SECONDS);

            logger().info("[Purpurbedrock] NetherNet LAN Discovery active on UDP " + listener.address() + ":" + discoveryPort
                    + " (LAN ICE UDP " + lanIcePort + ") — server visible in Bedrock 'Миры' LAN tab.");
        } catch (Throwable e) {
            closeLanDiscoveryResources();
            logger().info("[Purpurbedrock] NetherNet LAN UDP " + discoveryPort + " unavailable (" + e.getClass().getSimpleName()
                    + "); RakNet/Signaling remains active.");
        }
    }

    private void closeLanDiscoveryResources() {
        if (lanDiscoveryChannel != null) {
            try { lanDiscoveryChannel.close(); } catch (Throwable ignored) {}
            lanDiscoveryChannel = null;
        }
        if (lanDiscoverySignaling != null) {
            try { lanDiscoverySignaling.close(); } catch (Throwable ignored) {}
            lanDiscoverySignaling = null;
        }
        if (lanDiscoveryEventLoopGroup != null) {
            lanDiscoveryEventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            lanDiscoveryEventLoopGroup = null;
        }
        if (lanDiscoveryInitialiser != null) {
            lanDiscoveryInitialiser.getEventLoopGroup().shutdownGracefully(0, 1, TimeUnit.SECONDS);
            lanDiscoveryInitialiser = null;
        }
    }""",
        "NetherNetServer.startLanDiscovery",
    )
    replace_exact(
        nethernet_server,
        """    private void closeInbuiltResources() {
        if (inbuiltChannel != null) inbuiltChannel.close();
        if (signaling != null) signaling.close();
        if (inbuiltEventLoopGroup != null) inbuiltEventLoopGroup.shutdownGracefully();
        if (inbuiltInitialiser != null) inbuiltInitialiser.getEventLoopGroup().shutdownGracefully();
    }""",
        """    private void closeInbuiltResources() {
        if (inbuiltChannel != null) inbuiltChannel.close();
        if (signaling != null) signaling.close();
        if (inbuiltEventLoopGroup != null) inbuiltEventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        if (inbuiltInitialiser != null) inbuiltInitialiser.getEventLoopGroup().shutdownGracefully(0, 1, TimeUnit.SECONDS);
        closeLanDiscoveryResources();
    }""",
        "NetherNetServer.closeInbuiltResources",
    )

    # 5. BedrockPacketViolationWarningTranslator & InvalidPacketHandler: never kick players for benign warnings
    violation_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/BedrockPacketViolationWarningTranslator.java"
    replace_exact(
        violation_translator,
        """        logger.warning("Received packet violation warning (type: %s, severity: %s, packetCauseId: %s) from client (%s): %s! Enable debug mode for the full message.".formatted(packet.getType(),
            packet.getSeverity(), packet.getPacketCauseId(), session.bedrockUsername(), sanitize(message)));
        session.disconnect("An error occurred! Please contact an administrator.");""",
        """        logger.debug("Ignored packet violation warning (type: %s, severity: %s, packetCauseId: %s) from client (%s): %s".formatted(packet.getType(),
            packet.getSeverity(), packet.getPacketCauseId(), session.bedrockUsername(), sanitize(message)));
        // Purpurbedrock: do not kick Bedrock players on non-fatal client packet warnings""",
        "BedrockPacketViolationWarningTranslator",
    )

    invalid_packet = root / "core/src/main/java/org/geysermc/geyser/network/bedrock/InvalidPacketHandler.java"
    replace_exact(
        invalid_packet,
        """        // Kick users that try to send illegal packets
        logger.error("Illegal packet from " + session.bedrockUsername(), cause);
        session.disconnect("Invalid packet received!");
        session.forciblyCloseUpstream();""",
        """        // Purpurbedrock: ignore recoverable malformed/unsupported packets instead of disconnecting
        logger.debug("Ignored unsupported/malformed Bedrock packet from " + session.bedrockUsername() + ": " + rootCause.getMessage());""",
        "InvalidPacketHandler",
    )

    # 6. Dedicated root folder ('bedrock/') for cache, custom_mappings, extensions, and packs
    geyser_impl = root / "core/src/main/java/org/geysermc/geyser/GeyserImpl.java"
    replace_exact(
        geyser_impl,
        """    @Override
    @NonNull
    public Path configDirectory() {
        return bootstrap.getConfigFolder();
    }

    @Override
    @NonNull
    public Path packDirectory() {
        return bootstrap.getConfigFolder().resolve("packs");
    }""",
        """    @Override
    @NonNull
    public Path configDirectory() {
        return Path.of(System.getProperty("purpurbedrock.dataDir", "bedrock")).toAbsolutePath();
    }

    @Override
    @NonNull
    public Path packDirectory() {
        return Path.of(System.getProperty("purpurbedrock.dataDir", "bedrock")).toAbsolutePath().resolve("packs");
    }""",
        "GeyserImpl.configAndPackDirectory",
    )

    ext_loader = root / "core/src/main/java/org/geysermc/geyser/extension/GeyserExtensionLoader.java"
    replace_exact(
        ext_loader,
        """    private final Path extensionsDirectory = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("extensions");""",
        """    private final Path extensionsDirectory = Path.of(System.getProperty("purpurbedrock.dataDir", "bedrock")).toAbsolutePath().resolve("extensions");""",
        "GeyserExtensionLoader.extensionsDirectory",
    )

    mappings_reader = root / "core/src/main/java/org/geysermc/geyser/registry/mappings/MappingsConfigReader.java"
    replace_exact(
        mappings_reader,
        """        Path customMappingsDirectory = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("custom_mappings");""",
        """        Path customMappingsDirectory = Path.of(System.getProperty("purpurbedrock.dataDir", "bedrock")).toAbsolutePath().resolve("custom_mappings");""",
        "MappingsConfigReader.customMappingsDirectory",
    )

    # 7. HeadDatabase / player_skulls cache support:
    #    - Auto-register any pre-cached <skinHash>.png textures in bedrock/cache/player_skulls/
    #      so HeadDatabase skulls render in 3D on Bedrock without requiring manual JSON lists.
    #    - Eagerly ensure bedrock/cache/player_skulls exists and never wipe cached .png textures.
    skull_populator = root / "core/src/main/java/org/geysermc/geyser/registry/populator/CustomSkullRegistryPopulator.java"
    replace_exact(
        skull_populator,
        """        profiles.forEach((profile) -> {
            String skinHash = getSkinHash(profile);
            if (skinHash != null) {
                skinHashes.add(skinHash);
            }
        });""",
        """        profiles.forEach((profile) -> {
            String skinHash = getSkinHash(profile);
            if (skinHash != null) {
                skinHashes.add(skinHash);
            }
        });

        // Purpurbedrock: auto-discover pre-cached HeadDatabase skull PNGs in bedrock/cache/player_skulls/
        Path cachedSkullsDir = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("cache").resolve("player_skulls");
        try {
            Files.createDirectories(cachedSkullsDir);
            java.util.HashSet<String> seenHashes = new java.util.HashSet<>(skinHashes);
            try (java.util.stream.Stream<Path> cachedFiles = Files.list(cachedSkullsDir)) {
                cachedFiles.forEach(file -> {
                    String fileName = file.getFileName().toString();
                    if (Files.isRegularFile(file) && fileName.endsWith(".png") && fileName.length() > 4) {
                        String candidateHash = fileName.substring(0, fileName.length() - 4);
                        if (SKULL_HASH_PATTERN.matcher(candidateHash).matches() && seenHashes.add(candidateHash)) {
                            skinHashes.add(candidateHash);
                        }
                    }
                });
            }
        } catch (IOException ignored) {
        }""",
        "CustomSkullRegistryPopulator.autoDiscoverPlayerSkulls",
    )

    skull_pack_mgr = root / "core/src/main/java/org/geysermc/geyser/pack/SkullResourcePackManager.java"
    replace_exact(
        skull_pack_mgr,
        """        try {
            Files.createDirectories(cachePath);
        } catch (IOException e) {""",
        """        try {
            Files.createDirectories(cachePath);
            Files.createDirectories(SKULL_SKIN_CACHE_PATH);
        } catch (IOException e) {""",
        "SkullResourcePackManager.ensurePlayerSkullsDir",
    )
    replace_exact(
        skull_pack_mgr,
        """    public static void cleanSkullSkinCache() {
        // No need to clean up if skin cache does not exist
        if (!Files.exists(SKULL_SKIN_CACHE_PATH)) {
            return;
        }""",
        """    public static void cleanSkullSkinCache() {
        // Purpurbedrock: preserve pre-cached HeadDatabase player_skulls/*.png textures across restarts
        if (Boolean.parseBoolean(System.getProperty("purpurbedrock.preserveSkullCache", "true"))) {
            return;
        }
        // No need to clean up if skin cache does not exist
        if (!Files.exists(SKULL_SKIN_CACHE_PATH)) {
            return;
        }""",
        "SkullResourcePackManager.preserveHeadDatabaseCache",
    )


    # 8. DialogHolder: remove the 'gui.waitingForResponse.title' waiting popup window
    dialog_holder = root / "core/src/main/java/org/geysermc/geyser/session/dialog/DialogHolder.java"
    replace_exact(
        dialog_holder,
        """            case WAIT_FOR_RESPONSE -> {
                // If no new dialog was opened, open a form telling the user we're waiting on a response from the server
                // This dialog is replaced with a similar form with a "back" button after 5 seconds, matching Java behaviour
                responseWaitTime = System.currentTimeMillis();
                sendBackButton = false;
                waitForResponse();
            }""",
        """            case WAIT_FOR_RESPONSE -> {
                // Purpurbedrock: close the dialog cleanly without showing the intrusive waiting popup;
                // if the server subsequently sends a follow-up dialog or chest GUI, it opens directly.
                manager.close();
            }""",
        "DialogHolder.suppressWaitForResponseAfterAction",
    )
    replace_exact(
        dialog_holder,
        """    private void waitForResponse() {
        String content;
        if (sendBackButton) {
            content = GeyserLocale.getPlayerLocaleString("geyser.dialogs.waiting_for_a_while", session.locale());
        } else {
            content = GeyserLocale.getPlayerLocaleString("geyser.dialogs.waiting_for_response", session.locale());
        }

        session.sendDialogForm(SimpleForm.builder()
            .translator(MinecraftLocale::getLocaleString, session.locale())
            .title("gui.waitingForResponse.title")
            .content(content)
            .optionalButton("gui.back", sendBackButton)
            .closedOrInvalidResultHandler(() -> {
                if (stillValid()) { // If still waiting on a new dialog
                    waitForResponse();
                }
            })
            .validResultHandler(response -> manager.close()) // Back button was pressed, meaning no new dialog was sent
            .build());
    }""",
        """    private void waitForResponse() {
        manager.close();
    }""",
        "DialogHolder.removeWaitForResponseForm",
    )

    # 9. Full Bedrock Multi-Version (1.21.0 v685 -> 26.52 v2193)
    extract_historical_git_assets(root)

    mv_helper_path = root / "core/src/main/java/org/geysermc/geyser/registry/populator/MultiVersionPaletteHelper.java"
    mv_helper_path.write_text(MULTI_VERSION_HELPER_JAVA, encoding="utf-8")
    print(f"Wrote MultiVersionPaletteHelper.java -> {mv_helper_path.name}")

    # 9a. GameProtocol: register all 23 Bedrock codecs (1.21.0 through 26.52)
    game_protocol = root / "core/src/main/java/org/geysermc/geyser/network/bedrock/GameProtocol.java"
    replace_exact(
        game_protocol,
        """import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;""",
        """import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v685.Bedrock_v685;
import org.cloudburstmc.protocol.bedrock.codec.v686.Bedrock_v686;
import org.cloudburstmc.protocol.bedrock.codec.v712.Bedrock_v712;
import org.cloudburstmc.protocol.bedrock.codec.v729.Bedrock_v729;
import org.cloudburstmc.protocol.bedrock.codec.v748.Bedrock_v748;
import org.cloudburstmc.protocol.bedrock.codec.v766.Bedrock_v766;
import org.cloudburstmc.protocol.bedrock.codec.v776.Bedrock_v776;
import org.cloudburstmc.protocol.bedrock.codec.v786.Bedrock_v786;
import org.cloudburstmc.protocol.bedrock.codec.v800.Bedrock_v800;
import org.cloudburstmc.protocol.bedrock.codec.v818.Bedrock_v818;
import org.cloudburstmc.protocol.bedrock.codec.v819.Bedrock_v819;
import org.cloudburstmc.protocol.bedrock.codec.v827.Bedrock_v827;
import org.cloudburstmc.protocol.bedrock.codec.v844.Bedrock_v844;
import org.cloudburstmc.protocol.bedrock.codec.v859.Bedrock_v859;
import org.cloudburstmc.protocol.bedrock.codec.v860.Bedrock_v860;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;""",
        "GameProtocol.imports",
    )
    replace_exact(
        game_protocol,
        """        // Strict ordering
        register(Bedrock_v1001.CODEC, "26.30", "26.31", "26.32", "26.33", "26.34");
        register(Bedrock_v2168_hotfix4.CODEC, "26.40", "26.41", "26.42", "26.43", "26.44");
        register(Bedrock_v2169.CODEC, "26.45");
        register(Bedrock_v2193.CODEC, "26.50", "26.51", "26.52");""",
        """        // Strict ascending protocol ordering: Bedrock 1.21.0 (v685) -> 26.52 (v2193)
        register(Bedrock_v685.CODEC, "1.21.0", "1.21.1");
        register(Bedrock_v686.CODEC, "1.21.2", "1.21.3");
        register(Bedrock_v712.CODEC, "1.21.20", "1.21.21", "1.21.22", "1.21.23");
        register(Bedrock_v729.CODEC, "1.21.30", "1.21.31");
        register(Bedrock_v748.CODEC, "1.21.40", "1.21.41", "1.21.43", "1.21.44");
        register(Bedrock_v766.CODEC, "1.21.50", "1.21.51");
        register(Bedrock_v776.CODEC, "1.21.60", "1.21.61", "1.21.62");
        register(Bedrock_v786.CODEC, "1.21.70", "1.21.71", "1.21.72", "1.21.73");
        register(Bedrock_v800.CODEC, "1.21.80", "1.21.81", "1.21.82", "1.21.83", "1.21.84");
        register(Bedrock_v818.CODEC, "1.21.90", "1.21.91", "1.21.92");
        register(Bedrock_v819.CODEC, "1.21.93", "1.21.94");
        register(Bedrock_v827.CODEC, "1.21.100", "1.21.101");
        register(Bedrock_v844.CODEC, "1.21.110", "1.21.111", "1.21.112", "1.21.113", "1.21.114");
        register(Bedrock_v859.CODEC, "1.21.120", "1.21.121", "1.21.122", "1.21.123");
        register(Bedrock_v860.CODEC, "1.21.124");
        register(Bedrock_v898.CODEC, "1.21.130", "1.21.131", "1.21.132");
        register(Bedrock_v924.CODEC, "26.0", "26.1", "26.2", "26.3");
        register(Bedrock_v944.CODEC, "26.10", "26.11", "26.12");
        register(Bedrock_v975.CODEC, "26.20", "26.21", "26.22", "26.23");
        register(Bedrock_v1001.CODEC, "26.30", "26.31", "26.32", "26.33", "26.34");
        register(Bedrock_v2168_hotfix4.CODEC, "26.40", "26.41", "26.42", "26.43", "26.44");
        register(Bedrock_v2169.CODEC, "26.45");
        register(Bedrock_v2193.CODEC, "26.50", "26.51", "26.52");""",
        "GameProtocol.registerAllVersions",
    )

    # 9b. CodecProcessor: exact packet serializer ladder across v685..v2193
    codec_processor = root / "core/src/main/java/org/geysermc/geyser/network/bedrock/CodecProcessor.java"
    replace_exact(
        codec_processor,
        """import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.MobEquipmentSerializer_v291;
import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.MoveEntityAbsoluteSerializer_v291;
import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.PlayerHotbarSerializer_v291;
import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.SetEntityLinkSerializer_v291;
import org.cloudburstmc.protocol.bedrock.codec.v390.serializer.PlayerSkinSerializer_v390;
import org.cloudburstmc.protocol.bedrock.codec.v419.serializer.MovePlayerSerializer_v419;
import org.cloudburstmc.protocol.bedrock.codec.v557.serializer.SetEntityDataSerializer_v557;
import org.cloudburstmc.protocol.bedrock.codec.v662.serializer.SetEntityMotionSerializer_v662;
import org.cloudburstmc.protocol.bedrock.codec.v712.serializer.MobArmorEquipmentSerializer_v712;
import org.cloudburstmc.protocol.bedrock.codec.v748.serializer.InventoryContentSerializer_v748;
import org.cloudburstmc.protocol.bedrock.codec.v748.serializer.InventorySlotSerializer_v748;""",
        """import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.MobArmorEquipmentSerializer_v291;
import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.MobEquipmentSerializer_v291;
import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.MoveEntityAbsoluteSerializer_v291;
import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.PlayerHotbarSerializer_v291;
import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.SetEntityLinkSerializer_v291;
import org.cloudburstmc.protocol.bedrock.codec.v390.serializer.PlayerSkinSerializer_v390;
import org.cloudburstmc.protocol.bedrock.codec.v407.serializer.InventoryContentSerializer_v407;
import org.cloudburstmc.protocol.bedrock.codec.v407.serializer.InventorySlotSerializer_v407;
import org.cloudburstmc.protocol.bedrock.codec.v419.serializer.MovePlayerSerializer_v419;
import org.cloudburstmc.protocol.bedrock.codec.v486.serializer.BossEventSerializer_v486;
import org.cloudburstmc.protocol.bedrock.codec.v557.serializer.SetEntityDataSerializer_v557;
import org.cloudburstmc.protocol.bedrock.codec.v662.serializer.SetEntityMotionSerializer_v662;
import org.cloudburstmc.protocol.bedrock.codec.v712.serializer.InventoryContentSerializer_v712;
import org.cloudburstmc.protocol.bedrock.codec.v712.serializer.InventorySlotSerializer_v712;
import org.cloudburstmc.protocol.bedrock.codec.v712.serializer.MobArmorEquipmentSerializer_v712;
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventoryContentSerializer_v729;
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventorySlotSerializer_v729;
import org.cloudburstmc.protocol.bedrock.codec.v748.serializer.InventoryContentSerializer_v748;
import org.cloudburstmc.protocol.bedrock.codec.v748.serializer.InventorySlotSerializer_v748;
import org.cloudburstmc.protocol.bedrock.codec.v776.serializer.BossEventSerializer_v776;
import org.cloudburstmc.protocol.bedrock.packet.PlayerInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RiderJumpPacket;""",
        "CodecProcessor.imports",
    )
    replace_exact(
        codec_processor,
        """    private static final BedrockPacketSerializer<InventoryContentPacket> INVENTORY_CONTENT_SERIALIZER_V748 = new InventoryContentSerializer_v748() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, InventoryContentPacket packet) {
            throw new IllegalPacketException("Client cannot send InventoryContentPacket in server-auth inventory environment!");
        }
    };""",
        """    private static final BedrockPacketSerializer<InventoryContentPacket> INVENTORY_CONTENT_SERIALIZER_V407 = new InventoryContentSerializer_v407() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, InventoryContentPacket packet) {
            throw new IllegalPacketException("Client cannot send InventoryContentPacket in server-auth inventory environment!");
        }
    };

    private static final BedrockPacketSerializer<InventoryContentPacket> INVENTORY_CONTENT_SERIALIZER_V712 = new InventoryContentSerializer_v712() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, InventoryContentPacket packet) {
            throw new IllegalPacketException("Client cannot send InventoryContentPacket in server-auth inventory environment!");
        }
    };

    private static final BedrockPacketSerializer<InventoryContentPacket> INVENTORY_CONTENT_SERIALIZER_V729 = new InventoryContentSerializer_v729() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, InventoryContentPacket packet) {
            throw new IllegalPacketException("Client cannot send InventoryContentPacket in server-auth inventory environment!");
        }
    };

    private static final BedrockPacketSerializer<InventoryContentPacket> INVENTORY_CONTENT_SERIALIZER_V748 = new InventoryContentSerializer_v748() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, InventoryContentPacket packet) {
            throw new IllegalPacketException("Client cannot send InventoryContentPacket in server-auth inventory environment!");
        }
    };

    private static final BedrockPacketSerializer<InventorySlotPacket> INVENTORY_SLOT_SERIALIZER_V407 = new InventorySlotSerializer_v407() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, InventorySlotPacket packet) {
            throw new IllegalPacketException("Client cannot send InventorySlotPacket in server-auth inventory environment!");
        }
    };

    private static final BedrockPacketSerializer<InventorySlotPacket> INVENTORY_SLOT_SERIALIZER_V712 = new InventorySlotSerializer_v712() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, InventorySlotPacket packet) {
            throw new IllegalPacketException("Client cannot send InventorySlotPacket in server-auth inventory environment!");
        }
    };

    private static final BedrockPacketSerializer<InventorySlotPacket> INVENTORY_SLOT_SERIALIZER_V729 = new InventorySlotSerializer_v729() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, InventorySlotPacket packet) {
            throw new IllegalPacketException("Client cannot send InventorySlotPacket in server-auth inventory environment!");
        }
    };

    private static final BedrockPacketSerializer<BossEventPacket> BOSS_EVENT_SERIALIZER_V486 = new BossEventSerializer_v486() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, BossEventPacket packet) {
        }
    };

    private static final BedrockPacketSerializer<BossEventPacket> BOSS_EVENT_SERIALIZER_V776 = new BossEventSerializer_v776() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, BossEventPacket packet) {
        }
    };

    private static final BedrockPacketSerializer<MobArmorEquipmentPacket> MOB_ARMOR_EQUIPMENT_SERIALIZER_V291 = new MobArmorEquipmentSerializer_v291() {
        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, MobArmorEquipmentPacket packet) {
        }
    };""",
        "CodecProcessor.serializers",
    )
    replace_exact(
        codec_processor,
        """        if (codec.getProtocolVersion() < 975) { // 26.20
            codecBuilder.updateSerializer(InventorySlotPacket.class, INVENTORY_SLOT_SERIALIZER_V748);
            codecBuilder.updateSerializer(MoveEntityAbsolutePacket.class, MOVE_ENTITY_SERIALIZER_V291);
            // Valid serverbound packets where reading of some fields can be skipped
            codecBuilder.updateSerializer(MobEquipmentPacket.class, MOB_EQUIPMENT_SERIALIZER_V291);
        } else {
            codecBuilder.updateSerializer(InventorySlotPacket.class, INVENTORY_SLOT_SERIALIZER_V975);
            codecBuilder.updateSerializer(MoveEntityAbsolutePacket.class, MOVE_ENTITY_SERIALIZER_V975);
            codecBuilder.updateSerializer(MobEquipmentPacket.class, MOB_EQUIPMENT_SERIALIZER_V975);
        }

        if (codec.getProtocolVersion() < 1001) { // 26.30
            codecBuilder.updateSerializer(MobArmorEquipmentPacket.class, MOB_ARMOR_EQUIPMENT_SERIALIZER_V712);
            codecBuilder.updateSerializer(InventoryContentPacket.class, INVENTORY_CONTENT_SERIALIZER_V748);
        } else {
            codecBuilder.updateSerializer(MobArmorEquipmentPacket.class, MOB_ARMOR_EQUIPMENT_SERIALIZER_V1001);
            codecBuilder.updateSerializer(InventoryContentPacket.class, INVENTORY_CONTENT_SERIALIZER_V1001);
        }

        if (codec.getProtocolVersion() < 2168) { // 26.40
            // Illegal when serverbound due to Geyser specific setup
            codecBuilder.updateSerializer(MovePlayerPacket.class, MOVE_PLAYER_SERIALIZER_V419);
            codecBuilder.updateSerializer(PlayerSkinPacket.class, PLAYER_SKIN_SERIALIZER_V390);
        } else {
            codecBuilder.updateSerializer(MovePlayerPacket.class, MOVE_PLAYER_SERIALIZER_V2168);
            codecBuilder.updateSerializer(PlayerSkinPacket.class, PLAYER_SKIN_SERIALIZER_V2168);
        }

        if (codec.getProtocolVersion() < 2193) { // 26.50
            codecBuilder.updateSerializer(BossEventPacket.class, BOSS_EVENT_SERIALIZER_V1001);
        } else {
            codecBuilder.updateSerializer(BossEventPacket.class, BOSS_EVENT_SERIALIZER_V2193);
        }""",
        """        int proto = codec.getProtocolVersion();

        if (proto < 800) { // Removed in 1.21.80
            codecBuilder.updateSerializer(RiderJumpPacket.class, IGNORED_SERIALIZER);
            codecBuilder.updateSerializer(PlayerInputPacket.class, IGNORED_SERIALIZER);
        }

        if (proto >= 975) { // 26.20+
            codecBuilder.updateSerializer(InventorySlotPacket.class, INVENTORY_SLOT_SERIALIZER_V975);
            codecBuilder.updateSerializer(MoveEntityAbsolutePacket.class, MOVE_ENTITY_SERIALIZER_V975);
            codecBuilder.updateSerializer(MobEquipmentPacket.class, MOB_EQUIPMENT_SERIALIZER_V975);
        } else {
            codecBuilder.updateSerializer(MoveEntityAbsolutePacket.class, MOVE_ENTITY_SERIALIZER_V291);
            codecBuilder.updateSerializer(MobEquipmentPacket.class, MOB_EQUIPMENT_SERIALIZER_V291);
            if (proto >= 748) {
                codecBuilder.updateSerializer(InventorySlotPacket.class, INVENTORY_SLOT_SERIALIZER_V748);
            } else if (proto >= 729) {
                codecBuilder.updateSerializer(InventorySlotPacket.class, INVENTORY_SLOT_SERIALIZER_V729);
            } else if (proto >= 712) {
                codecBuilder.updateSerializer(InventorySlotPacket.class, INVENTORY_SLOT_SERIALIZER_V712);
            } else {
                codecBuilder.updateSerializer(InventorySlotPacket.class, INVENTORY_SLOT_SERIALIZER_V407);
            }
        }

        if (proto >= 1001) { // 26.30+
            codecBuilder.updateSerializer(MobArmorEquipmentPacket.class, MOB_ARMOR_EQUIPMENT_SERIALIZER_V1001);
            codecBuilder.updateSerializer(InventoryContentPacket.class, INVENTORY_CONTENT_SERIALIZER_V1001);
        } else {
            codecBuilder.updateSerializer(
                MobArmorEquipmentPacket.class,
                proto >= 712 ? MOB_ARMOR_EQUIPMENT_SERIALIZER_V712 : MOB_ARMOR_EQUIPMENT_SERIALIZER_V291
            );
            if (proto >= 748) {
                codecBuilder.updateSerializer(InventoryContentPacket.class, INVENTORY_CONTENT_SERIALIZER_V748);
            } else if (proto >= 729) {
                codecBuilder.updateSerializer(InventoryContentPacket.class, INVENTORY_CONTENT_SERIALIZER_V729);
            } else if (proto >= 712) {
                codecBuilder.updateSerializer(InventoryContentPacket.class, INVENTORY_CONTENT_SERIALIZER_V712);
            } else {
                codecBuilder.updateSerializer(InventoryContentPacket.class, INVENTORY_CONTENT_SERIALIZER_V407);
            }
        }

        if (proto < 2168) { // < 26.40
            codecBuilder.updateSerializer(MovePlayerPacket.class, MOVE_PLAYER_SERIALIZER_V419);
            codecBuilder.updateSerializer(PlayerSkinPacket.class, PLAYER_SKIN_SERIALIZER_V390);
        } else {
            codecBuilder.updateSerializer(MovePlayerPacket.class, MOVE_PLAYER_SERIALIZER_V2168);
            codecBuilder.updateSerializer(PlayerSkinPacket.class, PLAYER_SKIN_SERIALIZER_V2168);
        }

        if (proto >= 2193) { // 26.50+
            codecBuilder.updateSerializer(BossEventPacket.class, BOSS_EVENT_SERIALIZER_V2193);
        } else if (proto >= 1001) { // 26.30 - 26.45
            codecBuilder.updateSerializer(BossEventPacket.class, BOSS_EVENT_SERIALIZER_V1001);
        } else if (proto >= 776) { // 1.21.60 - 26.23
            codecBuilder.updateSerializer(BossEventPacket.class, BOSS_EVENT_SERIALIZER_V776);
        } else { // 1.21.0 - 1.21.51
            codecBuilder.updateSerializer(BossEventPacket.class, BOSS_EVENT_SERIALIZER_V486);
        }""",
        "CodecProcessor.processCodecMultiVersion",
    )

    # 9c. BlockRegistryPopulator: load all 17 block palettes (1.21.0 -> 26.50) with guaranteed state resolution
    block_populator = root / "core/src/main/java/org/geysermc/geyser/registry/populator/BlockRegistryPopulator.java"
    replace_exact(
        block_populator,
        """        var blockMappers = ImmutableMap.<ObjectIntPair<String>, Remapper>builder()
                .put(ObjectIntPair.of("26_30", Bedrock_v1001.CODEC.getProtocolVersion()), ICanHasStates::convertBlock)
                .put(ObjectIntPair.of("26_40", Bedrock_v2168.CODEC.getProtocolVersion()), ICanHasStates::convertBlock)
                .put(ObjectIntPair.of("26_50", Bedrock_v2193.CODEC.getProtocolVersion()), tag -> tag)
            .build();""",
        """        var blockMappers = ImmutableMap.<ObjectIntPair<String>, Remapper>builder()
                .put(ObjectIntPair.of("1_21_0", 685), tag -> tag)
                .put(ObjectIntPair.of("1_21_20", 712), tag -> tag)
                .put(ObjectIntPair.of("1_21_30", 729), tag -> tag)
                .put(ObjectIntPair.of("1_21_40", 748), tag -> tag)
                .put(ObjectIntPair.of("1_21_50", 766), tag -> tag)
                .put(ObjectIntPair.of("1_21_60", 776), tag -> tag)
                .put(ObjectIntPair.of("1_21_70", 786), tag -> tag)
                .put(ObjectIntPair.of("1_21_80", 800), tag -> tag)
                .put(ObjectIntPair.of("1_21_90", 818), tag -> tag)
                .put(ObjectIntPair.of("1_21_100", 827), tag -> tag)
                .put(ObjectIntPair.of("1_21_110", 844), tag -> tag)
                .put(ObjectIntPair.of("1_21_130", 898), tag -> tag)
                .put(ObjectIntPair.of("1_26_10", 944), tag -> tag)
                .put(ObjectIntPair.of("1_26_20", 975), tag -> tag)
                .put(ObjectIntPair.of("26_30", Bedrock_v1001.CODEC.getProtocolVersion()), tag -> tag)
                .put(ObjectIntPair.of("26_40", Bedrock_v2168.CODEC.getProtocolVersion()), tag -> tag)
                .put(ObjectIntPair.of("26_50", Bedrock_v2193.CODEC.getProtocolVersion()), tag -> tag)
            .build();""",
        "BlockRegistryPopulator.blockMappers",
    )
    replace_exact(
        block_populator,
        """                NbtMap originalBedrockTag = buildBedrockState(blockState, entry);
                NbtMap bedrockTag = stateMapper.remap(originalBedrockTag);

                GeyserBedrockBlock vanillaBedrockDefinition = blockStateOrderedMap.get(bedrockTag);""",
        """                NbtMap originalBedrockTag = buildBedrockState(blockState, entry);
                NbtMap bedrockTag = MultiVersionPaletteHelper.remapBlockForVersion(protocolVersion, blockState, stateMapper.remap(originalBedrockTag));

                GeyserBedrockBlock vanillaBedrockDefinition = MultiVersionPaletteHelper.resolveBlockDefinition(bedrockTag, blockStateOrderedMap, blocksByName);""",
        "BlockRegistryPopulator.resolveMultiVersionBlock",
    )
    replace_exact(
        block_populator,
        """            Object2ObjectMap<NbtMap, GeyserBedrockBlock> blockStateOrderedMap = new Object2ObjectOpenHashMap<>(blockStates.size());
            GeyserBedrockBlock[] bedrockRuntimeMap = new GeyserBedrockBlock[blockStates.size()];
            for (int i = 0; i < blockStates.size(); i++) {
                NbtMap tag = blockStates.get(i);
                GeyserBedrockBlock block = new GeyserBedrockBlock(i, tag);
                if (blockStateOrderedMap.put(tag, block) != null) {
                    throw new AssertionError("Duplicate block states in Bedrock palette: " + tag);
                }
                bedrockRuntimeMap[i] = block;
            }""",
        """            Object2ObjectMap<NbtMap, GeyserBedrockBlock> blockStateOrderedMap = new Object2ObjectOpenHashMap<>(blockStates.size());
            Map<String, List<GeyserBedrockBlock>> blocksByName = new Object2ObjectOpenHashMap<>();
            GeyserBedrockBlock[] bedrockRuntimeMap = new GeyserBedrockBlock[blockStates.size()];
            for (int i = 0; i < blockStates.size(); i++) {
                NbtMap tag = blockStates.get(i);
                GeyserBedrockBlock block = new GeyserBedrockBlock(i, tag);
                if (blockStateOrderedMap.put(tag, block) != null) {
                    throw new AssertionError("Duplicate block states in Bedrock palette: " + tag);
                }
                bedrockRuntimeMap[i] = block;
                blocksByName.computeIfAbsent(tag.getString("name"), k -> new ArrayList<>()).add(block);
            }""",
        "BlockRegistryPopulator.indexBlocksByName",
    )

    # 9d. CreativeItemRegistryPopulator: support < 1.21.60 creative_items JSONs without 'groups'
    creative_populator = root / "core/src/main/java/org/geysermc/geyser/registry/populator/CreativeItemRegistryPopulator.java"
    replace_exact(
        creative_populator,
        """        JsonArray creativeItemEntries;
        try (InputStream stream = bootstrap.getResourceOrThrow(String.format("bedrock/creative_items.%s.json", palette.creativeVersion()))) {
            creativeItemEntries = JsonUtils.fromJson(stream).getAsJsonArray("groups");
        } catch (Exception e) {
            throw new AssertionError("Unable to load creative item groups", e);
        }""",
        """        JsonArray creativeItemEntries;
        try (InputStream stream = bootstrap.getResourceOrThrow(String.format("bedrock/creative_items.%s.json", palette.creativeVersion()))) {
            JsonObject rootJson = JsonUtils.fromJson(stream);
            if (!rootJson.has("groups") || !rootJson.get("groups").isJsonArray()) {
                return;
            }
            creativeItemEntries = rootJson.getAsJsonArray("groups");
        } catch (Exception e) {
            throw new AssertionError("Unable to load creative item groups", e);
        }""",
        "CreativeItemRegistryPopulator.optionalGroups",
    )
    replace_exact(
        creative_populator,
        """                itemData = creativeItemData.stream()
                    .map(CreativeItemData::getItem)
                    .filter(item -> item.getDefinition().getIdentifier().equals(identifier))
                    .findFirst()
                    .orElseThrow();""",
        """                itemData = creativeItemData.stream()
                    .map(CreativeItemData::getItem)
                    .filter(item -> item.getDefinition().getIdentifier().equals(identifier))
                    .findFirst()
                    .orElse(ItemData.AIR);""",
        "CreativeItemRegistryPopulator.safeGroupIcon",
    )

    # 9e. ItemRegistryPopulator: load all 20 item palettes (1.21.0 v685 -> 26.50 v2193)
    item_populator = root / "core/src/main/java/org/geysermc/geyser/registry/populator/ItemRegistryPopulator.java"
    replace_exact(
        item_populator,
        """        List<PaletteVersion> paletteVersions = new ArrayList<>(3);
        paletteVersions.add(new PaletteVersion("26_30", Bedrock_v1001.CODEC.getProtocolVersion()));
        paletteVersions.add(new PaletteVersion("26_40", Bedrock_v2168.CODEC.getProtocolVersion()));
        paletteVersions.add(new PaletteVersion("26_50", Bedrock_v2193.CODEC.getProtocolVersion()));""",
        """        List<PaletteVersion> paletteVersions = new ArrayList<>(20);
        paletteVersions.add(new PaletteVersion("1_21_0", 685, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(685), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(685, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_20", 712, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(712), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(712, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_30", 729, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(729), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(729, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_40", 748, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(748), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(748, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_50", 766, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(766), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(766, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_60", 776, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(776), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(776, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_70", 786, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(786), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(786, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_80", 800, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(800), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(800, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_90", 818, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(818), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(818, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_93", 819, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(819), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(819, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_100", 827, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(827), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(827, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_110", 844, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(844), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(844, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_120", 859, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(859), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(859, it, mp)));
        paletteVersions.add(new PaletteVersion("1_21_130", 898, MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(898), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(898, it, mp)));
        paletteVersions.add(new PaletteVersion("1_26_0", Bedrock_v924.CODEC.getProtocolVersion(), MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(924), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(924, it, mp)));
        paletteVersions.add(new PaletteVersion("1_26_10", Bedrock_v944.CODEC.getProtocolVersion(), MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(944), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(944, it, mp)));
        paletteVersions.add(new PaletteVersion("1_26_20", Bedrock_v975.CODEC.getProtocolVersion(), "1_26_10", MultiVersionPaletteHelper.buildJavaOnlyItemFallbacks(975), (it, mp) -> MultiVersionPaletteHelper.remapItemForVersion(975, it, mp)));
        paletteVersions.add(new PaletteVersion("26_30", Bedrock_v1001.CODEC.getProtocolVersion()));
        paletteVersions.add(new PaletteVersion("26_40", Bedrock_v2168.CODEC.getProtocolVersion()));
        paletteVersions.add(new PaletteVersion("26_50", Bedrock_v2193.CODEC.getProtocolVersion()));""",
        "ItemRegistryPopulator.paletteVersions",
    )
    replace_exact(
        item_populator,
        """        public PaletteVersion(String version, int protocolVersion, Map<Item, Item> javaOnlyItems, String creativeVersion) {
            this(version, protocolVersion, creativeVersion, javaOnlyItems, (item, mapping) -> mapping);
        }""",
        """        public PaletteVersion(String version, int protocolVersion, Map<Item, Item> javaOnlyItems, String creativeVersion) {
            this(version, protocolVersion, creativeVersion, javaOnlyItems, (item, mapping) -> mapping);
        }

        public PaletteVersion(String version, int protocolVersion, Map<Item, Item> javaOnlyItems, Remapper remapper) {
            this(version, protocolVersion, null, javaOnlyItems, remapper);
        }""",
        "ItemRegistryPopulator.PaletteVersionCtor",
    )
    replace_exact(
        item_populator,
        """            NbtMap vanillaComponents;
            try (InputStream stream = bootstrap.getResourceOrThrow("bedrock/item_components.%s.nbt".formatted(palette.version()))) {
                vanillaComponents = (NbtMap) NbtUtils.createGZIPReader(stream, true, true).readTag();
            } catch (Exception e) {
                throw new AssertionError("Unable to load Bedrock item components", e);
            }""",
        """            NbtMap vanillaComponents;
            String componentsVer = palette.protocolVersion() < 818 ? "1_21_80" : palette.version();
            try (InputStream stream = bootstrap.getResourceOrThrow("bedrock/item_components.%s.nbt".formatted(componentsVer))) {
                vanillaComponents = (NbtMap) NbtUtils.createGZIPReader(stream, true, true).readTag();
            } catch (Exception e) {
                throw new AssertionError("Unable to load Bedrock item components", e);
            }""",
        "ItemRegistryPopulator.vanillaComponentsVersion",
    )
    replace_exact(
        item_populator,
        """                // Some items, e.g. food, are not component based but still have components
                NbtMap components = vanillaComponents.getCompound(entry.getName());
                if (components == null && entry.isComponentBased()) {
                    // FIXME needs a proper item components file update
                    if (!entry.getName().contains("lava_chicken")) {
                        throw new RuntimeException("Could not find vanilla components for vanilla component based item! " + entry.getName());
                    } else {
                        components = NbtMap.EMPTY;
                    }
                }""",
        """                // Some items, e.g. food, are not component based but still have components
                NbtMap components = vanillaComponents.getCompound(entry.getName());
                if (components == null && entry.isComponentBased()) {
                    components = NbtMap.EMPTY;
                }""",
        "ItemRegistryPopulator.safeMissingComponents",
    )
    replace_exact(
        item_populator,
        """                GeyserMappingItem mappingItem;
                Item replacementItem = palette.javaOnlyItems().get(javaItem);
                if (replacementItem != null) {
                    mappingItem = items.get(replacementItem.javaIdentifier()); // java only item, a java id fallback has been provided
                } else {
                    // check if any mapping changes need to be made on this version
                    mappingItem = palette.remapper().remap(javaItem, entry.getValue());
                }

                String bedrockIdentifier = mappingItem.getBedrockIdentifier();
                ItemDefinition definition = definitions.get(bedrockIdentifier);
                if (definition == null) {
                    throw new RuntimeException("Missing Bedrock ItemDefinition in version " + palette.version() + " for mapping: " + mappingItem);
                }""",
        """                GeyserMappingItem mappingItem;
                Item replacementItem = palette.javaOnlyItems().get(javaItem);
                if (replacementItem != null) {
                    mappingItem = palette.remapper().remap(replacementItem, items.get(replacementItem.javaIdentifier()));
                } else {
                    mappingItem = palette.remapper().remap(javaItem, entry.getValue());
                }

                String bedrockIdentifier = mappingItem.getBedrockIdentifier();
                ItemDefinition definition = definitions.get(bedrockIdentifier);
                if (definition == null) {
                    javaOnlyItems.add(javaItem);
                    if (entry.getValue().getFirstBlockRuntimeId() != null) {
                        replacementItem = Items.STONE;
                        mappingItem = palette.remapper().remap(Items.STONE, items.get("minecraft:stone"));
                    } else {
                        mappingItem = palette.remapper().remap(Items.PAPER, items.get("minecraft:paper"));
                    }
                    bedrockIdentifier = mappingItem.getBedrockIdentifier();
                    definition = definitions.get(bedrockIdentifier);
                }""",
        "ItemRegistryPopulator.safeDefinitionFallback",
    )
    replace_exact(
        item_populator,
        """                Integer firstBlockRuntimeId = entry.getValue().getFirstBlockRuntimeId();""",
        """                Integer firstBlockRuntimeId = replacementItem != null
                    ? items.get(replacementItem.javaIdentifier()).getFirstBlockRuntimeId()
                    : entry.getValue().getFirstBlockRuntimeId();
                Integer effectiveLastBlockRuntimeId = replacementItem != null
                    ? items.get(replacementItem.javaIdentifier()).getLastBlockRuntimeId()
                    : entry.getValue().getLastBlockRuntimeId();""",
        "ItemRegistryPopulator.replacementBlockRuntimeIds",
    )
    replace_exact(
        item_populator,
        """                            int lastBlockRuntimeId = entry.getValue().getLastBlockRuntimeId() == null ? firstBlockRuntimeId : entry.getValue().getLastBlockRuntimeId();""",
        """                            int lastBlockRuntimeId = effectiveLastBlockRuntimeId == null ? firstBlockRuntimeId : effectiveLastBlockRuntimeId;""",
        "ItemRegistryPopulator.effectiveLastBlockRuntimeId",
    )
    replace_exact(
        item_populator,
        """                                if (bedrockBlock == null) {
                                    throw new RuntimeException("Could not find a block match for " + entry.getKey());
                                }""",
        """                                if (bedrockBlock == null) {
                                    bedrockBlock = blockMappings.getVanillaBedrockBlock(firstBlockRuntimeId);
                                }""",
        "ItemRegistryPopulator.safeBlockMatchFallback",
    )
    replace_exact(
        item_populator,
        """                                    if (itemData.getItem().getDamage() != 0) {
                                        break;
                                    }

                                    NbtMap states = ((GeyserBedrockBlock) itemData.getItem().getBlockDefinition()).getState().getCompound("states");""",
        """                                    if (itemData.getItem().getDamage() != 0 || itemData.getItem().getBlockDefinition() == null) {
                                        break;
                                    }

                                    NbtMap states = ((GeyserBedrockBlock) itemData.getItem().getBlockDefinition()).getState().getCompound("states");""",
        "ItemRegistryPopulator.nullSafeCreativeBlockDef",
    )
    replace_exact(
        item_populator,
        """                    try {
                        definition = registerSkullItem(identifier, items.get("minecraft:player_head"), customProtocolId, palette.protocolVersion, firstMappingsPass);""",
        """                    try {
                        GeyserMappingItem skullBaseMapping = palette.remapper().remap(Items.PLAYER_HEAD, items.get("minecraft:player_head"));
                        definition = registerSkullItem(identifier, skullBaseMapping, customProtocolId, palette.protocolVersion, firstMappingsPass);""",
        "ItemRegistryPopulator.multiVersionSkullBaseMapping",
    )

    # 9f. TagRegistryPopulator: register tags across 685..2193
    tag_populator = root / "core/src/main/java/org/geysermc/geyser/registry/populator/TagRegistryPopulator.java"
    replace_exact(
        tag_populator,
        """        List<ObjectIntPair<String>> paletteVersions = List.of(
            ObjectIntPair.of("26_30", Bedrock_v1001.CODEC.getProtocolVersion()),
            ObjectIntPair.of("26_30", Bedrock_v2168.CODEC.getProtocolVersion()),
            ObjectIntPair.of("26_30", Bedrock_v2193.CODEC.getProtocolVersion())
        );""",
        """        List<ObjectIntPair<String>> paletteVersions = List.of(
            ObjectIntPair.of("1_21_50", 685),
            ObjectIntPair.of("1_21_60", 776),
            ObjectIntPair.of("1_21_70", 786),
            ObjectIntPair.of("1_21_90", 818),
            ObjectIntPair.of("1_21_100", 827),
            ObjectIntPair.of("1_21_110", 844),
            ObjectIntPair.of("1_21_120", 859),
            ObjectIntPair.of("1_21_130", 898),
            ObjectIntPair.of("1_26_0", 924),
            ObjectIntPair.of("1_26_10", 944),
            ObjectIntPair.of("26_30", Bedrock_v1001.CODEC.getProtocolVersion()),
            ObjectIntPair.of("26_30", Bedrock_v2168.CODEC.getProtocolVersion()),
            ObjectIntPair.of("26_30", Bedrock_v2193.CODEC.getProtocolVersion())
        );""",
        "TagRegistryPopulator.paletteVersions",
    )

    # 9g. UpstreamSession: O(1) outgoing packet codec gate + EntityDataMap/EntityFlag/Attribute/EntityId sanitizer
    upstream_session = root / "core/src/main/java/org/geysermc/geyser/session/UpstreamSession.java"
    replace_exact(
        upstream_session,
        """import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.geysermc.geyser.network.bedrock.GeyserBedrockPeer;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;""",
        """import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BaseBedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.EntityDataTypeMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.cloudburstmc.protocol.bedrock.transformer.FlagTransformer;
import org.cloudburstmc.protocol.common.util.TypeMap;
import org.geysermc.geyser.network.bedrock.GeyserBedrockPeer;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;""",
        "UpstreamSession.imports",
    )
    replace_exact(
        upstream_session,
        """    public void sendPacket(@NonNull BedrockPacket packet) {
        if (!isClosed()) {
            session.sendPacket(packet);
        }
    }

    public void sendPacketImmediately(@NonNull BedrockPacket packet) {
        if (!isClosed()) {
            session.sendPacketImmediately(packet);
        }
    }""",
        """    private static final Set<String> ATTRIBUTES_26_30 = Set.of(
        "minecraft:friction_modifier",
        "minecraft:bounciness",
        "minecraft:air_drag_modifier"
    );

    private record ProtocolEntityFilter(EntityDataTypeMap dataTypes, TypeMap<EntityFlag> flagTypes) {}
    private static final ConcurrentHashMap<Integer, ProtocolEntityFilter> ENTITY_FILTERS = new ConcurrentHashMap<>();
    private static final Field ENTITY_DATA_FIELD;
    private static final Field FLAG_TYPEMAP_FIELD;

    static {
        Field edf = null;
        Field ftf = null;
        try {
            edf = BaseBedrockCodecHelper.class.getDeclaredField("entityData");
            edf.setAccessible(true);
            ftf = FlagTransformer.class.getDeclaredField("typeMap");
            ftf.setAccessible(true);
        } catch (Throwable ignored) {
        }
        ENTITY_DATA_FIELD = edf;
        FLAG_TYPEMAP_FIELD = ftf;
    }

    @SuppressWarnings("unchecked")
    private ProtocolEntityFilter getEntityFilter(int proto) {
        return ENTITY_FILTERS.computeIfAbsent(proto, p -> {
            try {
                BedrockCodecHelper helper = getCodecHelper();
                if (ENTITY_DATA_FIELD != null && helper instanceof BaseBedrockCodecHelper baseHelper) {
                    EntityDataTypeMap edm = (EntityDataTypeMap) ENTITY_DATA_FIELD.get(baseHelper);
                    TypeMap<EntityFlag> ftm = null;
                    EntityDataTypeMap.Definition<?> flagsDef = edm.fromType(EntityDataTypes.FLAGS);
                    if (flagsDef != null && FLAG_TYPEMAP_FIELD != null && flagsDef.getTransformer() instanceof FlagTransformer ft) {
                        ftm = (TypeMap<EntityFlag>) FLAG_TYPEMAP_FIELD.get(ft);
                    }
                    return new ProtocolEntityFilter(edm, ftm);
                }
            } catch (Throwable ignored) {
            }
            return new ProtocolEntityFilter(null, null);
        });
    }

    private void sanitizeEntityData(int proto, EntityDataMap metadata) {
        if (proto >= 2193 || metadata == null || metadata.isEmpty()) {
            return;
        }
        ProtocolEntityFilter filter = getEntityFilter(proto);
        if (filter.dataTypes() != null) {
            metadata.keySet().removeIf(type -> filter.dataTypes().fromType(type) == null);
        }
        EnumMap<EntityFlag, Boolean> flags = metadata.getFlags();
        if (flags != null && !flags.isEmpty() && filter.flagTypes() != null) {
            flags.keySet().removeIf(flag -> filter.flagTypes().getIdUnsafe(flag) == -1);
        }
    }

    private boolean prepareOutgoingPacket(BedrockPacket packet) {
        if (this.session.getCodec().getPacketDefinition(packet.getClass()) == null) {
            return false;
        }
        int proto = getProtocolVersion();
        if (proto >= 2193) {
            return true;
        }
        if (packet instanceof SetEntityDataPacket sed) {
            sanitizeEntityData(proto, sed.getMetadata());
        } else if (packet instanceof AddEntityPacket aep) {
            sanitizeEntityData(proto, aep.getMetadata());
            if (proto < 898) {
                String id = aep.getIdentifier();
                if (proto < 766 && "minecraft:creaking".equals(id)) {
                    aep.setIdentifier("minecraft:enderman");
                } else if (proto < 800 && "minecraft:happy_ghast".equals(id)) {
                    aep.setIdentifier("minecraft:ghast");
                } else if (proto < 844 && "minecraft:copper_golem".equals(id)) {
                    aep.setIdentifier("minecraft:iron_golem");
                } else if ("minecraft:nautilus".equals(id) || "minecraft:zombie_nautilus".equals(id)) {
                    aep.setIdentifier("minecraft:dolphin");
                } else if ("minecraft:camel_husk".equals(id)) {
                    aep.setIdentifier("minecraft:camel");
                } else if ("minecraft:parched".equals(id)) {
                    aep.setIdentifier("minecraft:stray");
                }
            }
        } else if (packet instanceof AddPlayerPacket app) {
            sanitizeEntityData(proto, app.getMetadata());
        } else if (packet instanceof AddItemEntityPacket aiep) {
            sanitizeEntityData(proto, aiep.getMetadata());
        } else if (proto < 1001 && packet instanceof UpdateAttributesPacket uap && uap.getAttributes() != null) {
            uap.getAttributes().removeIf(attr -> ATTRIBUTES_26_30.contains(attr.getName()));
            if (uap.getAttributes().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public void sendPacket(@NonNull BedrockPacket packet) {
        if (!isClosed() && prepareOutgoingPacket(packet)) {
            session.sendPacket(packet);
        }
    }

    public void sendPacketImmediately(@NonNull BedrockPacket packet) {
        if (!isClosed() && prepareOutgoingPacket(packet)) {
            session.sendPacketImmediately(packet);
        }
    }""",
        "UpstreamSession.multiVersionSanitizer",
    )
    replace_exact(
        upstream_session,
        """        BedrockPacket packet;
        while ((packet = postStartGamePackets.poll()) != null) {
            session.sendPacket(packet);
        }""",
        """        BedrockPacket packet;
        while ((packet = postStartGamePackets.poll()) != null) {
            if (prepareOutgoingPacket(packet)) {
                session.sendPacket(packet);
            }
        }""",
        "UpstreamSession.postStartGameSanitizer",
    )

    # 9h. GeyserSession: multi-version StartGamePacket, ItemComponentPacket, BiomeDefinitionListPacket, Jigsaw/VoxelShapes, Experiments
    geyser_session = root / "core/src/main/java/org/geysermc/geyser/session/GeyserSession.java"
    replace_exact(
        geyser_session,
        """        ItemComponentPacket componentPacket = new ItemComponentPacket();
        componentPacket.getItems().addAll(itemMappings.getItemDefinitions().values());
        upstream.sendPacket(componentPacket);""",
        """        ItemComponentPacket componentPacket = new ItemComponentPacket();
        if (this.protocolVersion() < 776) {
            for (var def : itemMappings.getItemDefinitions().values()) {
                if (def.isComponentBased() && def.getComponentData() != null && (
                        !def.getIdentifier().startsWith("minecraft:")
                        || def.getIdentifier().equals("minecraft:cake")
                        || def.getIdentifier().equals("minecraft:armor_stand")
                        || def.getIdentifier().equals("minecraft:firework_rocket"))) {
                    componentPacket.getItems().add(def);
                }
            }
        } else {
            componentPacket.getItems().addAll(itemMappings.getItemDefinitions().values());
        }
        upstream.sendPacket(componentPacket);""",
        "GeyserSession.itemComponentPacketMultiVersion",
    )
    replace_exact(
        geyser_session,
        """        BiomeDefinitionListPacket biomeDefinitionListPacket = new BiomeDefinitionListPacket();
        biomeDefinitionListPacket.setBiomes(Registries.BIOMES.get());
        upstream.sendPacket(biomeDefinitionListPacket);""",
        """        BiomeDefinitionListPacket biomeDefinitionListPacket = new BiomeDefinitionListPacket();
        if (this.protocolVersion() >= 800) {
            biomeDefinitionListPacket.setBiomes(Registries.BIOMES.get());
        } else {
            biomeDefinitionListPacket.setDefinitions(Registries.BIOMES_NBT.get());
        }
        upstream.sendPacket(biomeDefinitionListPacket);""",
        "GeyserSession.biomeDefinitionListMultiVersion",
    )
    replace_exact(
        geyser_session,
        """        JigsawStructureDataPacket jigsawStructureDataPacket = new JigsawStructureDataPacket();
        jigsawStructureDataPacket.setJigsawStructureDataTag(NbtMap.fromMap(Map.of(
            "processors", NbtList.EMPTY,
            "template_pools", NbtList.EMPTY,
            "jigsaws", NbtList.EMPTY,
            "structure_sets", NbtList.EMPTY
        )));
        upstream.sendPacket(jigsawStructureDataPacket);

        VoxelShapesPacket voxelShapesPacket = new VoxelShapesPacket();
        voxelShapesPacket.setNameMap(new HashMap<>());
        voxelShapesPacket.setShapes(new ArrayList<>());
        upstream.sendPacket(voxelShapesPacket);""",
        """        if (this.protocolVersion() >= 712) {
            JigsawStructureDataPacket jigsawStructureDataPacket = new JigsawStructureDataPacket();
            jigsawStructureDataPacket.setJigsawStructureDataTag(NbtMap.fromMap(Map.of(
                "processors", NbtList.EMPTY,
                "template_pools", NbtList.EMPTY,
                "jigsaws", NbtList.EMPTY,
                "structure_sets", NbtList.EMPTY
            )));
            upstream.sendPacket(jigsawStructureDataPacket);
        }

        if (this.protocolVersion() >= 975) {
            VoxelShapesPacket voxelShapesPacket = new VoxelShapesPacket();
            voxelShapesPacket.setNameMap(new HashMap<>());
            voxelShapesPacket.setShapes(new ArrayList<>());
            upstream.sendPacket(voxelShapesPacket);
        }""",
        "GeyserSession.jigsawAndVoxelShapesGates",
    )
    replace_exact(
        geyser_session,
        """        // Needed for custom block mappings and custom skulls system
        startGamePacket.getBlockProperties().addAll(this.blockMappings.getBlockProperties());""",
        """        // Needed for custom block mappings and custom skulls system
        startGamePacket.getBlockProperties().addAll(this.blockMappings.getBlockProperties());
        if (this.protocolVersion() < 776) {
            startGamePacket.getItemDefinitions().addAll(this.itemMappings.getItemDefinitions().values());
        }""",
        "GeyserSession.pre776StartGameItemDefinitions",
    )
    replace_exact(
        geyser_session,
        """        // Needed for certain molang queries used in blocks and items
        startGamePacket.getExperiments().add(new ExperimentData("experimental_molang_features", true));
    }""",
        """        // Needed for certain molang queries used in blocks and items
        startGamePacket.getExperiments().add(new ExperimentData("experimental_molang_features", true));
        if (this.protocolVersion() == 800) {
            startGamePacket.getExperiments().add(new ExperimentData("y_2025_drop_2", true));
            startGamePacket.getExperiments().add(new ExperimentData("locator_bar", true));
        } else if (this.protocolVersion() == 827) {
            startGamePacket.getExperiments().add(new ExperimentData("y_2025_drop_3", true));
        }
    }""",
        "GeyserSession.configureExperimentsMultiVersion",
    )
    replace_exact(
        geyser_session,
        """    private void syncEntityProperties() {
        for (NbtMap nbtMap : Registries.BEDROCK_ENTITY_PROPERTIES.get()) {
            SyncEntityPropertyPacket syncEntityPropertyPacket = new SyncEntityPropertyPacket();
            syncEntityPropertyPacket.setData(nbtMap);
            upstream.sendPacket(syncEntityPropertyPacket);
        }
    }""",
        """    private void syncEntityProperties() {
        int proto = this.protocolVersion();
        for (NbtMap nbtMap : Registries.BEDROCK_ENTITY_PROPERTIES.get()) {
            String type = nbtMap.getString("type", "");
            if ((proto < 766 && "minecraft:creaking".equals(type))
                || (proto < 800 && "minecraft:happy_ghast".equals(type))
                || (proto < 844 && "minecraft:copper_golem".equals(type))
                || (proto < 898 && ("minecraft:nautilus".equals(type)
                    || "minecraft:zombie_nautilus".equals(type)
                    || "minecraft:camel_husk".equals(type)
                    || "minecraft:parched".equals(type)))) {
                continue;
            }
            SyncEntityPropertyPacket syncEntityPropertyPacket = new SyncEntityPropertyPacket();
            syncEntityPropertyPacket.setData(nbtMap);
            upstream.sendPacket(syncEntityPropertyPacket);
        }
    }""",
        "GeyserSession.syncEntityPropertiesMultiVersion",
    )

    # 9i. UpstreamPacketHandler, BedrockInventoryTransactionTranslator, BedrockPlayerAuthInputTranslator,
    #     JavaRecipeBookAddTranslator, BoatEntity, SkullBlockEntityTranslator
    upstream_handler = root / "core/src/main/java/org/geysermc/geyser/network/bedrock/UpstreamPacketHandler.java"
    replace_exact(
        upstream_handler,
        """                stackPacket.setGameVersion("*");
                stackPacket.getResourcePacks().addAll(this.resourcePackLoadEvent.orderedPacks());

                session.sendUpstreamPacket(stackPacket);""",
        """                stackPacket.setGameVersion("*");
                stackPacket.getResourcePacks().addAll(this.resourcePackLoadEvent.orderedPacks());
                if (session.protocolVersion() == 800) {
                    stackPacket.getExperiments().add(new org.cloudburstmc.protocol.bedrock.data.ExperimentData("y_2025_drop_2", true));
                    stackPacket.getExperiments().add(new org.cloudburstmc.protocol.bedrock.data.ExperimentData("locator_bar", true));
                } else if (session.protocolVersion() == 827) {
                    stackPacket.getExperiments().add(new org.cloudburstmc.protocol.bedrock.data.ExperimentData("y_2025_drop_3", true));
                }

                session.sendUpstreamPacket(stackPacket);""",
        "UpstreamPacketHandler.stackExperiments",
    )

    inv_tx_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/BedrockInventoryTransactionTranslator.java"
    replace_exact(
        inv_tx_translator,
        """                if (packet.getActions().size() == 2) {
                    InventoryActionData worldAction = packet.getActions().get(1);
                    InventoryActionData containerAction = packet.getActions().get(0);""",
        """                if (packet.getActions().size() == 2) {
                    boolean is2630OrAbove = session.protocolVersion() >= 1001;
                    InventoryActionData worldAction = packet.getActions().get(is2630OrAbove ? 1 : 0);
                    InventoryActionData containerAction = packet.getActions().get(is2630OrAbove ? 0 : 1);""",
        "BedrockInventoryTransactionTranslator.dropItemOrder",
    )

    auth_input_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/entity/player/input/BedrockPlayerAuthInputTranslator.java"
    replace_exact(
        auth_input_translator,
        """        session.setInClientPredictedVehicle(packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE) && entity.getVehicle() != null);""",
        """        session.setInClientPredictedVehicle(session.protocolVersion() >= 944
            && packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)
            && entity.getVehicle() != null);""",
        "BedrockPlayerAuthInputTranslator.vehiclePredictionGate",
    )

    recipe_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/java/JavaRecipeBookAddTranslator.java"
    replace_exact(
        recipe_translator,
        """            if (display instanceof FurnaceRecipeDisplay furnaceRecipe) {""",
        """            if (display instanceof FurnaceRecipeDisplay furnaceRecipe) {
                if (session.protocolVersion() < 975) {
                    continue;
                }""",
        "JavaRecipeBookAddTranslator.furnaceRecipeGate",
    )

    boat_entity = root / "core/src/main/java/org/geysermc/geyser/entity/type/BoatEntity.java"
    replace_exact(
        boat_entity,
        """        this.variant = variant;

        metadata.put(EntityDataTypes.VARIANT, variant.ordinal());""",
        """        if (variant == BoatVariant.PALE_OAK && session.protocolVersion() < 766) {
            variant = BoatVariant.BIRCH;
        }
        this.variant = variant;

        metadata.put(EntityDataTypes.VARIANT, variant.ordinal());""",
        "BoatEntity.paleOakVariantFallback",
    )

    skull_be = root / "core/src/main/java/org/geysermc/geyser/translator/level/block/entity/SkullBlockEntityTranslator.java"
    replace_exact(
        skull_be,
        """        if (blockState.getValue(Properties.POWERED)) {
            bedrockNbt.putBoolean("MouthMoving", true);
        }""",
        """        if (session.protocolVersion() < 748 && blockState.block() instanceof org.geysermc.geyser.level.block.type.SkullBlock skull) {
            bedrockNbt.putByte("SkullType", (byte) skull.skullType().bedrockId());
        }
        if (blockState.getValue(Properties.POWERED)) {
            bedrockNbt.putBoolean("MouthMoving", true);
        }""",
        "SkullBlockEntityTranslator.pre748SkullType",
    )



if __name__ == "__main__":
    main()

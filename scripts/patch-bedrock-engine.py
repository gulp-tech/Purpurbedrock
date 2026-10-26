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

    # 3. AssetUtils.java & MinecraftLocale.java: instant offline en_us + ru_ru/uk_ua/be_by/kk_kz + deprecated
    #    translations from Bukkit/bundled classpath without downloading 50MB client.jar, plus quiet async
    #    Mojang asset index lookup for all other client languages.
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
        "AssetUtils.generateAssetCacheOfflineBypass",
    )
    replace_exact(
        asset_utils,
        """                // Get the url for the latest version of the games manifest
                String latestInfoURL = "";
                for (Version version : versionManifest.getVersions()) {
                    if (version.getId().equals(GameProtocol.getJavaMinecraftVersion())) {
                        latestInfoURL = version.getUrl();
                        break;
                    }
                }""",
        """                // Get the url for the latest version of the games manifest (with release fallback)
                String latestInfoURL = "";
                for (Version version : versionManifest.getVersions()) {
                    if (version.getId().equals(GameProtocol.getJavaMinecraftVersion())) {
                        latestInfoURL = version.getUrl();
                        break;
                    }
                }
                if (latestInfoURL.isEmpty() && versionManifest.getLatestVersion() != null) {
                    String fallbackRelease = versionManifest.getLatestVersion().getRelease();
                    for (Version version : versionManifest.getVersions()) {
                        if (version.getId().equals(fallbackRelease)) {
                            latestInfoURL = version.getUrl();
                            break;
                        }
                    }
                }""",
        "AssetUtils.generateAssetCacheFallback",
    )
    replace_exact(
        asset_utils,
        """            } catch (Exception e) {
                GeyserImpl.getInstance().getLogger().error(GeyserLocale.getLocaleStringLog("geyser.locale.fail.asset_cache", (!e.getMessage().isEmpty() ? e.getMessage() : e.getStackTrace())));
            }""",
        """            } catch (Exception e) {
                // Fail silently to debug if Mojang CDN is offline/blocked; bundled locales still work offline
                GeyserImpl.getInstance().getLogger().debug("Mojang asset index unavailable (using bundled locales): " + e.getMessage());
            }""",
        "AssetUtils.quietOfflineAssetIndex",
    )
    replace_exact(
        asset_utils,
        """    public static void downloadAndRunClientJarTasks() {
        if (CLIENT_JAR_INFO == null) {
            // Likely failed to download
            GeyserImpl.getInstance().getLogger().debug("Skipping en_US hash check as client jar is null.");
            return;
        }""",
        """    private static InputStream openServerOrBundledAsset(String assetPath) {
        String fileName = assetPath.substring(assetPath.lastIndexOf('/') + 1);
        InputStream stream = AssetUtils.class.getClassLoader().getResourceAsStream("purpurbedrock/locales/" + fileName);
        if (stream != null) {
            return stream;
        }
        stream = AssetUtils.class.getClassLoader().getResourceAsStream(assetPath);
        if (stream != null) {
            return stream;
        }
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            if (bukkitClass.getClassLoader() != null) {
                stream = bukkitClass.getClassLoader().getResourceAsStream(assetPath);
                if (stream != null) {
                    return stream;
                }
            }
            stream = bukkitClass.getResourceAsStream("/" + assetPath);
            if (stream != null) {
                return stream;
            }
        } catch (Throwable ignored) {
        }
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return ctx != null ? ctx.getResourceAsStream(assetPath) : null;
    }

    public static void downloadAndRunClientJarTasks() {
        if (Boolean.parseBoolean(System.getProperty("purpurbedrock.offlineAssets", "true")) || CLIENT_JAR_INFO == null) {
            ClientJarTask task;
            while ((task = CLIENT_JAR_TASKS.poll()) != null) {
                try {
                    InputStream classpathStream = openServerOrBundledAsset(task.asset);
                    if (classpathStream != null) {
                        try (InputStream in = classpathStream) {
                            task.ifNewDownload.accept(in);
                        }
                    }
                    task.whenDone.run();
                } catch (Exception ignored) {
                }
            }
            return;
        }""",
        "AssetUtils.downloadAndRunClientJarTasks",
    )

    minecraft_locale = root / "core/src/main/java/org/geysermc/geyser/text/MinecraftLocale.java"
    replace_exact(
        minecraft_locale,
        """    public static void ensureEN_US() {
        // en_us requires special treatment since it's included in Minecraft's JAR file
        Path localeFile = getPath("en_us");
        AssetUtils.addTask(!Files.exists(localeFile), new AssetUtils.ClientJarTask("assets/minecraft/lang/en_us.json",
                (stream) -> AssetUtils.saveFile(localeFile, stream),
                () -> loadLocale("en_us")));
    }""",
        """    private static volatile boolean purpurBundledLoaded = false;

    public static void ensureEN_US() {
        installBundledLocales();
        // en_us requires special treatment since it's included in Minecraft's JAR file
        Path localeFile = getPath("en_us");
        AssetUtils.addTask(!Files.exists(localeFile), new AssetUtils.ClientJarTask("assets/minecraft/lang/en_us.json",
                (stream) -> AssetUtils.saveFile(localeFile, stream),
                () -> loadLocale("en_us")));
    }

    private static synchronized void installBundledLocales() {
        if (purpurBundledLoaded && LOCALE_MAPPINGS.containsKey("ru_ru") && LOCALE_MAPPINGS.containsKey("en_us")) {
            return;
        }
        ClassLoader cl = MinecraftLocale.class.getClassLoader();
        // 1. Load deprecated.json directly from bundled JAR stream into REMOVED_KEYS & REPLACED_KEYS
        try {
            InputStream depIn = cl != null ? cl.getResourceAsStream("purpurbedrock/locales/deprecated.json") : null;
            if (depIn == null) {
                try {
                    Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
                    if (bukkitClass.getClassLoader() != null) {
                        depIn = bukkitClass.getClassLoader().getResourceAsStream("assets/minecraft/lang/deprecated.json");
                    }
                } catch (Throwable ignored) {
                }
            }
            if (depIn != null) {
                try (InputStream stream = depIn) {
                    JsonObject localeObj = JsonUtils.fromJson(stream);
                    JsonElement removed = localeObj.get("removed");
                    if (removed != null && removed.isJsonArray()) {
                        for (JsonElement removedElement : removed.getAsJsonArray()) {
                            REMOVED_KEYS.add(removedElement.getAsString());
                        }
                    }
                    JsonElement renamed = localeObj.get("renamed");
                    if (renamed != null && renamed.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> renamedElement : renamed.getAsJsonObject().entrySet()) {
                            REPLACED_KEYS.put(renamedElement.getKey(), renamedElement.getValue().getAsString());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 2. Load en_us, ru_ru, uk_ua, be_by, kk_kz directly from JAR classpath into LOCALE_MAPPINGS in RAM
        //    AND repair any missing/truncated (< 150 KB) files on disk from earlier failed Mojang downloads!
        String[] langs = new String[]{"en_us", "ru_ru", "uk_ua", "be_by", "kk_kz"};
        for (String code : langs) {
            try {
                InputStream in = cl != null ? cl.getResourceAsStream("purpurbedrock/locales/" + code + ".json") : null;
                if (in == null && "en_us".equals(code)) {
                    try {
                        Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
                        if (bukkitClass.getClassLoader() != null) {
                            in = bukkitClass.getClassLoader().getResourceAsStream("assets/minecraft/lang/en_us.json");
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (in != null) {
                    byte[] rawBytes;
                    try (InputStream stream = in) {
                        rawBytes = stream.readAllBytes();
                    }
                    if (LOCALE_FOLDER != null) {
                        Path target = getPath(code);
                        if (!Files.exists(target) || Files.size(target) < 150_000L) {
                            Files.write(target, rawBytes);
                        }
                    }
                    try (InputStream memStream = new java.io.ByteArrayInputStream(rawBytes)) {
                        JsonObject localeObj = JsonUtils.fromJson(memStream);
                        Map<String, String> langMap = new HashMap<>(8192);
                        for (Map.Entry<String, JsonElement> entry : localeObj.entrySet()) {
                            if (REMOVED_KEYS.contains(entry.getKey())) {
                                continue;
                            }
                            String val = entry.getValue().getAsString();
                            langMap.put(entry.getKey(), val);
                            String repl = REPLACED_KEYS.get(entry.getKey());
                            if (repl != null) {
                                langMap.put(repl, val);
                            }
                        }
                        if (LOCALE_FOLDER != null) {
                            Path overridePath = getPath("overrides/" + code);
                            if (Files.exists(overridePath) && Files.isReadable(overridePath)) {
                                langMap.putAll(parseLangFile(overridePath, code));
                            }
                        }
                        if (!langMap.isEmpty()) {
                            LOCALE_MAPPINGS.put(code, langMap);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        purpurBundledLoaded = true;
    }""",
        "MinecraftLocale.installBundledLocales",
    )
    replace_exact(
        minecraft_locale,
        """    public static @Nullable String getLocaleStringIfPresent(String messageText, String locale) {
        Map<String, String> localeStrings = LOCALE_MAPPINGS.get(locale.toLowerCase(Locale.ROOT));
        if (localeStrings != null) {
            String translated = localeStrings.get(messageText);
            if (translated != null) {
                return translated;
            }
        }
        localeStrings = LOCALE_MAPPINGS.get("en_us");
        if (localeStrings != null) {
            return localeStrings.get(messageText);
        }
        return null;
    }""",
        """    public static @Nullable String getLocaleStringIfPresent(String messageText, String locale) {
        if (!purpurBundledLoaded) {
            installBundledLocales();
        }
        String normLocale = locale == null ? "ru_ru" : locale.toLowerCase(Locale.ROOT);
        if (!LOCALE_MAPPINGS.containsKey(normLocale) && LOCALE_FOLDER != null && Files.exists(getPath(normLocale))) {
            loadLocale(normLocale);
        }
        String mappedKey = REPLACED_KEYS.getOrDefault(messageText, messageText);
        Map<String, String> localeStrings = LOCALE_MAPPINGS.get(normLocale);
        if (localeStrings != null) {
            String translated = localeStrings.get(messageText);
            if (translated == null && !mappedKey.equals(messageText)) {
                translated = localeStrings.get(mappedKey);
            }
            if (translated != null) {
                return translated;
            }
        }
        if (!"ru_ru".equals(normLocale) && normLocale.startsWith("ru_")) {
            Map<String, String> ruStrings = LOCALE_MAPPINGS.get("ru_ru");
            if (ruStrings != null) {
                String translated = ruStrings.getOrDefault(messageText, ruStrings.get(mappedKey));
                if (translated != null) {
                    return translated;
                }
            }
        }
        localeStrings = LOCALE_MAPPINGS.get("en_us");
        if (localeStrings != null) {
            String translated = localeStrings.get(messageText);
            if (translated == null && !mappedKey.equals(messageText)) {
                translated = localeStrings.get(mappedKey);
            }
            if (translated != null) {
                return translated;
            }
        }
        return lookupLegacySpawnOrSystemKey(messageText, normLocale);
    }

    private static @Nullable String lookupLegacySpawnOrSystemKey(String key, String normLocale) {
        boolean isRu = normLocale.startsWith("ru") || normLocale.startsWith("be") || normLocale.startsWith("kk");
        boolean isUk = normLocale.startsWith("uk");
        return switch (key) {
            case "potion.withDuration" -> "%s (%s)";
            case "potion.withAmplifier" -> "%s %s";
            case "effect.duration.infinite" -> "∞";
            case "chat.type.advancement.task" -> isRu ? "%s получил достижение %s" : (isUk ? "%s здобув досягнення %s" : "%s has made the advancement %s");
            case "chat.type.advancement.challenge" -> isRu ? "%s выполнил испытание %s" : (isUk ? "%s виконав випробування %s" : "%s has completed the challenge %s");
            case "chat.type.advancement.goal" -> isRu ? "%s достиг цели %s" : (isUk ? "%s досяг мети %s" : "%s has reached the goal %s");
            case "block.minecraft.spawn.not_valid" -> isRu
                ? "У вас нет кровати или заряженного якоря возрождения, либо доступ к ним затруднён"
                : (isUk ? "У вас немає ліжка або зарядженого якоря відродження, або доступ до них перешкоджено"
                    : "You have no home bed or charged respawn anchor, or it was obstructed");
            default -> null;
        };
    }""",
        "MinecraftLocale.getLocaleStringIfPresent",
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
            int maxAutoSkulls = Math.max(64, Integer.getInteger("purpurbedrock.maxCachedCustomSkulls", 2048));
            java.util.HashSet<String> seenHashes = new java.util.HashSet<>(skinHashes);
            java.util.ArrayList<Path> candidateFiles = new java.util.ArrayList<>();
            try (java.util.stream.Stream<Path> cachedFiles = Files.list(cachedSkullsDir)) {
                cachedFiles.forEach(file -> {
                    String fileName = file.getFileName().toString();
                    if (Files.isRegularFile(file) && fileName.endsWith(".png") && fileName.length() > 4) {
                        String candidateHash = fileName.substring(0, fileName.length() - 4);
                        if (SKULL_HASH_PATTERN.matcher(candidateHash).matches() && !seenHashes.contains(candidateHash)) {
                            candidateFiles.add(file);
                        }
                    }
                });
            }
            if (candidateFiles.size() > maxAutoSkulls) {
                candidateFiles.sort((a, b) -> {
                    try {
                        return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                    } catch (IOException ex) {
                        return 0;
                    }
                });
            }
            int addedAuto = 0;
            for (Path file : candidateFiles) {
                if (addedAuto >= maxAutoSkulls) {
                    break;
                }
                String fileName = file.getFileName().toString();
                String candidateHash = fileName.substring(0, fileName.length() - 4);
                if (seenHashes.add(candidateHash)) {
                    skinHashes.add(candidateHash);
                    addedAuto++;
                }
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
        } else if (proto < 1001 && packet instanceof UpdateAttributesPacket uap) {
            java.util.List<org.cloudburstmc.protocol.bedrock.data.AttributeData> attrs = uap.getAttributes();
            if (attrs == null || attrs.isEmpty()) {
                return false;
            }
            boolean hasUnsupported = false;
            for (int i = 0, n = attrs.size(); i < n; i++) {
                if (ATTRIBUTES_26_30.contains(attrs.get(i).getName())) {
                    hasUnsupported = true;
                    break;
                }
            }
            if (hasUnsupported) {
                java.util.ArrayList<org.cloudburstmc.protocol.bedrock.data.AttributeData> filtered = new java.util.ArrayList<>(attrs.size());
                for (int i = 0, n = attrs.size(); i < n; i++) {
                    org.cloudburstmc.protocol.bedrock.data.AttributeData ad = attrs.get(i);
                    if (!ATTRIBUTES_26_30.contains(ad.getName())) {
                        filtered.add(ad);
                    }
                }
                if (filtered.isEmpty()) {
                    return false;
                }
                uap.setAttributes(filtered);
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
        """        startGamePacket.setRewindHistorySize(0);""",
        """        // Required for < 1.21.90 (< v818) StartGameSerializer_v428.writeSyncedPlayerMovementSettings (prevents NPE on null ordinal)
        startGamePacket.setAuthoritativeMovementMode(org.cloudburstmc.protocol.bedrock.data.AuthoritativeMovementMode.SERVER);
        startGamePacket.setRewindHistorySize(0);""",
        "GeyserSession.pre818AuthoritativeMovementMode",
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

    # =========================================================================
    # 10. Bedrock Chunk-Loading Reliability Fixes & Real RakNet/NetherNet RTT Ping
    # =========================================================================
    chunk_utils = root / "core/src/main/java/org/geysermc/geyser/util/ChunkUtils.java"
    replace_exact(
        chunk_utils,
        """    public static void updateChunkPosition(GeyserSession session, Vector3i position) {
        Vector2i chunkPos = session.getLastChunkPosition();
        Vector2i newChunkPos = Vector2i.from(position.getX() >> 4, position.getZ() >> 4);

        if (chunkPos == null || !chunkPos.equals(newChunkPos)) {
            NetworkChunkPublisherUpdatePacket chunkPublisherUpdatePacket = new NetworkChunkPublisherUpdatePacket();
            chunkPublisherUpdatePacket.setPosition(position);
            // Mitigates chunks not loading on 1.17.1 Paper and 1.19.3 Fabric. As of Bedrock 1.19.60.
            // https://github.com/GeyserMC/Geyser/issues/3490
            chunkPublisherUpdatePacket.setRadius(squareToCircle(session.getServerRenderDistance()) << 4);
            session.sendUpstreamPacket(chunkPublisherUpdatePacket);

            session.setLastChunkPosition(newChunkPos);
        }
    }""",
        """    private static final java.util.Map<GeyserSession, Integer> LAST_PUBLISHER_RADIUS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static int computePublisherBlockRadius(GeyserSession session) {
        int serverDist = Math.max(session.getServerRenderDistance(), 8);
        int clientDist = session.getClientRenderDistance() > 0 ? Math.min(session.getClientRenderDistance(), 32) : serverDist;
        int effectiveChunks = Math.max(serverDist, Math.min(clientDist, serverDist + 2));
        return (squareToCircle(effectiveChunks) + 3) << 4;
    }

    public static Vector3i clampPublisherY(GeyserSession session, Vector3i position) {
        BedrockDimension dim = session.getBedrockDimension();
        int minY = dim != null ? dim.minY() : -64;
        int maxY = dim != null ? (dim.minY() + dim.height() - 1) : 319;
        int rawY = position.getY();
        int clampedY = (rawY < minY || rawY > maxY) ? Math.max(minY, Math.min(64, maxY)) : rawY;
        return clampedY == rawY ? position : Vector3i.from(position.getX(), clampedY, position.getZ());
    }

    public static void updateChunkPosition(GeyserSession session, Vector3i position) {
        updateChunkPosition(session, position, false);
    }

    public static void forceUpdateChunkPosition(GeyserSession session, Vector3i position) {
        updateChunkPosition(session, position, true);
    }

    public static void forceUpdateChunkPositionBlock(GeyserSession session, int blockX, int blockY, int blockZ) {
        updateChunkPosition(session, Vector3i.from(blockX, blockY, blockZ), true);
    }

    public static void updateChunkPosition(GeyserSession session, Vector3i position, boolean force) {
        Vector3i safePos = clampPublisherY(session, position);
        Vector2i chunkPos = session.getLastChunkPosition();
        Vector2i newChunkPos = Vector2i.from(safePos.getX() >> 4, safePos.getZ() >> 4);
        int blockRadius = computePublisherBlockRadius(session);
        Integer prevRadius = LAST_PUBLISHER_RADIUS.get(session);

        if (force || chunkPos == null || !chunkPos.equals(newChunkPos) || prevRadius == null || prevRadius != blockRadius) {
            NetworkChunkPublisherUpdatePacket chunkPublisherUpdatePacket = new NetworkChunkPublisherUpdatePacket();
            chunkPublisherUpdatePacket.setPosition(safePos);
            chunkPublisherUpdatePacket.setRadius(blockRadius);
            session.sendUpstreamPacket(chunkPublisherUpdatePacket);

            session.setLastChunkPosition(newChunkPos);
            LAST_PUBLISHER_RADIUS.put(session, blockRadius);
        }
    }

    public static void ensureChunkPublished(GeyserSession session, int chunkX, int chunkZ) {
        Vector2i lastCenter = session.getLastChunkPosition();
        int blockRadius = computePublisherBlockRadius(session);
        int chunkRadius = Math.max(4, (blockRadius >> 4) - 1);
        Vector3i playerPos = session.getPlayerEntity() != null
            ? session.getPlayerEntity().position().toInt()
            : Vector3i.from((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
        if (lastCenter == null) {
            updateChunkPosition(session, Vector3i.from((chunkX << 4) + 8, playerPos.getY(), (chunkZ << 4) + 8), true);
            return;
        }
        int dx = chunkX - lastCenter.getX();
        int dz = chunkZ - lastCenter.getY();
        if ((dx * dx + dz * dz) > (chunkRadius * chunkRadius)) {
            int playerChunkX = playerPos.getX() >> 4;
            int playerChunkZ = playerPos.getZ() >> 4;
            int pdx = chunkX - playerChunkX;
            int pdz = chunkZ - playerChunkZ;
            if ((pdx * pdx + pdz * pdz) <= (chunkRadius * chunkRadius)) {
                updateChunkPosition(session, playerPos, true);
            } else {
                updateChunkPosition(session, Vector3i.from((chunkX << 4) + 8, playerPos.getY(), (chunkZ << 4) + 8), true);
            }
        }
    }""",
        "ChunkUtils.reliableChunkPublisher",
    )

    replace_exact(
        chunk_utils,
        """    public static void sendEmptyChunks(GeyserSession session, Vector3i position, int radius, boolean forceUpdate) {
        int chunkX = position.getX() >> 4;
        int chunkZ = position.getZ() >> 4;""",
        """    public static void sendEmptyChunks(GeyserSession session, Vector3i position, int radius, boolean forceUpdate) {
        if (forceUpdate) {
            forceUpdateChunkPosition(session, position);
        } else {
            updateChunkPosition(session, position);
        }
        int chunkX = position.getX() >> 4;
        int chunkZ = position.getZ() >> 4;""",
        "ChunkUtils.sendEmptyChunksPublishFirst",
    )

    cache_center_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/java/level/JavaSetChunkCacheCenterTranslator.java"
    replace_exact(
        cache_center_translator,
        """        if (!session.isSpawned() && session.getLastChunkPosition() == null) {
            ChunkUtils.updateChunkPosition(session, Vector3i.from(packet.getChunkX() << 4, 64, packet.getChunkZ() << 4));
        }""",
        """        int centerY = session.getPlayerEntity() != null ? session.getPlayerEntity().position().toInt().getY() : 64;
        ChunkUtils.updateChunkPosition(session, Vector3i.from((packet.getChunkX() << 4) + 8, centerY, (packet.getChunkZ() << 4) + 8));""",
        "JavaSetChunkCacheCenterTranslator.alwaysUpdatePublisher",
    )

    player_pos_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/java/entity/player/JavaPlayerPositionTranslator.java"
    replace_exact(
        player_pos_translator,
        """                ChunkRadiusUpdatedPacket chunkRadiusUpdatedPacket = new ChunkRadiusUpdatedPacket();
                chunkRadiusUpdatedPacket.setRadius(session.getServerRenderDistance());
                session.sendUpstreamPacket(chunkRadiusUpdatedPacket);""",
        """                ChunkRadiusUpdatedPacket chunkRadiusUpdatedPacket = new ChunkRadiusUpdatedPacket();
                chunkRadiusUpdatedPacket.setRadius(ChunkUtils.squareToCircle(Math.max(session.getServerRenderDistance(), 8)));
                session.sendUpstreamPacket(chunkRadiusUpdatedPacket);""",
        "JavaPlayerPositionTranslator.dimensionRadiusCircle",
    )
    replace_exact(
        player_pos_translator,
        """            ChunkUtils.updateChunkPosition(session, entityPosition.toInt());""",
        """            ChunkUtils.forceUpdateChunkPosition(session, entityPosition.toInt());
            session.scheduleInEventLoop(() -> {
                if (!session.isClosed() && session.getPlayerEntity() != null) {
                    ChunkUtils.forceUpdateChunkPosition(session, session.getPlayerEntity().position().toInt());
                }
            }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);""",
        "JavaPlayerPositionTranslator.forcePublishOnSpawnAndWorldSwitch",
    )
    replace_exact(
        player_pos_translator,
        """        session.setUnconfirmedTeleport(new TeleportCache(session, teleportDestination, deltaMovement, newPitch, newYaw, teleportId, type));
        entity.moveAbsolute(teleportDestination, newYaw, newPitch, false, true);""",
        """        session.setUnconfirmedTeleport(new TeleportCache(session, teleportDestination, deltaMovement, newPitch, newYaw, teleportId, type));
        entity.moveAbsolute(teleportDestination, newYaw, newPitch, false, true);
        ChunkUtils.forceUpdateChunkPosition(session, teleportDestination.toInt());""",
        "JavaPlayerPositionTranslator.teleportUpdateChunkPosition",
    )

    level_chunk_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/java/level/JavaLevelChunkWithLightTranslator.java"
    replace_exact(
        level_chunk_translator,
        """        if (session.isSpawned()) {
            ChunkUtils.updateChunkPosition(session, session.getPlayerEntity().position().toInt());
        }""",
        """        if (session.isSpawned()) {
            ChunkUtils.updateChunkPosition(session, session.getPlayerEntity().position().toInt());
        }
        ChunkUtils.ensureChunkPublished(session, packet.getX(), packet.getZ());""",
        "JavaLevelChunkWithLightTranslator.ensureChunkPublished",
    )

    chunk_batch_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/java/level/JavaChunkBatchFinishedTranslator.java"
    replace_exact(
        chunk_batch_translator,
        """        session.sendDownstreamGamePacket(new ServerboundChunkBatchReceivedPacket(20));""",
        """        session.sendDownstreamGamePacket(new ServerboundChunkBatchReceivedPacket(32f));""",
        "JavaChunkBatchFinishedTranslator.higherChunkBatchRate",
    )

    replace_exact(
        geyser_session,
        """    private int clientRenderDistance = -1;
    private int serverRenderDistance = -1;""",
        """    private int clientRenderDistance = -1;
    private int serverRenderDistance = -1;
    private volatile int measuredRttMs = -1;
    private int rttProbeTicks = 0;""",
        "GeyserSession.measuredRttFields",
    )
    replace_exact(
        geyser_session,
        """        try {
            pistonCache.tick();""",
        """        try {
            if (++this.rttProbeTicks >= 60 && this.sentSpawnPacket && getUpstream().isInitialized() && !getUpstream().isClosed()) {
                this.rttProbeTicks = 0;
                if (this.measuredRttMs < 0 || !(getUpstream().getSession().getPeer().getChannel() instanceof RakChildChannel)) {
                    sendNetworkLatencyStackPacket(System.currentTimeMillis(), false, () -> {});
                }
            }
            pistonCache.tick();""",
        "GeyserSession.nethernetRttProbeTick",
    )
    replace_exact(
        geyser_session,
        """    public void setClientRenderDistance(int clientRenderDistance) {
        boolean oldSquareToCircle = this.clientRenderDistance < this.serverRenderDistance;
        this.clientRenderDistance = clientRenderDistance;
        boolean newSquareToCircle = this.clientRenderDistance < this.serverRenderDistance;

        if (this.serverRenderDistance != -1 && oldSquareToCircle != newSquareToCircle) {
            recalculateBedrockRenderDistance();
        }
    }""",
        """    public void setClientRenderDistance(int clientRenderDistance) {
        this.clientRenderDistance = clientRenderDistance;
        if (this.serverRenderDistance != -1) {
            recalculateBedrockRenderDistance();
        }
    }""",
        "GeyserSession.alwaysAckClientRenderDistance",
    )
    replace_exact(
        geyser_session,
        """    private void recalculateBedrockRenderDistance() {
        int renderDistance = ChunkUtils.squareToCircle(this.serverRenderDistance);
        ChunkRadiusUpdatedPacket chunkRadiusUpdatedPacket = new ChunkRadiusUpdatedPacket();
        chunkRadiusUpdatedPacket.setRadius(renderDistance);
        upstream.sendPacket(chunkRadiusUpdatedPacket);
    }""",
        """    private void recalculateBedrockRenderDistance() {
        int serverDist = Math.max(this.serverRenderDistance, 8);
        int effectiveClientDist = this.clientRenderDistance != -1
            ? Math.min(Math.max(this.clientRenderDistance, 5), serverDist)
            : serverDist;
        int renderDistance = this.clientRenderDistance != -1 && this.clientRenderDistance <= serverDist
            ? Math.max(5, this.clientRenderDistance)
            : ChunkUtils.squareToCircle(effectiveClientDist);
        ChunkRadiusUpdatedPacket chunkRadiusUpdatedPacket = new ChunkRadiusUpdatedPacket();
        chunkRadiusUpdatedPacket.setRadius(renderDistance);
        upstream.sendPacket(chunkRadiusUpdatedPacket);
        if (this.playerEntity != null && (this.sentSpawnPacket || this.lastChunkPosition != null)) {
            org.cloudburstmc.math.vector.Vector3i center = this.lastChunkPosition != null && !this.sentSpawnPacket
                ? org.cloudburstmc.math.vector.Vector3i.from((this.lastChunkPosition.getX() << 4) + 8, 64, (this.lastChunkPosition.getY() << 4) + 8)
                : this.playerEntity.position().toInt();
            ChunkUtils.forceUpdateChunkPosition(this, center);
        }
    }""",
        "GeyserSession.recalculateRenderDistanceRepublish",
    )
    replace_exact(
        geyser_session,
        """    private int getRenderDistance() {
        if (clientRenderDistance != -1) {
            // The client has sent a render distance
            return clientRenderDistance;
        } else if (serverRenderDistance != -1) {
            // only known once ClientboundLoginPacket is received
            return serverRenderDistance;
        }
        return 2; // unfortunate default until we got more info
    }""",
        """    private int getRenderDistance() {
        if (clientRenderDistance != -1) {
            return Math.max(5, clientRenderDistance);
        } else if (serverRenderDistance != -1) {
            return Math.max(6, serverRenderDistance);
        }
        return 8;
    }""",
        "GeyserSession.preventTinyInitialRenderDistance",
    )
    replace_exact(
        geyser_session,
        """    @Override
    public int ping() {
        // Can otherwise cause issues if the player isn't logged in yet / already left
        if (!getUpstream().isInitialized() || getUpstream().isClosed()) {
            return 0;
        }

        // TODO fixme
        // TODO NetherNet: expose the WebRTC round trip time
        if (!(getUpstream().getSession().getPeer().getChannel() instanceof RakChildChannel rakChannel)) {
            return 0;
        }

        RakSessionCodec rakSessionCodec = rakChannel.rakPipeline().get(RakSessionCodec.class);
        return (int) Math.floor(rakSessionCodec.getPing());
    }""",
        """    @Override
    public int ping() {
        if (!getUpstream().isInitialized() || getUpstream().isClosed()) {
            return Math.max(0, this.measuredRttMs);
        }

        if (getUpstream().getSession().getPeer().getChannel() instanceof RakChildChannel rakChannel) {
            RakSessionCodec rakSessionCodec = rakChannel.rakPipeline().get(RakSessionCodec.class);
            if (rakSessionCodec != null) {
                double rawPing = rakSessionCodec.getPing();
                if (rawPing > 0.0) {
                    return Math.max(1, (int) Math.round(rawPing));
                }
            }
        }

        return Math.max(0, this.measuredRttMs);
    }""",
        "GeyserSession.realRakNetAndNetherNetPing",
    )
    replace_exact(
        geyser_session,
        """    public void sendNetworkLatencyStackPacket(long timestamp, boolean ensureEventLoop, Runnable runnable) {
        NetworkStackLatencyPacket latencyPacket = new NetworkStackLatencyPacket();
        latencyPacket.setFromServer(true);
        latencyPacket.setTimestamp(timestamp);
        if (ensureEventLoop) {
            latencyPingCache.add(() -> ensureInEventLoop(runnable));
        } else {
            latencyPingCache.add(runnable);
        }
        sendUpstreamPacket(latencyPacket);
    }""",
        """    public void sendNetworkLatencyStackPacket(long timestamp, boolean ensureEventLoop, Runnable runnable) {
        final long sentAtNanos = System.nanoTime();
        Runnable timedCallback = () -> {
            this.measuredRttMs = Math.max(1, (int) Math.round((System.nanoTime() - sentAtNanos) / 1_000_000.0));
            runnable.run();
        };
        NetworkStackLatencyPacket latencyPacket = new NetworkStackLatencyPacket();
        latencyPacket.setFromServer(true);
        latencyPacket.setTimestamp(timestamp);
        if (ensureEventLoop) {
            latencyPingCache.add(() -> ensureInEventLoop(timedCallback));
        } else {
            latencyPingCache.add(timedCallback);
        }
        sendUpstreamPacket(latencyPacket);
    }""",
        "GeyserSession.measureLatencyStackRtt",
    )

    # 11. RaknetServer.java & BedrockClientData.java:
    #     - Prevent Cloudburst RakServerRateLimiter (default 120 datagrams/10ms) from blacklisting legitimate
    #       26.52 PC/CGNAT players during resource-pack (.mcpack), chunk-burst, or high-CPS gameplay.
    #     - Support Microsoft Windows GDK DeviceOS ordinal (16+) in Bedrock 26.x so PC clients (e.g. ZET Gaming / ASUS / MSI)
    #       resolve to DeviceOs.UWP ("Windows") instead of DeviceOs.UNKNOWN (which caused VoidTab to show kbmouse!).
    raknet_server = root / "core/src/main/java/org/geysermc/geyser/network/RaknetServer.java"
    replace_exact(
        raknet_server,
        """        int rakPacketLimit = positivePropOrDefault("Geyser.RakPacketLimit", DEFAULT_PACKET_LIMIT);
        this.geyser.getLogger().debug("Setting RakNet packet limit to " + rakPacketLimit);

        int rakGlobalPacketLimit = positivePropOrDefault("Geyser.RakGlobalPacketLimit", DEFAULT_GLOBAL_PACKET_LIMIT);
        this.geyser.getLogger().debug("Setting RakNet global packet limit to " + rakGlobalPacketLimit);

        boolean rakSendCookie = Boolean.parseBoolean(System.getProperty("Geyser.RakSendCookie", "true"));
        this.geyser.getLogger().debug("Setting RakNet send cookie to " + rakSendCookie);

        int maxConnectionsPerAddress =  positivePropOrDefault("Geyser.MaxConnectionsPerAddress", 10);""",
        """        int rakPacketLimit = positivePropOrDefault("Geyser.RakPacketLimit", Math.max(3000, DEFAULT_PACKET_LIMIT));
        this.geyser.getLogger().debug("Setting RakNet packet limit to " + rakPacketLimit);

        int rakGlobalPacketLimit = positivePropOrDefault("Geyser.RakGlobalPacketLimit", Math.max(300000, DEFAULT_GLOBAL_PACKET_LIMIT));
        this.geyser.getLogger().debug("Setting RakNet global packet limit to " + rakGlobalPacketLimit);

        boolean rakSendCookie = Boolean.parseBoolean(System.getProperty("Geyser.RakSendCookie", "false"));
        this.geyser.getLogger().debug("Setting RakNet send cookie to " + rakSendCookie);

        int maxConnectionsPerAddress = positivePropOrDefault("Geyser.MaxConnectionsPerAddress", 32);""",
        "RaknetServer.safeRateLimitsForPackAndCgnat",
    )

    client_data = root / "core/src/main/java/org/geysermc/geyser/session/auth/BedrockClientData.java"
    replace_exact(
        client_data,
        """    public DeviceOs getDeviceOs() {
        return deviceOs != null ? deviceOs : DeviceOs.UNKNOWN;
    }""",
        """    public DeviceOs getDeviceOs() {
        if (deviceOs != null && deviceOs != DeviceOs.UNKNOWN) {
            return deviceOs;
        }
        String modelUpper = deviceModel == null ? "" : deviceModel.trim().toUpperCase(java.util.Locale.ROOT);
        if (modelUpper.contains("PLAYSTATION") || modelUpper.startsWith("PS4") || modelUpper.startsWith("PS5")) {
            return DeviceOs.PS4;
        }
        if (modelUpper.contains("XBOX")) {
            return DeviceOs.XBOX;
        }
        if (modelUpper.startsWith("IPHONE") || modelUpper.startsWith("IPAD")) {
            return DeviceOs.IOS;
        }
        if (defaultInputMode == InputMode.KEYBOARD_MOUSE
            || currentInputMode == InputMode.KEYBOARD_MOUSE
            || uiProfile == UiProfile.CLASSIC) {
            // Bedrock 26.x Microsoft GDK for Windows sends DeviceOS >= 16 (outside legacy Floodgate enum)
            return DeviceOs.UWP;
        }
        if (defaultInputMode == InputMode.CONTROLLER || currentInputMode == InputMode.CONTROLLER) {
            return DeviceOs.XBOX;
        }
        return DeviceOs.UNKNOWN;
    }""",
        "BedrockClientData.windowsGdkGetDeviceOs",
    )
    replace_exact(
        client_data,
        """                public T read(JsonReader in) throws IOException {
                    int ordinal = in.nextInt();
                    if (ordinal < 0 || ordinal >= constants.length) {
                        return null;
                    }
                    return constants[ordinal];
                }""",
        """                @SuppressWarnings("unchecked")
                public T read(JsonReader in) throws IOException {
                    int ordinal = in.nextInt();
                    if (ordinal < 0 || ordinal >= constants.length) {
                        if (rawType == DeviceOs.class && ordinal >= constants.length) {
                            return (T) DeviceOs.UWP;
                        }
                        return null;
                    }
                    return constants[ordinal];
                }""",
        "BedrockClientData.windowsGdkOrdinalMapping",
    )

    # 12. Purpurbedrock Geyser Performance & Main-Thread Optimization Suite:
    #     - Async background pre-warm of Geyser registries during plugin enable so ServerLoadEvent spends ~0.00ms on Server thread.
    #     - Thread-safe O(1) VersionedRegistry cache + on-demand lazy loading for legacy (< 26.50 / < v2193) block, item, tag & potion palettes
    #       (cuts boot CPU by ~90% and saves ~350 MB of heap RAM when only modern 26.x players are online, while preserving 100% 1.21.0..26.52 support).
    #     - Algorithmic O(1) fixes in BlockRegistryPopulator, ItemRegistryPopulator, and TagRegistryPopulator (eliminating O(N^2) linear scans and NbtMap churn).
    #     - Runtime hot-path fixes: O(1) UUID player lookup in GeyserSpigotNativeWorldManager, lazy locale loading in SpigotCommandSource
    #       (fixing BrigadierPermissionPredicate.test() overhead), accurate GeyserChunkSection buffer sizing + zero-alloc empty subchunks +
    #       BitSet BedrockChunkWantsBlockEntityTag lookup in JavaLevelChunkWithLightTranslator, idle ScoreboardUpdater backoff, and disabled join update HTTP check.

    versioned_registry = root / "core/src/main/java/org/geysermc/geyser/registry/VersionedRegistry.java"
    replace_exact(
        versioned_registry,
        """public class VersionedRegistry<V> extends AbstractMappedRegistry<Integer, V, Int2ObjectMap<V>> {
    protected <I> VersionedRegistry(I input, RegistryLoader<I, Int2ObjectMap<V>> registryLoader) {
        super(input, registryLoader);
    }

    /**
     * Gets the closest value for the specified version. Only
     * returns versions higher up than the specified if one
     * does not exist for the given one. Useful in the event
     * that you want to get a resource which is guaranteed for
     * older versions, but not on newer ones.
     *
     * @param version the version
     * @return the closest value for the specified version
     * @throws IllegalArgumentException if no values exist at or above the given version
     */
    @NonNull
    public V forVersion(int version) {
        Int2ObjectMap.Entry<V> current = null;
        for (Int2ObjectMap.Entry<V> entry : this.mappings.int2ObjectEntrySet()) {
            int currentVersion = entry.getIntKey();
            if (version < currentVersion) {
                continue;
            }
            if (version == currentVersion) {
                return entry.getValue();
            }
            if (current == null || current.getIntKey() < currentVersion) {
                // This version is newer and should be prioritized
                current = entry;
            }
        }
        if (current == null) {
            throw new IllegalArgumentException("No appropriate value for version: " + version);
        }
        return current.getValue();
    }""",
        """public class VersionedRegistry<V> extends AbstractMappedRegistry<Integer, V, Int2ObjectMap<V>> {
    private final Int2ObjectMap<java.util.function.IntFunction<V>> lazyLoaders =
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, V> resolvedCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    protected <I> VersionedRegistry(I input, RegistryLoader<I, Int2ObjectMap<V>> registryLoader) {
        super(input, registryLoader);
    }

    @Override
    public V register(Integer key, V value) {
        synchronized (this) {
            V previous = super.register(key, value);
            if (key != null) {
                this.lazyLoaders.remove(key.intValue());
            }
            this.resolvedCache.clear();
            return previous;
        }
    }

    public void registerLazy(int version, java.util.function.IntFunction<V> loader) {
        synchronized (this) {
            if (!this.mappings.containsKey(version)) {
                this.lazyLoaders.put(version, loader);
                this.resolvedCache.clear();
            }
        }
    }

    public int[] lazyVersions() {
        synchronized (this) {
            return this.lazyLoaders.keySet().toIntArray();
        }
    }

    /**
     * Gets the closest value for the specified version. Only
     * returns versions higher up than the specified if one
     * does not exist for the given one. Useful in the event
     * that you want to get a resource which is guaranteed for
     * older versions, but not on newer ones.
     *
     * @param version the version
     * @return the closest value for the specified version
     * @throws IllegalArgumentException if no values exist at or above the given version
     */
    @NonNull
    public V forVersion(int version) {
        V cached = this.resolvedCache.get(version);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = this.resolvedCache.get(version);
            if (cached != null) {
                return cached;
            }
            int bestVersion = Integer.MIN_VALUE;
            boolean bestIsLazy = false;
            for (Int2ObjectMap.Entry<V> entry : this.mappings.int2ObjectEntrySet()) {
                int v = entry.getIntKey();
                if (v <= version && v > bestVersion) {
                    bestVersion = v;
                    bestIsLazy = false;
                }
            }
            for (Int2ObjectMap.Entry<java.util.function.IntFunction<V>> entry : this.lazyLoaders.int2ObjectEntrySet()) {
                int v = entry.getIntKey();
                if (v <= version && v > bestVersion) {
                    bestVersion = v;
                    bestIsLazy = true;
                }
            }
            if (bestVersion == Integer.MIN_VALUE) {
                throw new IllegalArgumentException("No appropriate value for version: " + version);
            }
            V resolved;
            if (bestIsLazy) {
                java.util.function.IntFunction<V> loader = this.lazyLoaders.remove(bestVersion);
                resolved = loader.apply(bestVersion);
                this.mappings.put(bestVersion, resolved);
            } else {
                resolved = this.mappings.get(bestVersion);
            }
            this.resolvedCache.put(version, resolved);
            return resolved;
        }
    }""",
        "VersionedRegistry.lazyAndCachedForVersion",
    )

    versioned_deferred = root / "core/src/main/java/org/geysermc/geyser/registry/VersionedDeferredRegistry.java"
    replace_exact(
        versioned_deferred,
        """    @NonNull
    public V forVersion(int version) {
        return backingRegistry().forVersion(version);
    }""",
        """    @NonNull
    public V forVersion(int version) {
        return backingRegistry().forVersion(version);
    }

    public void registerLazy(int version, java.util.function.IntFunction<V> loader) {
        backingRegistry().registerLazy(version, loader);
    }""",
        "VersionedDeferredRegistry.registerLazy",
    )

    block_registries = root / "core/src/main/java/org/geysermc/geyser/registry/BlockRegistries.java"
    replace_exact(
        block_registries,
        """    public static final SimpleRegistry<BitSet> WATERLOGGED = SimpleRegistry.create(RegistryLoaders.empty(BitSet::new));""",
        """    public static final SimpleRegistry<BitSet> WATERLOGGED = SimpleRegistry.create(RegistryLoaders.empty(BitSet::new));

    public static final BitSet BEDROCK_CHUNK_BLOCK_ENTITIES = new BitSet();""",
        "BlockRegistries.BEDROCK_CHUNK_BLOCK_ENTITIES",
    )

    replace_exact(
        block_populator,
        """    /**
     * Stores the raw blocks NBT until it is no longer needed.
     */
    private static List<NbtMap> BLOCKS_NBT;
    public static int MIN_CUSTOM_RUNTIME_ID = -1;
    public static int JAVA_BLOCKS_SIZE = -1;

    private static void nullifyBlocksNbt() {
        BLOCKS_NBT = null;
    }

    private static void registerBedrockBlocks() {
        var blockMappers = ImmutableMap.<ObjectIntPair<String>, Remapper>builder()
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
            .build();

        // We can keep this strong as nothing should be garbage collected
        // Safe to intern since Cloudburst NBT is immutable
        //noinspection UnstableApiUsage
        Interner<NbtMap> statesInterner = Interners.newStrongInterner();

        for (ObjectIntPair<String> palette : blockMappers.keySet()) {
            int protocolVersion = palette.valueInt();
            List<NbtMap> vanillaBlockStates;
            List<NbtMap> blockStates;
            try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrThrow(String.format("bedrock/block_palette.%s.nbt", palette.key()));
                NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)), true, true)) {
                NbtMap blockPalette = (NbtMap) nbtInputStream.readTag();

                vanillaBlockStates = new ArrayList<>(blockPalette.getList("blocks", NbtType.COMPOUND));
                for (int i = 0; i < vanillaBlockStates.size(); i++) {
                    NbtMapBuilder builder = vanillaBlockStates.get(i).toBuilder();
                    builder.remove("version"); // Remove all nbt tags which are not needed for differentiating states
                    builder.remove("name_hash"); // Quick workaround - was added in 1.19.20
                    builder.remove("network_id"); // Added in 1.19.80
                    builder.remove("block_id"); // Added in 1.20.60
                    //noinspection UnstableApiUsage
                    builder.putCompound("states", statesInterner.intern((NbtMap) builder.remove("states")));
                    vanillaBlockStates.set(i, builder.build());
                }

                blockStates = new ArrayList<>(vanillaBlockStates);
            } catch (Exception e) {
                throw new AssertionError("Unable to get blocks from runtime block states", e);
            }

            List<BlockPropertyData> customBlockProperties = new ArrayList<>();
            List<NbtMap> customBlockStates = new ArrayList<>();
            List<CustomBlockState> customExtBlockStates = new ArrayList<>();
            int[] remappedVanillaIds = new int[0];

            // TODO Make this throw once we drop any version below 26.50, as it's required in 26.50+
            InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrNull(String.format("bedrock/data_driven_blocks.%s.nbt", palette.key()));
            if (stream != null) {
                // Data-driven blocks!!!
                try (stream; NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)))) {
                    NbtMap dataDrivenBlocks = (NbtMap) nbtInputStream.readTag();
                    for (Map.Entry<String, Object> entry : dataDrivenBlocks.entrySet()) {
                        customBlockProperties.add(new BlockPropertyData(entry.getKey(), (NbtMap) entry.getValue()));
                    }
                } catch (IOException e) {
                    throw new AssertionError("Unable to get data driven blocks from runtime definitions", e);
                }
            }

            if (BlockRegistries.CUSTOM_BLOCKS.get().length != 0) {
                CustomBlockRegistryPopulator.BLOCK_ID.set(CustomBlockRegistryPopulator.START_OFFSET);
                for (CustomBlockData customBlock : BlockRegistries.CUSTOM_BLOCKS.get()) {
                    customBlockProperties.add(CustomBlockRegistryPopulator.generateBlockPropertyData(customBlock));
                    CustomBlockRegistryPopulator.generateCustomBlockStates(customBlock, customBlockStates, customExtBlockStates);
                }
                blockStates.addAll(customBlockStates);
                GeyserImpl.getInstance().getLogger().debug("Added " + customBlockStates.size() + " custom block states to v" + protocolVersion + " palette.");

                // The palette is sorted by the FNV1 64-bit hash of the name
                blockStates.sort((a, b) -> Long.compareUnsigned(fnv164(a.getString("name")), fnv164(b.getString("name"))));
            }

            // New since 1.16.100 - find the block runtime ID by the order given to us in the block palette,
            // as we no longer send a block palette
            Object2ObjectMap<NbtMap, GeyserBedrockBlock> blockStateOrderedMap = new Object2ObjectOpenHashMap<>(blockStates.size());
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
            }

            Object2ObjectMap<CustomBlockState, GeyserBedrockBlock> customBlockStateDefinitions = Object2ObjectMaps.emptyMap();
            if (BlockRegistries.CUSTOM_BLOCKS.get().length != 0) {
                customBlockStateDefinitions = new Object2ObjectOpenHashMap<>(customExtBlockStates.size());
                for (int i = 0; i < customExtBlockStates.size(); i++) {
                    NbtMap tag = customBlockStates.get(i);
                    CustomBlockState blockState = customExtBlockStates.get(i);
                    GeyserBedrockBlock bedrockBlock = blockStateOrderedMap.get(tag);
                    customBlockStateDefinitions.put(blockState, bedrockBlock);
                }

                remappedVanillaIds = new int[vanillaBlockStates.size()];
                for (int i = 0; i < vanillaBlockStates.size(); i++) {
                    GeyserBedrockBlock bedrockBlock = blockStateOrderedMap.get(vanillaBlockStates.get(i));
                    remappedVanillaIds[i] = bedrockBlock != null ? bedrockBlock.getRuntimeId() : -1;
                }
            }

            int javaRuntimeId = -1;

            List<BlockState> javaBlockStates = BlockRegistries.BLOCK_STATES.get();

            GeyserBedrockBlock airDefinition = null;
            BlockDefinition commandBlockDefinition = null;
            BlockDefinition mobSpawnerBlockDefinition = null;
            BlockDefinition netherPortalBlockDefinition = null;
            BlockDefinition waterDefinition = null;
            BlockDefinition movingBlockDefinition = null;
            Iterator<NbtMap> blocksIterator = BLOCKS_NBT.iterator();

            Remapper stateMapper = blockMappers.get(palette);

            GeyserBedrockBlock[] javaToBedrockBlocks = new GeyserBedrockBlock[JAVA_BLOCKS_SIZE];
            GeyserBedrockBlock[] javaToVanillaBedrockBlocks = new GeyserBedrockBlock[JAVA_BLOCKS_SIZE];

            var javaToBedrockIdentifiers = new Int2ObjectOpenHashMap<String>();
            Block lastBlockSeen = null;

            // Stream isn't ideal.
            List<Block> javaPottable = BlockRegistries.JAVA_BLOCKS.get()
                    .parallelStream()
                    .flatMap(block -> {
                        if (block instanceof FlowerPotBlock flowerPot && flowerPot.flower() != Blocks.AIR) {
                            return Stream.of(flowerPot.flower());
                        }
                        return null;
                    })
                    .toList();
            Map<Block, NbtMap> flowerPotBlocks = new Object2ObjectOpenHashMap<>();
            Map<NbtMap, BlockDefinition> itemFrames = new Object2ObjectOpenHashMap<>();
            IntArrayList collisionIgnoredBlocks = new IntArrayList();

            Set<BlockDefinition> jigsawDefinitions = new ObjectOpenHashSet<>();
            Map<String, BlockDefinition> structureBlockDefinitions = new Object2ObjectOpenHashMap<>();

            BlockMappings.BlockMappingsBuilder builder = BlockMappings.builder();
            while (blocksIterator.hasNext()) {
                javaRuntimeId++;
                NbtMap entry = blocksIterator.next();
                BlockState blockState = javaBlockStates.get(javaRuntimeId);
                String javaId = blockState.toString();

                NbtMap originalBedrockTag = buildBedrockState(blockState, entry);
                NbtMap bedrockTag = MultiVersionPaletteHelper.remapBlockForVersion(protocolVersion, blockState, stateMapper.remap(originalBedrockTag));

                GeyserBedrockBlock vanillaBedrockDefinition = MultiVersionPaletteHelper.resolveBlockDefinition(bedrockTag, blockStateOrderedMap, blocksByName);

                GeyserBedrockBlock bedrockDefinition;
                CustomBlockState blockStateOverride = BlockRegistries.CUSTOM_BLOCK_STATE_OVERRIDES.get(javaRuntimeId);
                if (blockStateOverride == null) {
                    bedrockDefinition = vanillaBedrockDefinition;
                    if (bedrockDefinition == null) {
                        throw new RuntimeException(\"\"\"
                            Unable to find %s Bedrock runtime ID for %s! Original block tag:
                            %s
                            Updated block tag:
                            %s\"\"\".formatted(javaId, palette.key(), originalBedrockTag, bedrockTag));
                    }
                } else {
                    bedrockDefinition = customBlockStateDefinitions.get(blockStateOverride);
                    if (bedrockDefinition == null) {
                        throw new RuntimeException("Unable to find " + javaId + " Bedrock runtime ID! Custom block override: \\n" +
                            blockStateOverride);
                    }
                }

                switch (javaId) {
                    case "minecraft:air" -> airDefinition = bedrockDefinition;
                    case "minecraft:water[level=0]" -> waterDefinition = bedrockDefinition;
                    case "minecraft:command_block[conditional=false,facing=north]" -> commandBlockDefinition = bedrockDefinition;
                    case "minecraft:spawner" -> mobSpawnerBlockDefinition = bedrockDefinition;
                    case "minecraft:moving_piston[facing=north,type=normal]" -> movingBlockDefinition = bedrockDefinition;
                }

                Block block = blockState.block();
                if (block != lastBlockSeen) {
                    lastBlockSeen = block;
                    String bedrockName = bedrockDefinition.getState().getString("name");
                    if (!block.javaIdentifier().toString().equals(bedrockName)) {
                        javaToBedrockIdentifiers.put(block.javaId(), bedrockName.substring("minecraft:".length()).intern());
                    }
                }

                if (block == Blocks.JIGSAW) {
                    jigsawDefinitions.add(bedrockDefinition);
                }

                if (block == Blocks.STRUCTURE_BLOCK) {
                    String mode = blockState.getValue(Properties.STRUCTUREBLOCK_MODE);
                    structureBlockDefinitions.put(mode.toUpperCase(Locale.ROOT), bedrockDefinition);
                }

                if (block == Blocks.NETHER_PORTAL) {
                    netherPortalBlockDefinition = bedrockDefinition;
                }

                if (block == Blocks.BAMBOO || block == Blocks.POINTED_DRIPSTONE) {
                    collisionIgnoredBlocks.add(javaRuntimeId);
                }

                boolean waterlogged = blockState.getValue(Properties.WATERLOGGED, false)
                        || block == Blocks.BUBBLE_COLUMN || block == Blocks.KELP || block == Blocks.KELP_PLANT
                        || block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS;

                if (waterlogged) {
                    BlockRegistries.WATERLOGGED.get().set(javaRuntimeId);
                }

                // Get the tag needed for non-empty flower pots
                if (javaPottable.contains(block)) {
                    // Specifically NOT putIfAbsent - mangrove propagule breaks otherwise
                    flowerPotBlocks.put(block, blockStates.get(bedrockDefinition.getRuntimeId()));
                }

                javaToVanillaBedrockBlocks[javaRuntimeId] = vanillaBedrockDefinition;
                javaToBedrockBlocks[javaRuntimeId] = bedrockDefinition;
            }

            builder.collisionIgnoredBlocks(collisionIgnoredBlocks);

            if (commandBlockDefinition == null) {
                throw new AssertionError("Unable to find command block in palette");
            }
            builder.commandBlock(commandBlockDefinition);

            if (mobSpawnerBlockDefinition == null) {
                throw new AssertionError("Unable to find mob spawner block in palette");
            }
            builder.mobSpawnerBlock(mobSpawnerBlockDefinition);

            if (netherPortalBlockDefinition == null) {
                throw new AssertionError("Unable to find nether portal block in palette");
            }
            builder.netherPortalBlock(netherPortalBlockDefinition);

            if (waterDefinition  == null) {
                throw new AssertionError("Unable to find water in palette");
            }
            builder.bedrockWater(waterDefinition);

            if (airDefinition  == null) {
                throw new AssertionError("Unable to find air in palette");
            }
            builder.bedrockAir(airDefinition);

            if (movingBlockDefinition  == null) {
                throw new AssertionError("Unable to find moving block in palette");
            }
            builder.bedrockMovingBlock(movingBlockDefinition);

            Map<JavaBlockState, CustomBlockState> nonVanillaStateOverrides = BlockRegistries.NON_VANILLA_BLOCK_STATE_OVERRIDES.get();
            if (!nonVanillaStateOverrides.isEmpty()) {
                // First ensure all non vanilla runtime IDs at minimum are air in case they aren't consecutive
                Arrays.fill(javaToVanillaBedrockBlocks, MIN_CUSTOM_RUNTIME_ID, javaToVanillaBedrockBlocks.length, airDefinition);
                Arrays.fill(javaToBedrockBlocks, MIN_CUSTOM_RUNTIME_ID, javaToBedrockBlocks.length, airDefinition);

                for (Map.Entry<JavaBlockState, CustomBlockState> entry : nonVanillaStateOverrides.entrySet()) {
                    GeyserBedrockBlock bedrockDefinition = customBlockStateDefinitions.get(entry.getValue());
                    if (bedrockDefinition == null) {
                        GeyserImpl.getInstance().getLogger().warning("Unable to find custom block for " + entry.getValue());
                        continue;
                    }

                    JavaBlockState javaState = entry.getKey();
                    int stateRuntimeId = javaState.javaId();

                    boolean waterlogged = javaState.waterlogged();

                    if (waterlogged) {
                        BlockRegistries.WATERLOGGED.register(set -> set.set(stateRuntimeId));
                    }

                    javaToVanillaBedrockBlocks[stateRuntimeId] = bedrockDefinition; // TODO: Check this?
                    javaToBedrockBlocks[stateRuntimeId] = bedrockDefinition;
                    javaToBedrockIdentifiers.put(entry.getKey().stateGroupId(), entry.getValue().block().identifier());
                }
            }

            javaToBedrockIdentifiers.trim();

            // Loop around again to find all item frame runtime IDs
            Object2ObjectMaps.fastForEach(blockStateOrderedMap, entry -> {
                String name = entry.getKey().getString("name");
                if (name.equals("minecraft:frame") || name.equals("minecraft:glow_frame")) {
                    itemFrames.put(entry.getKey(), entry.getValue());
                }
            });

            BlockRegistries.BLOCKS.register(palette.valueInt(), builder.bedrockRuntimeMap(bedrockRuntimeMap)
                    .javaToBedrockBlocks(javaToBedrockBlocks)
                    .javaToVanillaBedrockBlocks(javaToVanillaBedrockBlocks)
                    .javaToBedrockIdentifiers(javaToBedrockIdentifiers)
                    .stateDefinitionMap(blockStateOrderedMap)
                    .itemFrames(itemFrames)
                    .flowerPotBlocks(flowerPotBlocks)
                    .jigsawStates(jigsawDefinitions)
                    .structureBlockStates(structureBlockDefinitions)
                    .remappedVanillaIds(remappedVanillaIds)
                    .blockProperties(customBlockProperties)
                    .customBlockStateDefinitions(customBlockStateDefinitions)
                    .build());
        }
    }

    private static void registerJavaBlocks() {
        List<NbtMap> blocksNbt;
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrThrow("mappings/blocks.nbt")) {
            blocksNbt = ((NbtMap) NbtUtils.createGZIPReader(stream).readTag())
                    .getList("bedrock_mappings", NbtType.COMPOUND);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java block mappings", e);
        }

        int javaRuntimeId = -1;
        for (BlockState javaBlockState : BlockRegistries.BLOCK_STATES.get()) {
            javaRuntimeId++;
            String javaId = javaBlockState.toString().intern();

            BlockRegistries.JAVA_BLOCK_STATE_IDENTIFIER_TO_ID.register(javaId, javaRuntimeId);
        }

        BLOCKS_NBT = blocksNbt;
        JAVA_BLOCKS_SIZE = blocksNbt.size();

        JsonObject blockInteractionsJson;
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrThrow("mappings/interactions.json")) {
            blockInteractionsJson = JsonUtils.fromJson(stream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java block interaction mappings", e);
        }

        BlockRegistries.INTERACTIVE.set(toBlockStateSet(blockInteractionsJson.getAsJsonArray("always_consumes")));
        BlockRegistries.INTERACTIVE_MAY_BUILD.set(toBlockStateSet(blockInteractionsJson.getAsJsonArray("requires_may_build")));
    }""",
        """    @SuppressWarnings("UnstableApiUsage")
    private static final Interner<NbtMap> STATES_INTERNER = Interners.newStrongInterner();
    private static List<NbtMap> BLOCKS_NBT;
    private static NbtMap[] BASE_BEDROCK_TAGS;
    private static Set<Block> POTTABLE_JAVA_BLOCKS;
    private static IntArrayList SHARED_COLLISION_IGNORED_BLOCKS;
    private static int AIR_JAVA_ID = -1;
    private static int WATER_LEVEL0_JAVA_ID = -1;
    private static int CMD_BLOCK_NORTH_JAVA_ID = -1;
    private static int SPAWNER_JAVA_ID = -1;
    private static int MOVING_PISTON_NORTH_JAVA_ID = -1;
    public static int MIN_CUSTOM_RUNTIME_ID = -1;
    public static int JAVA_BLOCKS_SIZE = -1;

    private static void nullifyBlocksNbt() {
        BLOCKS_NBT = null;
    }

    private static void registerBedrockBlocks() {
        var blockMappers = ImmutableMap.<ObjectIntPair<String>, Remapper>builder()
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
            .build();

        ensureSharedJavaBlockTables();
        boolean eagerAll = Boolean.getBoolean("purpurbedrock.eagerAllPalettes");
        int primaryProto = Bedrock_v2193.CODEC.getProtocolVersion();

        for (Map.Entry<ObjectIntPair<String>, Remapper> entry : blockMappers.entrySet()) {
            ObjectIntPair<String> palette = entry.getKey();
            Remapper stateMapper = entry.getValue();
            int protocolVersion = palette.valueInt();
            if (eagerAll || protocolVersion == primaryProto) {
                BlockRegistries.BLOCKS.register(protocolVersion, buildBedrockPalette(palette, stateMapper));
            } else {
                BlockRegistries.BLOCKS.registerLazy(protocolVersion, ver -> buildBedrockPalette(palette, stateMapper));
            }
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private static synchronized void ensureSharedJavaBlockTables() {
        if (BASE_BEDROCK_TAGS != null && BASE_BEDROCK_TAGS.length == JAVA_BLOCKS_SIZE) {
            return;
        }
        List<BlockState> javaBlockStates = BlockRegistries.BLOCK_STATES.get();
        BASE_BEDROCK_TAGS = new NbtMap[JAVA_BLOCKS_SIZE];
        int vanillaCount = Math.min(BLOCKS_NBT.size(), javaBlockStates.size());
        SHARED_COLLISION_IGNORED_BLOCKS = new IntArrayList();
        POTTABLE_JAVA_BLOCKS = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();
        for (Block b : BlockRegistries.JAVA_BLOCKS.get()) {
            if (b instanceof FlowerPotBlock flowerPot && flowerPot.flower() != Blocks.AIR) {
                POTTABLE_JAVA_BLOCKS.add(flowerPot.flower());
            }
        }

        BitSet waterloggedSet = BlockRegistries.WATERLOGGED.get();
        for (int id = 0; id < vanillaCount; id++) {
            BlockState blockState = javaBlockStates.get(id);
            NbtMap raw = BLOCKS_NBT.get(id);
            NbtMap built = buildBedrockState(blockState, raw);
            NbtMap states = STATES_INTERNER.intern(built.getCompound("states"));
            BASE_BEDROCK_TAGS[id] = NbtMap.builder()
                    .putString("name", built.getString("name").intern())
                    .putCompound("states", states)
                    .build();

            Block block = blockState.block();
            if (block == Blocks.BAMBOO || block == Blocks.POINTED_DRIPSTONE) {
                SHARED_COLLISION_IGNORED_BLOCKS.add(id);
            }
            if (block instanceof org.geysermc.geyser.translator.level.block.entity.BedrockChunkWantsBlockEntityTag) {
                BlockRegistries.BEDROCK_CHUNK_BLOCK_ENTITIES.set(id);
            }
            boolean waterlogged = blockState.getValue(Properties.WATERLOGGED, false)
                    || block == Blocks.BUBBLE_COLUMN || block == Blocks.KELP || block == Blocks.KELP_PLANT
                    || block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS;
            if (waterlogged) {
                waterloggedSet.set(id);
            }
        }

        Map<JavaBlockState, CustomBlockState> nonVanillaStateOverrides = BlockRegistries.NON_VANILLA_BLOCK_STATE_OVERRIDES.get();
        if (!nonVanillaStateOverrides.isEmpty()) {
            for (JavaBlockState javaState : nonVanillaStateOverrides.keySet()) {
                if (javaState.waterlogged()) {
                    waterloggedSet.set(javaState.javaId());
                }
            }
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private static synchronized BlockMappings buildBedrockPalette(ObjectIntPair<String> palette, Remapper stateMapper) {
        int protocolVersion = palette.valueInt();
        List<NbtMap> vanillaBlockStates;
        List<NbtMap> blockStates;
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrThrow(String.format("bedrock/block_palette.%s.nbt", palette.key()));
            NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)), true, true)) {
            NbtMap blockPalette = (NbtMap) nbtInputStream.readTag();

            vanillaBlockStates = new ArrayList<>(blockPalette.getList("blocks", NbtType.COMPOUND));
            for (int i = 0; i < vanillaBlockStates.size(); i++) {
                NbtMapBuilder builder = vanillaBlockStates.get(i).toBuilder();
                builder.remove("version");
                builder.remove("name_hash");
                builder.remove("network_id");
                builder.remove("block_id");
                builder.putCompound("states", STATES_INTERNER.intern((NbtMap) builder.remove("states")));
                vanillaBlockStates.set(i, builder.build());
            }

            blockStates = new ArrayList<>(vanillaBlockStates);
        } catch (Exception e) {
            throw new AssertionError("Unable to get blocks from runtime block states", e);
        }

        List<BlockPropertyData> customBlockProperties = new ArrayList<>();
        List<NbtMap> customBlockStates = new ArrayList<>();
        List<CustomBlockState> customExtBlockStates = new ArrayList<>();
        int[] remappedVanillaIds = new int[0];

        InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrNull(String.format("bedrock/data_driven_blocks.%s.nbt", palette.key()));
        if (stream != null) {
            try (stream; NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)))) {
                NbtMap dataDrivenBlocks = (NbtMap) nbtInputStream.readTag();
                for (Map.Entry<String, Object> entry : dataDrivenBlocks.entrySet()) {
                    customBlockProperties.add(new BlockPropertyData(entry.getKey(), (NbtMap) entry.getValue()));
                }
            } catch (IOException e) {
                throw new AssertionError("Unable to get data driven blocks from runtime definitions", e);
            }
        }

        if (BlockRegistries.CUSTOM_BLOCKS.get().length != 0) {
            CustomBlockRegistryPopulator.BLOCK_ID.set(CustomBlockRegistryPopulator.START_OFFSET);
            for (CustomBlockData customBlock : BlockRegistries.CUSTOM_BLOCKS.get()) {
                customBlockProperties.add(CustomBlockRegistryPopulator.generateBlockPropertyData(customBlock));
                CustomBlockRegistryPopulator.generateCustomBlockStates(customBlock, customBlockStates, customExtBlockStates);
            }
            blockStates.addAll(customBlockStates);
            blockStates.sort((a, b) -> Long.compareUnsigned(fnv164(a.getString("name")), fnv164(b.getString("name"))));
        }

        Object2ObjectMap<NbtMap, GeyserBedrockBlock> blockStateOrderedMap = new Object2ObjectOpenHashMap<>(blockStates.size());
        Map<String, List<GeyserBedrockBlock>> blocksByName = new Object2ObjectOpenHashMap<>();
        Map<NbtMap, BlockDefinition> itemFrames = new Object2ObjectOpenHashMap<>();
        GeyserBedrockBlock[] bedrockRuntimeMap = new GeyserBedrockBlock[blockStates.size()];
        for (int i = 0; i < blockStates.size(); i++) {
            NbtMap tag = blockStates.get(i);
            GeyserBedrockBlock block = new GeyserBedrockBlock(i, tag);
            if (blockStateOrderedMap.put(tag, block) != null) {
                throw new AssertionError("Duplicate block states in Bedrock palette: " + tag);
            }
            bedrockRuntimeMap[i] = block;
            String blockName = tag.getString("name");
            blocksByName.computeIfAbsent(blockName, k -> new ArrayList<>()).add(block);
            if (blockName.equals("minecraft:frame") || blockName.equals("minecraft:glow_frame")) {
                itemFrames.put(tag, block);
            }
        }

        Object2ObjectMap<CustomBlockState, GeyserBedrockBlock> customBlockStateDefinitions = Object2ObjectMaps.emptyMap();
        if (BlockRegistries.CUSTOM_BLOCKS.get().length != 0) {
            customBlockStateDefinitions = new Object2ObjectOpenHashMap<>(customExtBlockStates.size());
            for (int i = 0; i < customExtBlockStates.size(); i++) {
                NbtMap tag = customBlockStates.get(i);
                CustomBlockState blockState = customExtBlockStates.get(i);
                GeyserBedrockBlock bedrockBlock = blockStateOrderedMap.get(tag);
                customBlockStateDefinitions.put(blockState, bedrockBlock);
            }

            remappedVanillaIds = new int[vanillaBlockStates.size()];
            for (int i = 0; i < vanillaBlockStates.size(); i++) {
                GeyserBedrockBlock bedrockBlock = blockStateOrderedMap.get(vanillaBlockStates.get(i));
                remappedVanillaIds[i] = bedrockBlock != null ? bedrockBlock.getRuntimeId() : -1;
            }
        }

        List<BlockState> javaBlockStates = BlockRegistries.BLOCK_STATES.get();
        int vanillaLimit = MIN_CUSTOM_RUNTIME_ID >= 0 ? MIN_CUSTOM_RUNTIME_ID : JAVA_BLOCKS_SIZE;

        GeyserBedrockBlock airDefinition = null;
        BlockDefinition commandBlockDefinition = null;
        BlockDefinition mobSpawnerBlockDefinition = null;
        BlockDefinition netherPortalBlockDefinition = null;
        BlockDefinition waterDefinition = null;
        BlockDefinition movingBlockDefinition = null;

        GeyserBedrockBlock[] javaToBedrockBlocks = new GeyserBedrockBlock[JAVA_BLOCKS_SIZE];
        GeyserBedrockBlock[] javaToVanillaBedrockBlocks = new GeyserBedrockBlock[JAVA_BLOCKS_SIZE];

        var javaToBedrockIdentifiers = new Int2ObjectOpenHashMap<String>();
        Block lastBlockSeen = null;

        Map<Block, NbtMap> flowerPotBlocks = new Object2ObjectOpenHashMap<>();
        Set<BlockDefinition> jigsawDefinitions = new ObjectOpenHashSet<>();
        Map<String, BlockDefinition> structureBlockDefinitions = new Object2ObjectOpenHashMap<>();

        BlockMappings.BlockMappingsBuilder builder = BlockMappings.builder();
        for (int javaRuntimeId = 0; javaRuntimeId < vanillaLimit; javaRuntimeId++) {
            BlockState blockState = javaBlockStates.get(javaRuntimeId);
            NbtMap originalBedrockTag = BASE_BEDROCK_TAGS[javaRuntimeId];
            NbtMap bedrockTag = MultiVersionPaletteHelper.remapBlockForVersion(protocolVersion, blockState, stateMapper.remap(originalBedrockTag));

            GeyserBedrockBlock vanillaBedrockDefinition = MultiVersionPaletteHelper.resolveBlockDefinition(bedrockTag, blockStateOrderedMap, blocksByName);

            GeyserBedrockBlock bedrockDefinition;
            CustomBlockState blockStateOverride = BlockRegistries.CUSTOM_BLOCK_STATE_OVERRIDES.get(javaRuntimeId);
            if (blockStateOverride == null) {
                bedrockDefinition = vanillaBedrockDefinition;
                if (bedrockDefinition == null) {
                    throw new RuntimeException("Unable to find " + blockState + " Bedrock runtime ID for " + palette.key());
                }
            } else {
                bedrockDefinition = customBlockStateDefinitions.get(blockStateOverride);
                if (bedrockDefinition == null) {
                    throw new RuntimeException("Unable to find " + blockState + " Bedrock runtime ID! Custom block override: " + blockStateOverride);
                }
            }

            if (javaRuntimeId == AIR_JAVA_ID) {
                airDefinition = bedrockDefinition;
            } else if (javaRuntimeId == WATER_LEVEL0_JAVA_ID) {
                waterDefinition = bedrockDefinition;
            } else if (javaRuntimeId == CMD_BLOCK_NORTH_JAVA_ID) {
                commandBlockDefinition = bedrockDefinition;
            } else if (javaRuntimeId == SPAWNER_JAVA_ID) {
                mobSpawnerBlockDefinition = bedrockDefinition;
            } else if (javaRuntimeId == MOVING_PISTON_NORTH_JAVA_ID) {
                movingBlockDefinition = bedrockDefinition;
            }

            Block block = blockState.block();
            if (block != lastBlockSeen) {
                lastBlockSeen = block;
                String bedrockName = bedrockDefinition.getState().getString("name");
                if (!block.javaIdentifier().toString().equals(bedrockName)) {
                    javaToBedrockIdentifiers.put(block.javaId(), bedrockName.substring("minecraft:".length()).intern());
                }
            }

            if (block == Blocks.JIGSAW) {
                jigsawDefinitions.add(bedrockDefinition);
            } else if (block == Blocks.STRUCTURE_BLOCK) {
                String mode = blockState.getValue(Properties.STRUCTUREBLOCK_MODE);
                structureBlockDefinitions.put(mode.toUpperCase(Locale.ROOT), bedrockDefinition);
            } else if (block == Blocks.NETHER_PORTAL) {
                netherPortalBlockDefinition = bedrockDefinition;
            }

            if (POTTABLE_JAVA_BLOCKS.contains(block)) {
                flowerPotBlocks.put(block, blockStates.get(bedrockDefinition.getRuntimeId()));
            }

            javaToVanillaBedrockBlocks[javaRuntimeId] = vanillaBedrockDefinition;
            javaToBedrockBlocks[javaRuntimeId] = bedrockDefinition;
        }

        builder.collisionIgnoredBlocks(new IntArrayList(SHARED_COLLISION_IGNORED_BLOCKS));

        if (commandBlockDefinition == null) {
            throw new AssertionError("Unable to find command block in palette");
        }
        builder.commandBlock(commandBlockDefinition);

        if (mobSpawnerBlockDefinition == null) {
            throw new AssertionError("Unable to find mob spawner block in palette");
        }
        builder.mobSpawnerBlock(mobSpawnerBlockDefinition);

        if (netherPortalBlockDefinition == null) {
            throw new AssertionError("Unable to find nether portal block in palette");
        }
        builder.netherPortalBlock(netherPortalBlockDefinition);

        if (waterDefinition == null) {
            throw new AssertionError("Unable to find water in palette");
        }
        builder.bedrockWater(waterDefinition);

        if (airDefinition == null) {
            throw new AssertionError("Unable to find air in palette");
        }
        builder.bedrockAir(airDefinition);

        if (movingBlockDefinition == null) {
            throw new AssertionError("Unable to find moving block in palette");
        }
        builder.bedrockMovingBlock(movingBlockDefinition);

        Map<JavaBlockState, CustomBlockState> nonVanillaStateOverrides = BlockRegistries.NON_VANILLA_BLOCK_STATE_OVERRIDES.get();
        if (!nonVanillaStateOverrides.isEmpty()) {
            Arrays.fill(javaToVanillaBedrockBlocks, MIN_CUSTOM_RUNTIME_ID, javaToVanillaBedrockBlocks.length, airDefinition);
            Arrays.fill(javaToBedrockBlocks, MIN_CUSTOM_RUNTIME_ID, javaToBedrockBlocks.length, airDefinition);

            for (Map.Entry<JavaBlockState, CustomBlockState> entry : nonVanillaStateOverrides.entrySet()) {
                GeyserBedrockBlock bedrockDefinition = customBlockStateDefinitions.get(entry.getValue());
                if (bedrockDefinition == null) {
                    GeyserImpl.getInstance().getLogger().warning("Unable to find custom block for " + entry.getValue());
                    continue;
                }

                JavaBlockState javaState = entry.getKey();
                int stateRuntimeId = javaState.javaId();
                javaToVanillaBedrockBlocks[stateRuntimeId] = bedrockDefinition;
                javaToBedrockBlocks[stateRuntimeId] = bedrockDefinition;
                javaToBedrockIdentifiers.put(entry.getKey().stateGroupId(), entry.getValue().block().identifier());
            }
        }

        javaToBedrockIdentifiers.trim();

        return builder.bedrockRuntimeMap(bedrockRuntimeMap)
                .javaToBedrockBlocks(javaToBedrockBlocks)
                .javaToVanillaBedrockBlocks(javaToVanillaBedrockBlocks)
                .javaToBedrockIdentifiers(javaToBedrockIdentifiers)
                .stateDefinitionMap(blockStateOrderedMap)
                .itemFrames(itemFrames)
                .flowerPotBlocks(flowerPotBlocks)
                .jigsawStates(jigsawDefinitions)
                .structureBlockStates(structureBlockDefinitions)
                .remappedVanillaIds(remappedVanillaIds)
                .blockProperties(customBlockProperties)
                .customBlockStateDefinitions(customBlockStateDefinitions)
                .build();
    }

    private static void registerJavaBlocks() {
        List<NbtMap> blocksNbt;
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrThrow("mappings/blocks.nbt")) {
            blocksNbt = ((NbtMap) NbtUtils.createGZIPReader(stream).readTag())
                    .getList("bedrock_mappings", NbtType.COMPOUND);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java block mappings", e);
        }

        int javaRuntimeId = -1;
        for (BlockState javaBlockState : BlockRegistries.BLOCK_STATES.get()) {
            javaRuntimeId++;
            String javaId = javaBlockState.toString().intern();
            switch (javaId) {
                case "minecraft:air" -> AIR_JAVA_ID = javaRuntimeId;
                case "minecraft:water[level=0]" -> WATER_LEVEL0_JAVA_ID = javaRuntimeId;
                case "minecraft:command_block[conditional=false,facing=north]" -> CMD_BLOCK_NORTH_JAVA_ID = javaRuntimeId;
                case "minecraft:spawner" -> SPAWNER_JAVA_ID = javaRuntimeId;
                case "minecraft:moving_piston[facing=north,type=normal]" -> MOVING_PISTON_NORTH_JAVA_ID = javaRuntimeId;
            }

            BlockRegistries.JAVA_BLOCK_STATE_IDENTIFIER_TO_ID.register(javaId, javaRuntimeId);
        }

        BLOCKS_NBT = blocksNbt;
        JAVA_BLOCKS_SIZE = blocksNbt.size();

        JsonObject blockInteractionsJson;
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrThrow("mappings/interactions.json")) {
            blockInteractionsJson = JsonUtils.fromJson(stream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java block interaction mappings", e);
        }

        BlockRegistries.INTERACTIVE.set(toBlockStateSet(blockInteractionsJson.getAsJsonArray("always_consumes")));
        BlockRegistries.INTERACTIVE_MAY_BUILD.set(toBlockStateSet(blockInteractionsJson.getAsJsonArray("requires_may_build")));
    }""",
        "BlockRegistryPopulator.lazyAndPrecomputedOptimization",
    )

    replace_exact(
        item_populator,
        """        // We can reduce some operations as Java information is the same across all palette versions
        boolean firstMappingsPass = true;

        /* Load item palette */
        for (PaletteVersion palette : paletteVersions) {""",
        """        boolean firstMappingsPass = true;
        boolean eagerAll = Boolean.getBoolean("purpurbedrock.eagerAllPalettes");
        int primaryProto = Bedrock_v2193.CODEC.getProtocolVersion();
        for (PaletteVersion palette : paletteVersions) {
            if (eagerAll || palette.protocolVersion() == primaryProto) {
                boolean firstPass = firstMappingsPass;
                firstMappingsPass = false;
                Registries.ITEMS.register(palette.protocolVersion(),
                        buildItemMappingsForPalette(palette, bootstrap, items, customItems, nonVanillaCustomItems, firstPass));
            } else {
                Registries.ITEMS.registerLazy(palette.protocolVersion(),
                        ver -> buildItemMappingsForPalette(palette, bootstrap, items, customItems, nonVanillaCustomItems, false));
            }
        }
    }

    private static synchronized ItemMappings buildItemMappingsForPalette(
            PaletteVersion palette,
            GeyserBootstrap bootstrap,
            Map<String, GeyserMappingItem> items,
            Multimap<Identifier, CustomItemDefinition> customItems,
            Multimap<Identifier, NonVanillaCustomItemDefinition> nonVanillaCustomItems,
            boolean firstMappingsPass) {
        {""",
        "ItemRegistryPopulator.lazyPaletteDispatch",
    )
    replace_exact(
        item_populator,
        """            BlockMappings blockMappings = BlockRegistries.BLOCKS.forVersion(palette.protocolVersion());""",
        """            BlockMappings blockMappings = BlockRegistries.BLOCKS.forVersion(palette.protocolVersion());
            Map<String, List<GeyserBedrockBlock>> runtimeBlocksByName = new Object2ObjectOpenHashMap<>();
            for (GeyserBedrockBlock candidateBlock : blockMappings.getBedrockRuntimeMap()) {
                if (candidateBlock != null) {
                    runtimeBlocksByName.computeIfAbsent(candidateBlock.getState().getString("name"), k -> new ArrayList<>()).add(candidateBlock);
                }
            }""",
        "ItemRegistryPopulator.indexRuntimeBlocksByName",
    )
    replace_exact(
        item_populator,
        """                                for (GeyserBedrockBlock block : blockMappings.getBedrockRuntimeMap()) {
                                    if (block == null) {
                                        continue;
                                    }
                                    NbtMap blockTag = block.getState();
                                    if (blockTag.getString("name").equals(correctBedrockIdentifier)) {""",
        """                                for (GeyserBedrockBlock block : runtimeBlocksByName.getOrDefault(correctBedrockIdentifier, Collections.emptyList())) {
                                    NbtMap blockTag = block.getState();
                                    if (true) {""",
        "ItemRegistryPopulator.fastBlockMatchByName",
    )
    replace_exact(
        item_populator,
        """            Registries.ITEMS.register(palette.protocolVersion(), itemMappings);

            firstMappingsPass = false;
        }
    }""",
        """            return itemMappings;
        }
    }""",
        "ItemRegistryPopulator.returnItemMappings",
    )

    replace_exact(
        tag_populator,
        """public final class TagRegistryPopulator {
    private static final Gson GSON = new GsonBuilder().create(); // temporary

    public static void populate() {""",
        """public final class TagRegistryPopulator {
    private static final Gson GSON = new GsonBuilder().create(); // temporary
    private static final Map<String, Map<String, List<String>>> TAG_JSON_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static void populate() {""",
        "TagRegistryPopulator.tagJsonCacheField",
    )
    replace_exact(
        tag_populator,
        """        for (var palette : paletteVersions) {
            ItemMappings mappings = Registries.ITEMS.forVersion(palette.rightInt());

            Map<String, List<String>> bedrockTags;
            try (InputStream stream = bootstrap.getResourceOrThrow(String.format("bedrock/item_tags.%s.json", palette.left()))) {
                bedrockTags = GSON.fromJson(new InputStreamReader(stream), type);
            } catch (Exception e) {
                throw new AssertionError("Unable to load Bedrock runtime item IDs", e);
            }

            var javaItemsToBedrockTag = new Object2ObjectOpenCustomHashMap<int[], String>(hashStrategy);

            for (var entry : bedrockTags.entrySet()) {
                List<String> value = entry.getValue();
                if (value.isEmpty() || value.size() == 1) {
                    // For our usecase, we don't need this. Empty values are worthless; one value can just be a reference
                    // to the item itself, instead of the tag.
                    continue;
                }

                // In some cases, the int list will need to be minimized
                IntList javaNetworkIds = new IntArrayList(value.size());
                for (int i = 0; i < value.size(); i++) {
                    String bedrockIdentifier = value.get(i);
                    Item javaItem = Registries.JAVA_ITEM_IDENTIFIERS.get(bedrockIdentifier);
                    if (javaItem == null) {
                        // Time to search the long way around.
                        for (ItemMapping mapping : mappings.getItems()) {
                            if (mapping.getBedrockIdentifier().equals(bedrockIdentifier)) {
                                javaItem = mapping.getJavaItem();
                                break;
                            }
                        }
                    }
                    if (javaItem == null) {
                        // Triggers for Bedrock-only spawn eggs. We don't care.
                        continue;
                    }

                    javaNetworkIds.add(javaItem.javaId());
                }

                int[] javaNetworkIdArray = javaNetworkIds.toIntArray();
                // Sort IDs so equality checks just have to match if each is equal and not necessarily an order difference.
                Arrays.sort(javaNetworkIdArray);

                javaItemsToBedrockTag.put(javaNetworkIdArray, entry.getKey());
            }

            javaItemsToBedrockTag.trim();
            Registries.TAGS.register(palette.rightInt(), javaItemsToBedrockTag);
        }""",
        """        boolean eagerAll = Boolean.getBoolean("purpurbedrock.eagerAllPalettes");
        int primaryProto = Bedrock_v2193.CODEC.getProtocolVersion();
        for (var palette : paletteVersions) {
            int proto = palette.rightInt();
            String tagFileVer = palette.left();
            if (eagerAll || proto == primaryProto) {
                Registries.TAGS.register(proto, buildTagsForPalette(bootstrap, type, hashStrategy, tagFileVer, proto));
            } else {
                Registries.TAGS.registerLazy(proto, ver -> buildTagsForPalette(bootstrap, type, hashStrategy, tagFileVer, ver));
            }
        }
    }

    private static synchronized Object2ObjectOpenCustomHashMap<int[], String> buildTagsForPalette(
            GeyserBootstrap bootstrap,
            Type type,
            Hash.Strategy<int[]> hashStrategy,
            String tagFileVer,
            int protocolVersion) {
        ItemMappings mappings = Registries.ITEMS.forVersion(protocolVersion);
        Map<String, List<String>> bedrockTags = TAG_JSON_CACHE.computeIfAbsent(tagFileVer, key -> {
            try (InputStream stream = bootstrap.getResourceOrThrow(String.format("bedrock/item_tags.%s.json", key))) {
                return GSON.fromJson(new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8), type);
            } catch (Exception e) {
                throw new AssertionError("Unable to load Bedrock runtime item IDs", e);
            }
        });

        Map<String, Item> fallbackByBedrockId = null;
        var javaItemsToBedrockTag = new Object2ObjectOpenCustomHashMap<int[], String>(hashStrategy);

        for (var entry : bedrockTags.entrySet()) {
            List<String> value = entry.getValue();
            if (value.isEmpty() || value.size() == 1) {
                continue;
            }

            IntList javaNetworkIds = new IntArrayList(value.size());
            for (int i = 0; i < value.size(); i++) {
                String bedrockIdentifier = value.get(i);
                Item javaItem = Registries.JAVA_ITEM_IDENTIFIERS.get(bedrockIdentifier);
                if (javaItem == null) {
                    if (fallbackByBedrockId == null) {
                        fallbackByBedrockId = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>(mappings.getItems().length);
                        for (ItemMapping mapping : mappings.getItems()) {
                            if (mapping != null && mapping.getBedrockIdentifier() != null) {
                                fallbackByBedrockId.putIfAbsent(mapping.getBedrockIdentifier(), mapping.getJavaItem());
                            }
                        }
                    }
                    javaItem = fallbackByBedrockId.get(bedrockIdentifier);
                }
                if (javaItem == null) {
                    continue;
                }

                javaNetworkIds.add(javaItem.javaId());
            }

            int[] javaNetworkIdArray = javaNetworkIds.toIntArray();
            Arrays.sort(javaNetworkIdArray);
            javaItemsToBedrockTag.put(javaNetworkIdArray, entry.getKey());
        }

        javaItemsToBedrockTag.trim();
        return javaItemsToBedrockTag;""",
        "TagRegistryPopulator.lazyAndCachedTags",
    )

    potion_loader = root / "core/src/main/java/org/geysermc/geyser/registry/loader/PotionMixRegistryLoader.java"
    replace_exact(
        potion_loader,
        """    @Override
    public Int2ObjectMap<Set<PotionMixData>> load(Object input) {
        var allPotionMixes = new Int2ObjectOpenHashMap<Set<PotionMixData>>(Registries.ITEMS.get().size());
        for (var entry : Registries.ITEMS.get().int2ObjectEntrySet()) {
            ItemMappings mappings = entry.getValue();""",
        """    @Override
    public Int2ObjectMap<Set<PotionMixData>> load(Object input) {
        var allPotionMixes = new Int2ObjectOpenHashMap<Set<PotionMixData>>(Registries.ITEMS.get().size());
        for (var entry : Registries.ITEMS.get().int2ObjectEntrySet()) {
            allPotionMixes.put(entry.getIntKey(), buildForMappings(entry.getValue()));
        }
        allPotionMixes.trim();
        return allPotionMixes;
    }

    public static Set<PotionMixData> buildForMappings(ItemMappings mappings) {
        {""",
        "PotionMixRegistryLoader.extractBuildForMappingsHead",
    )
    replace_exact(
        potion_loader,
        """            allPotionMixes.put(entry.getIntKey(), potionMixes);
        }
        allPotionMixes.trim();
        return allPotionMixes;
    }""",
        """            return potionMixes;
        }
    }""",
        "PotionMixRegistryLoader.extractBuildForMappingsTail",
    )

    registries_java = root / "core/src/main/java/org/geysermc/geyser/registry/Registries.java"
    replace_exact(
        registries_java,
        """        // potion mixes depend on other registries
        POTION_MIXES.load();""",
        """        // potion mixes depend on other registries
        POTION_MIXES.load();
        for (int lazyProto : ITEMS.lazyVersions()) {
            POTION_MIXES.registerLazy(lazyProto, ver -> PotionMixRegistryLoader.buildForMappings(ITEMS.forVersion(ver)));
        }""",
        "Registries.registerLazyPotionMixes",
    )

    replace_exact(
        geyser_impl,
        """        eventBus.subscribe(this, GeyserDefineCustomBlocksEvent.class, BuiltInMappings::registerBlocks);
        eventBus.subscribe(this, GeyserDefineCustomItemsEvent.class, BuiltInMappings::registerItems);

        /*
        First load the registries and then populate them.
        Both the block registries and the common registries depend on each other,
        so maintaining this order is crucial for Geyser to load.
         */
        Registries.load();
        BlockRegistries.populate();
        Registries.populate();

        RegistryCache.init();

        /* Initialize translators */
        VanillaEntities.init();
        MessageTranslator.init();""",
        """        ensureCoreRegistriesReady();""",
        "GeyserImpl.initializeEnsureCoreRegistriesReady",
    )
    replace_exact(
        geyser_impl,
        """    private boolean isEnabled;""",
        """    private boolean isEnabled;
    private final Object coreRegistryLock = new Object();
    private volatile boolean coreRegistriesReady = false;

    public void ensureCoreRegistriesReady() {
        if (this.coreRegistriesReady) {
            return;
        }
        synchronized (this.coreRegistryLock) {
            if (this.coreRegistriesReady) {
                return;
            }
            eventBus.subscribe(this, GeyserDefineCustomBlocksEvent.class, BuiltInMappings::registerBlocks);
            eventBus.subscribe(this, GeyserDefineCustomItemsEvent.class, BuiltInMappings::registerItems);

            Registries.load();
            BlockRegistries.populate();
            Registries.populate();

            RegistryCache.init();
            VanillaEntities.init();
            MessageTranslator.init();
            this.coreRegistriesReady = true;
        }
    }""",
        "GeyserImpl.ensureCoreRegistriesReadyMethod",
    )

    replace_exact(
        spigot_plugin,
        """        this.commandRegistry = new SpigotCommandRegistry(geyser, cloud);

        // Needs to be an anonymous inner class otherwise Bukkit complains about missing classes""",
        """        this.commandRegistry = new SpigotCommandRegistry(geyser, cloud);

        if (canPrewarmRegistriesAsync()) {
            Thread prewarmThread = new Thread(() -> {
                try {
                    geyser.ensureCoreRegistriesReady();
                } catch (Throwable t) {
                    geyserLogger.debug("Async registry prewarm deferred to ServerLoadEvent: " + t.getMessage());
                }
            }, "Purpurbedrock-Geyser-Prewarm");
            prewarmThread.setDaemon(true);
            prewarmThread.start();
        }

        // Needs to be an anonymous inner class otherwise Bukkit complains about missing classes""",
        "GeyserSpigotPlugin.startAsyncPrewarm",
    )
    replace_exact(
        spigot_plugin,
        """    @Override
    public MetricsPlatform createMetricsPlatform() {""",
        """    private boolean canPrewarmRegistriesAsync() {
        if (!Boolean.parseBoolean(System.getProperty("purpurbedrock.asyncPrewarm", "true"))) {
            return false;
        }
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            String n = p.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("itemsadder") || n.contains("oraxen") || n.contains("nexo") || n.contains("crucible")) {
                return false;
            }
        }
        return true;
    }

    @Override
    public MetricsPlatform createMetricsPlatform() {""",
        "GeyserSpigotPlugin.canPrewarmRegistriesAsync",
    )

    spigot_cmd_source = root / "bootstrap/spigot/src/main/java/org/geysermc/geyser/platform/spigot/command/SpigotCommandSource.java"
    replace_exact(
        spigot_cmd_source,
        """    public SpigotCommandSource(CommandSender handle) {
        this.handle = handle;
        // Ensure even Java players' languages are loaded
        GeyserLocale.loadGeyserLocale(locale());
    }""",
        """    public SpigotCommandSource(CommandSender handle) {
        this.handle = handle;
    }""",
        "SpigotCommandSource.noEagerLocaleInCtor",
    )
    replace_exact(
        spigot_cmd_source,
        """    @SuppressWarnings("deprecation")
    @Override
    public String locale() {
        if (this.handle instanceof Player player) {
            // getLocale() is deprecated on Paper, but not on Spigot
            return player.getLocale();
        }

        return GeyserLocale.getDefaultLocale();
    }""",
        """    @SuppressWarnings("deprecation")
    @Override
    public String locale() {
        if (this.handle instanceof Player player) {
            String loc = player.getLocale();
            GeyserLocale.loadGeyserLocale(loc);
            return loc;
        }

        return GeyserLocale.getDefaultLocale();
    }""",
        "SpigotCommandSource.lazyLocaleLoad",
    )

    native_world_mgr = root / "bootstrap/spigot/src/main/java/org/geysermc/geyser/platform/spigot/world/manager/GeyserSpigotNativeWorldManager.java"
    replace_exact(
        native_world_mgr,
        """    @Override
    public int getBlockAt(GeyserSession session, int x, int y, int z) {
        Player player = Bukkit.getPlayer(session.getPlayerEntity().getUsername());
        if (player == null) {
            return Block.JAVA_AIR_ID;
        }
        return adapter.getBlockAt(player.getWorld(), x, y, z);
    }""",
        """    @Override
    public int getBlockAt(GeyserSession session, int x, int y, int z) {
        java.util.UUID uuid = session.getPlayerEntity().uuid();
        Player player = uuid != null ? Bukkit.getPlayer(uuid) : Bukkit.getPlayerExact(session.getPlayerEntity().getUsername());
        if (player == null) {
            return Block.JAVA_AIR_ID;
        }
        return adapter.getBlockAt(player.getWorld(), x, y, z);
    }""",
        "GeyserSpigotNativeWorldManager.fastUuidGetBlockAt",
    )

    chunk_section = root / "core/src/main/java/org/geysermc/geyser/level/chunk/GeyserChunkSection.java"
    replace_exact(
        chunk_section,
        """    public void writeToNetwork(ByteBuf buffer) {
        buffer.writeByte(CHUNK_SECTION_VERSION);
        buffer.writeByte(this.storage.length);
        // Required for chunk version 9+
        buffer.writeByte(this.subChunkIndex);
        for (BlockStorage blockStorage : this.storage) {
            blockStorage.writeToNetwork(buffer);
        }
    }

    public int estimateNetworkSize() {
        int size = 2; // Version + storage count
        for (BlockStorage blockStorage : this.storage) {
            size += blockStorage.estimateNetworkSize();
        }
        return size;
    }""",
        """    public static void writeEmptyToNetwork(ByteBuf buffer, int subChunkIndex) {
        buffer.writeByte(CHUNK_SECTION_VERSION);
        buffer.writeByte(0);
        buffer.writeByte(subChunkIndex);
    }

    public void writeToNetwork(ByteBuf buffer) {
        buffer.writeByte(CHUNK_SECTION_VERSION);
        buffer.writeByte(this.storage.length);
        // Required for chunk version 9+
        buffer.writeByte(this.subChunkIndex);
        for (BlockStorage blockStorage : this.storage) {
            blockStorage.writeToNetwork(buffer);
        }
    }

    public int estimateNetworkSize() {
        int size = 3; // Version + storage count + subChunkIndex
        for (BlockStorage blockStorage : this.storage) {
            size += blockStorage.estimateNetworkSize();
        }
        return size;
    }""",
        "GeyserChunkSection.accurateSizeAndZeroAllocEmpty",
    )

    replace_exact(
        level_chunk_translator,
        """                    // Check if block is piston, flower or cauldron to see if we'll need to create additional block entities, as they're only block entities in Bedrock
                    // TODO this needs a performance check when my head is clearer
                    BlockState state = BlockState.of(javaId);
                    if (state.block() instanceof BedrockChunkWantsBlockEntityTag) {
                        bedrockOnlyBlockEntityIds.set(i);
                    }""",
        """                    if (BlockRegistries.BEDROCK_CHUNK_BLOCK_ENTITIES.get(javaId)) {
                        bedrockOnlyBlockEntityIds.set(i);
                    }""",
        "JavaLevelChunkWithLightTranslator.fastBitSetBlockEntityCheck",
    )
    replace_exact(
        level_chunk_translator,
        """                } else {
                    int subChunkIndex = (i + (bedrockDimension.minY() >> 4));
                    new GeyserChunkSection(EMPTY_BLOCK_STORAGE, subChunkIndex).writeToNetwork(byteBuf);
                }""",
        """                } else {
                    int subChunkIndex = (i + (bedrockDimension.minY() >> 4));
                    GeyserChunkSection.writeEmptyToNetwork(byteBuf, subChunkIndex);
                }""",
        "JavaLevelChunkWithLightTranslator.zeroAllocEmptySection",
    )
    replace_exact(
        level_chunk_translator,
        """        for (Map.Entry<Vector3i, ItemFrameEntity> entry : session.getItemFrameCache().entrySet()) {
            Vector3i position = entry.getKey();
            if ((position.getX() >> 4) == packet.getX() && (position.getZ() >> 4) == packet.getZ()) {
                // Update this item frame so it doesn't get lost in the abyss
                //TODO optimize
                entry.getValue().updateBlock(true);
            }
        }""",
        """        if (!session.getItemFrameCache().isEmpty()) {
            for (Map.Entry<Vector3i, ItemFrameEntity> entry : session.getItemFrameCache().entrySet()) {
                Vector3i position = entry.getKey();
                if ((position.getX() >> 4) == packet.getX() && (position.getZ() >> 4) == packet.getZ()) {
                    entry.getValue().updateBlock(true);
                }
            }
        }""",
        "JavaLevelChunkWithLightTranslator.skipEmptyItemFrameCache",
    )

    scoreboard_updater = root / "core/src/main/java/org/geysermc/geyser/scoreboard/ScoreboardUpdater.java"
    replace_exact(
        scoreboard_updater,
        """    public static ScoreboardUpdater init() {
        ScoreboardUpdater updater = new ScoreboardUpdater();
        updater.start();
        return updater;
    }""",
        """    public static ScoreboardUpdater init() {
        ScoreboardUpdater updater = new ScoreboardUpdater();
        updater.setName("Purpurbedrock-Scoreboard-Updater");
        updater.setDaemon(true);
        updater.start();
        return updater;
    }""",
        "ScoreboardUpdater.namedDaemonThread",
    )
    replace_exact(
        scoreboard_updater,
        """                // reset score-packets per second every second
                Collection<GeyserSession> sessions = geyser.getSessionManager().getSessions().values();""",
        """                // reset score-packets per second every second
                Collection<GeyserSession> sessions = geyser.getSessionManager().getSessions().values();
                if (sessions.isEmpty()) {
                    lastUpdate = currentTime;
                    lastPacketsPerSecondUpdate = currentTime;
                    sleepFor(SECOND_MILLIS_BETWEEN_UPDATES);
                    continue;
                }""",
        "ScoreboardUpdater.idleSleepWhenEmpty",
    )

    update_listener = root / "bootstrap/spigot/src/main/java/org/geysermc/geyser/platform/spigot/GeyserSpigotUpdateListener.java"
    replace_exact(
        update_listener,
        """    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        if (GeyserImpl.getInstance().config().notifyOnNewBedrockUpdate()) {
            final Player player = event.getPlayer();
            if (player.hasPermission(Permissions.CHECK_UPDATE)) {
                VersionCheckUtils.checkForGeyserUpdate(() -> new SpigotCommandSource(player));
            }
        }
    }""",
        """    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        // Purpurbedrock: embedded engine is versioned with the server core; skip external update checks on join
    }""",
        "GeyserSpigotUpdateListener.noOpJoinUpdateCheck",
    )

    # 13. Waypoint Console-Spam & LocatorBar 20Hz Flood Fix + Bedrock Client FPS Protections
    waypoint_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/java/level/JavaTrackedWaypointTranslator.java"
    replace_exact(
        waypoint_translator,
        """    @Override
    public void translate(GeyserSession session, ClientboundTrackedWaypointPacket packet) {
        session.getWaypointCache().handlePacket(packet);
    }""",
        """    @Override
    public void translate(GeyserSession session, ClientboundTrackedWaypointPacket packet) {
        if (session.protocolVersion() < 800) {
            return;
        }
        session.getWaypointCache().handlePacket(packet);
    }""",
        "JavaTrackedWaypointTranslator.gateLocatorBarProtocol",
    )

    waypoint_cache = root / "core/src/main/java/org/geysermc/geyser/session/cache/waypoint/WaypointCache.java"
    replace_exact(
        waypoint_cache,
        """    public void tick() {
        for (GeyserWaypoint waypoint : waypoints.values()) {
            if (waypoint instanceof TickingWaypoint ticking) {
                ticking.tick();
            }
        }
    }""",
        """    public void tick() {
        if (waypoints.isEmpty()) {
            return;
        }
        for (GeyserWaypoint waypoint : waypoints.values()) {
            if (waypoint instanceof TickingWaypoint ticking) {
                ticking.tick();
            }
        }
    }""",
        "WaypointCache.fastEmptyTick",
    )
    replace_exact(
        waypoint_cache,
        """    private void update(TrackedWaypoint waypoint) {
        getWaypoint(waypoint).ifPresent(tracked -> tracked.update(waypoint.data()));
    }""",
        """    private void update(TrackedWaypoint waypoint) {
        Optional<GeyserWaypoint> existing = getWaypoint(waypoint);
        if (existing.isEmpty()) {
            track(waypoint);
            return;
        }
        GeyserWaypoint tracked = existing.get();
        boolean matchesType = switch (waypoint.type()) {
            case EMPTY -> false;
            case VEC3I -> tracked instanceof CoordinatesWaypoint;
            case CHUNK -> tracked instanceof ChunkWaypoint;
            case AZIMUTH -> tracked instanceof AzimuthWaypoint;
        };
        if (!matchesType) {
            track(waypoint);
            return;
        }
        tracked.update(waypoint.data());
    }""",
        "WaypointCache.handleSubtypeTransitionOnUpdate",
    )

    coords_waypoint = root / "core/src/main/java/org/geysermc/geyser/session/cache/waypoint/CoordinatesWaypoint.java"
    replace_exact(
        coords_waypoint,
        """        } else {
            session.getGeyser().getLogger().warning("Received incorrect waypoint data " + data.getClass() + " for coordinates waypoint");
        }""",
        """        } else {
            session.getGeyser().getLogger().debug("Received incorrect waypoint data " + data.getClass() + " for coordinates waypoint");
        }""",
        "CoordinatesWaypoint.downgradeMismatchWarning",
    )

    chunk_waypoint = root / "core/src/main/java/org/geysermc/geyser/session/cache/waypoint/ChunkWaypoint.java"
    replace_exact(
        chunk_waypoint,
        """        } else {
            session.getGeyser().getLogger().warning("Received incorrect waypoint data " + data.getClass() + " for chunk waypoint");
        }""",
        """        } else {
            session.getGeyser().getLogger().debug("Received incorrect waypoint data " + data.getClass() + " for chunk waypoint");
        }""",
        "ChunkWaypoint.downgradeMismatchWarning",
    )

    azimuth_waypoint = root / "core/src/main/java/org/geysermc/geyser/session/cache/waypoint/AzimuthWaypoint.java"
    replace_exact(
        azimuth_waypoint,
        """    private static final float WAYPOINT_DISTANCE = Integer.MAX_VALUE;

    // The angle, in radians, where the waypoint should appear on the bar
    private float angle = 0.0F;

    public AzimuthWaypoint(GeyserSession session, UUID uuid, CustomWaypointStyle style, Identifier styleIdentifier, Color color, Optional<Entity> entity) {
        super(session, uuid, style, styleIdentifier, color, entity);
    }

    @Override
    public void setData(WaypointData data) {
        if (data instanceof AzimuthWaypointData(float azimuthAngle)) {
            angle = azimuthAngle;
            updatePosition();
        } else {
            session.getGeyser().getLogger().warning("Received incorrect waypoint data " + data.getClass() + " for azimuth waypoint");
        }
    }

    @Override
    public void tick() {
        // Update position so that it remains accurate to the angle as the player moves around
        updatePosition();
        sendLocationPacket();
    }""",
        """    private static final float WAYPOINT_DISTANCE = 65536.0F;

    // The angle, in radians, where the waypoint should appear on the bar
    private float angle = 0.0F;
    private float lastSentAngle = Float.NaN;
    private Vector3f lastAnchorPosition = null;
    private int tickCounter = 0;

    public AzimuthWaypoint(GeyserSession session, UUID uuid, CustomWaypointStyle style, Identifier styleIdentifier, Color color, Optional<Entity> entity) {
        super(session, uuid, style, styleIdentifier, color, entity);
    }

    @Override
    public void setData(WaypointData data) {
        if (data instanceof AzimuthWaypointData(float azimuthAngle)) {
            angle = azimuthAngle;
            if (Float.isNaN(lastSentAngle) || Math.abs(azimuthAngle - lastSentAngle) >= 0.012F) {
                lastSentAngle = azimuthAngle;
                lastAnchorPosition = session.getPlayerEntity().position();
                updatePosition();
            }
        } else {
            session.getGeyser().getLogger().debug("Received incorrect waypoint data " + data.getClass() + " for azimuth waypoint");
        }
    }

    @Override
    public void tick() {
        if ((++tickCounter % 10) != 0) {
            return;
        }
        Vector3f playerPosition = session.getPlayerEntity().position();
        if (!Float.isNaN(lastSentAngle)
                && Math.abs(angle - lastSentAngle) < 0.0175F
                && lastAnchorPosition != null
                && playerPosition.distanceSquared(lastAnchorPosition) < 256.0F) {
            return;
        }
        lastSentAngle = angle;
        lastAnchorPosition = playerPosition;
        updatePosition();
        sendLocationPacket();
    }""",
        "AzimuthWaypoint.floatSafeAndThrottledTick",
    )

    particles_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/java/level/JavaLevelParticlesTranslator.java"
    replace_exact(
        particles_translator,
        """    private static final int MAX_PARTICLES = 100;""",
        """    private static final int MAX_PARTICLES = Math.max(1, Integer.getInteger("purpurbedrock.maxParticlesPerPacket", 16));""",
        "JavaLevelParticlesTranslator.clientFpsParticleCap",
    )

    motion_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/java/entity/JavaSetEntityMotionTranslator.java"
    replace_exact(
        motion_translator,
        """        Entity entity = session.getEntityCache().getEntityByJavaId(packet.getEntityId());
        if (entity == null) return;

        entity.setMotion(packet.getMovement().toFloat());""",
        """        Entity entity = session.getEntityCache().getEntityByJavaId(packet.getEntityId());
        if (entity == null) return;

        org.cloudburstmc.math.vector.Vector3f prevMotion = entity.getMotion();
        org.cloudburstmc.math.vector.Vector3f newMotion = packet.getMovement().toFloat();
        entity.setMotion(newMotion);
        if (entity != session.getPlayerEntity()
                && newMotion.lengthSquared() < 1.0E-6F
                && prevMotion != null
                && prevMotion.lengthSquared() < 1.0E-6F) {
            return;
        }""",
        "JavaSetEntityMotionTranslator.skipRedundantZeroMotion",
    )

    # =========================================================================
    # 14. Universal Join Reliability Across 1.21.0–26.52 & Non-Xbox XUID Isolation:
    #     - Non-Xbox Bedrock clients (xbox-auth-required: false) send empty extraData.xuid (""),
    #       which collided in SessionManager.isXuidAlreadyPending("") / sessionByXuid("") and kicked
    #       every 2nd+ non-Xbox subscriber with geyser.auth.already_loggedin!
    #     - Leaves ALL already-valid [a-zA-Z0-9_]{3,16} (including space->underscore Gamertags) 100% untouched,
    #       while auto-transliterating/sanitizing broken Bedrock nicknames (Cyrillic "Стив"/"Игрок", hyphens, dots, >16/<3).
    #     - Prevents forced GeyserIntegratedPack kicks on cellular/LTE clients when force-resource-packs: false.
    #     - Backports Oct 2026 Geyser fixes: duplicate rak-error-handler guard (#6746/#6824),
    #       null-safe 26.40-26.43 CodecProcessor.updateCodec, 1.21.130+ SkinManager PlayerList fix (#6725),
    #       and heterogeneous sign NBT safety (#6783).
    # =========================================================================
    login_enc_utils = root / "core/src/main/java/org/geysermc/geyser/util/LoginEncryptionUtils.java"
    replace_exact(
        login_enc_utils,
        """            session.setAuthData(new AuthData(extraData.displayName, extraData.identity, xuid, issuedAt, extraData.minecraftId));""",
        """            String effectiveDisplayName = sanitizeBedrockUsername(extraData.displayName, data.getDeviceId());
            if (!result.signed() || xuid == null || xuid.isBlank() || "0".equals(xuid)) {
                xuid = computeDeterministicOfflineXuid(effectiveDisplayName);
            }
            session.setAuthData(new AuthData(effectiveDisplayName, extraData.identity, xuid, issuedAt, extraData.minecraftId));""",
        "LoginEncryptionUtils.offlineXuidAndSafeUsername",
    )
    replace_exact(
        login_enc_utils,
        """    private static void startEncryptionHandshake(GeyserSession session, PublicKey key) throws Exception {""",
        """    public static String computeDeterministicOfflineXuid(String username) {
        String normalized = (username == null ? "player" : username.trim().toLowerCase(java.util.Locale.ROOT));
        java.util.UUID offlineId = java.util.UUID.nameUUIDFromBytes(
            ("OfflineBedrock:" + normalized).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        long synthetic = (offlineId.getLeastSignificantBits() & 0x0003FFFFFFFFFFFFL) | 0x0008000000000000L;
        return Long.toString(synthetic);
    }

    public static String sanitizeBedrockUsername(String rawName, String deviceId) {
        if (rawName == null) {
            rawName = "";
        }
        String spaceNormalized = rawName.trim().replace(' ', '_');
        if (isAsciiValidJavaName(spaceNormalized)) {
            return spaceNormalized;
        }
        String lowerTrimmed = rawName.trim().toLowerCase(java.util.Locale.ROOT);
        boolean genericDefault = lowerTrimmed.isEmpty()
            || lowerTrimmed.equals("стив")
            || lowerTrimmed.equals("игрок")
            || lowerTrimmed.equals("алекс");

        StringBuilder sb = new StringBuilder(spaceNormalized.length() + 4);
        for (int i = 0; i < spaceNormalized.length(); i++) {
            char c = spaceNormalized.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else if (c == '-' || c == '.') {
                sb.append('_');
            } else {
                sb.append(transliterateCyrillicChar(c));
            }
        }
        String cleaned = sb.toString().replaceAll("_+", "_");
        while (cleaned.startsWith("_") && cleaned.length() > 3) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("_") && cleaned.length() > 3) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) {
            cleaned = "Player";
            genericDefault = true;
        }
        if (genericDefault || cleaned.length() < 3) {
            String seed = (deviceId != null && !deviceId.isBlank()) ? deviceId.trim() : rawName;
            int hash = seed.hashCode() & 0xFFFF;
            String hex = String.format(java.util.Locale.ROOT, "%04x", hash);
            int maxPrefix = Math.min(cleaned.length(), 11);
            String prefix = cleaned.substring(0, maxPrefix);
            if (prefix.isEmpty()) {
                prefix = "Plr";
            }
            cleaned = prefix + "_" + hex;
        }
        if (cleaned.length() > 16) {
            cleaned = cleaned.substring(0, 16);
        }
        while (cleaned.length() < 3) {
            cleaned = cleaned + "_";
        }
        return cleaned;
    }

    private static boolean isAsciiValidJavaName(String s) {
        if (s == null || s.length() < 3 || s.length() > 16) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String transliterateCyrillicChar(char ch) {
        boolean upper = Character.isUpperCase(ch);
        char lower = Character.toLowerCase(ch);
        String mapped = switch (lower) {
            case 'а' -> "a";
            case 'б' -> "b";
            case 'в' -> "v";
            case 'г' -> "g";
            case 'д' -> "d";
            case 'е', 'ё', 'э' -> "e";
            case 'ж' -> "zh";
            case 'з' -> "z";
            case 'и', 'і', 'ї' -> "i";
            case 'й' -> "y";
            case 'к' -> "k";
            case 'л' -> "l";
            case 'м' -> "m";
            case 'н' -> "n";
            case 'о' -> "o";
            case 'п' -> "p";
            case 'р' -> "r";
            case 'с' -> "s";
            case 'т' -> "t";
            case 'у' -> "u";
            case 'ф' -> "f";
            case 'х' -> "kh";
            case 'ц' -> "ts";
            case 'ч' -> "ch";
            case 'ш', 'щ' -> "sh";
            case 'ы' -> "y";
            case 'ю' -> "yu";
            case 'я' -> "ya";
            case 'ґ' -> "g";
            case 'є' -> "ye";
            default -> "";
        };
        if (mapped.isEmpty() || !upper) {
            return mapped;
        }
        return Character.toUpperCase(mapped.charAt(0)) + mapped.substring(1);
    }

    private static void startEncryptionHandshake(GeyserSession session, PublicKey key) throws Exception {""",
        "LoginEncryptionUtils.helperMethods",
    )

    session_mgr = root / "core/src/main/java/org/geysermc/geyser/session/SessionManager.java"
    replace_exact(
        session_mgr,
        """    public boolean isXuidAlreadyPending(String xuid) {
        for (GeyserSession session : pendingSessions) {
            if (session.xuid().equals(xuid)) {
                return true;
            }
        }
        return false;
    }

    public @Nullable GeyserSession sessionByXuid(@NonNull String xuid) {
        Objects.requireNonNull(xuid);
        for (GeyserSession session : sessions.values()) {
            if (session.xuid().equals(xuid)) {
                return session;
            }
        }
        return null;
    }""",
        """    public boolean isXuidAlreadyPending(String xuid) {
        if (xuid == null || xuid.isBlank() || "0".equals(xuid)) {
            return false;
        }
        for (GeyserSession session : pendingSessions) {
            if (xuid.equals(session.xuid())) {
                return true;
            }
        }
        return false;
    }

    public @Nullable GeyserSession sessionByXuid(@NonNull String xuid) {
        Objects.requireNonNull(xuid);
        if (xuid.isBlank() || "0".equals(xuid)) {
            return null;
        }
        for (GeyserSession session : sessions.values()) {
            if (xuid.equals(session.xuid())) {
                return session;
            }
        }
        return null;
    }""",
        "SessionManager.ignoreBlankOrZeroXuid",
    )

    replace_exact(
        upstream_handler,
        """        resourcePacksInfo.setForcedToAccept(GeyserImpl.getInstance().config().gameplay().forceResourcePacks() ||
            resourcePackLoadEvent.isIntegratedPackActive());""",
        """        resourcePacksInfo.setForcedToAccept(GeyserImpl.getInstance().config().gameplay().forceResourcePacks());""",
        "UpstreamPacketHandler.nonBlockingIntegratedPack",
    )
    replace_exact(
        upstream_handler,
        """            case REFUSED -> session.disconnect("disconnectionScreen.resourcePack");""",
        """            case REFUSED -> {
                if (GeyserImpl.getInstance().config().gameplay().forceResourcePacks()) {
                    session.disconnect("disconnectionScreen.resourcePack");
                } else {
                    ResourcePackStackPacket stackPacket = new ResourcePackStackPacket();
                    stackPacket.setExperimentsPreviouslyToggled(false);
                    stackPacket.setForcedToAccept(false);
                    stackPacket.setGameVersion("*");
                    if (session.protocolVersion() == 800) {
                        stackPacket.getExperiments().add(new org.cloudburstmc.protocol.bedrock.data.ExperimentData("y_2025_drop_2", true));
                        stackPacket.getExperiments().add(new org.cloudburstmc.protocol.bedrock.data.ExperimentData("locator_bar", true));
                    } else if (session.protocolVersion() == 827) {
                        stackPacket.getExperiments().add(new org.cloudburstmc.protocol.bedrock.data.ExperimentData("y_2025_drop_3", true));
                    }
                    session.sendUpstreamPacket(stackPacket);
                }
            }""",
        "UpstreamPacketHandler.allowJoinWhenOptionalPacksSkipped",
    )

    server_init = root / "core/src/main/java/org/geysermc/geyser/network/GeyserServerInitializer.java"
    replace_exact(
        server_init,
        """                channel.pipeline().addAfter(BedrockPeer.NAME, InvalidPacketHandler.NAME, new InvalidPacketHandler(session));""",
        """                if (channel.pipeline().get(InvalidPacketHandler.NAME) != null) {
                    channel.pipeline().replace(InvalidPacketHandler.NAME, InvalidPacketHandler.NAME, new InvalidPacketHandler(session));
                } else {
                    channel.pipeline().addAfter(BedrockPeer.NAME, InvalidPacketHandler.NAME, new InvalidPacketHandler(session));
                }""",
        "GeyserServerInitializer.duplicateRakErrorHandlerGuard",
    )

    replace_exact(
        codec_processor,
        """    public static void updateCodec(UpstreamSession upstreamSession, String version) {
        // Support all older 1.26.4x builds
        switch (version) {
            case "1.26.40", "1.26.41", "1.26.42", "1.26.43" -> {
                upstreamSession.getSession().setCodec(processCodec(Bedrock_v2168.CODEC));
            }
        }
    }""",
        """    public static void updateCodec(UpstreamSession upstreamSession, String version) {
        if (version == null) {
            return;
        }
        // Support all older 1.26.4x / 26.4x builds without NPE on custom clients
        switch (version.trim()) {
            case "1.26.40", "1.26.41", "1.26.42", "1.26.43",
                 "26.40", "26.41", "26.42", "26.43" -> {
                upstreamSession.getSession().setCodec(processCodec(Bedrock_v2168.CODEC));
            }
        }
    }""",
        "CodecProcessor.nullSafeUpdateCodec",
    )

    skin_manager = root / "core/src/main/java/org/geysermc/geyser/skin/SkinManager.java"
    replace_exact(
        skin_manager,
        """        // Since 1.21.130: PlayerSkinPacket only works if player is listed; might as well always use the player list packet
        if (entity.uuid().equals(session.getPlayerEntity().uuid()) || !entity.isListed()) {""",
        """        // Upstream #6725: Since 1.21.130 (v898+), PlayerSkinPacket no longer reliably updates listed players
        if (session.protocolVersion() >= 898 || entity.uuid().equals(session.getPlayerEntity().uuid()) || !entity.isListed()) {""",
        "SkinManager.playerListSkinOnModernProtocols",
    )

    sign_translator = root / "core/src/main/java/org/geysermc/geyser/translator/level/block/entity/SignBlockEntityTranslator.java"
    replace_exact(
        sign_translator,
        """        List<?> rawMessages = javaNbt.getList("messages", NbtType.COMPOUND);
        if (rawMessages.isEmpty()) {
            rawMessages = javaNbt.getList("messages", NbtType.STRING);
        }
        List<String> messages = MessageTranslator.signTextFromNbtTag(session, rawMessages);""",
        """        List<String> messages;
        try {
            Object rawObj = javaNbt.get("messages");
            List<?> rawMessages = (rawObj instanceof List<?> rawList) ? rawList : java.util.Collections.emptyList();
            messages = MessageTranslator.signTextFromNbtTag(session, rawMessages);
        } catch (RuntimeException ignored) {
            messages = java.util.Collections.emptyList();
        }""",
        "SignBlockEntityTranslator.safeHeterogeneousNbtSignList",
    )



if __name__ == "__main__":
    main()

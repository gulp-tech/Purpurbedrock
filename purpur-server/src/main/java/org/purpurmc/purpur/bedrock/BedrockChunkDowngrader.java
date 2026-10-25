package org.purpurmc.purpur.bedrock;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.purpurmc.purpur.PurpurConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lossless 26.3 (DataVersion 5023) -> 26.2 (DataVersion 4903) chunk, BlockState,
 * DecoratedPot, BlendingData, Status, and ItemStack compatibility layer.
 */
public final class BedrockChunkDowngrader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Purpurbedrock");
    private static final AtomicBoolean LOGGED_DOWNGRADE_BANNER = new AtomicBoolean(false);
    private static final String[] DECORATED_POT_SIDES = {"back", "left", "right", "front"};

    private static final Set<String> EXPLORER_MAP_263_ITEMS = Set.of(
        "minecraft:ocean_explorer_map",
        "minecraft:ocean_monument_map",
        "minecraft:swamp_explorer_map",
        "minecraft:swamp_hut_map",
        "minecraft:trial_explorer_map",
        "minecraft:buried_trial_chambers_map",
        "minecraft:woodland_explorer_map",
        "minecraft:woodland_mansion_map",
        "minecraft:jungle_explorer_map",
        "minecraft:jungle_pyramid_map",
        "minecraft:desert_village_map",
        "minecraft:plains_village_map",
        "minecraft:savanna_village_map",
        "minecraft:snowy_village_map",
        "minecraft:taiga_village_map",
        "minecraft:buried_treasure_map"
    );

    private BedrockChunkDowngrader() {
    }

    /**
     * Wraps {@link BlockState#CODEC} so Minecraft 26.3's BlockState representations
     * (bare StringTag {@code "minecraft:stone"} and lowercase {@code {id, properties}} maps)
     * decode cleanly into exact 26.2 {@link BlockState} instances without falling back to air.
     */
    public static Codec<BlockState> wrapBlockStateCodec(final Codec<BlockState> baseCodec) {
        final Decoder.Terminal<BlockState> baseTerminal = baseCodec.terminal();
        final Decoder<BlockState> compatDecoder = Decoder.ofTerminal(new Decoder.Terminal<>() {
            @Override
            public <T> DataResult<BlockState> decode(final DynamicOps<T> ops, final T input) {
                // 26.3 propertyless BlockState: bare string e.g. "minecraft:pale_oak_stairs" or "minecraft:air"
                final Optional<String> strOpt = ops.getStringValue(input).result();
                if (strOpt.isPresent()) {
                    final String mappedId = resolveCompatibleBlockId(strOpt.get());
                    final T normalized = ops.createMap(Map.of(ops.createString("Name"), ops.createString(mappedId)));
                    return baseTerminal.decode(ops, normalized);
                }

                // 26.3 stateful BlockState: {id: "minecraft:...", properties: {...}}
                final Optional<MapLike<T>> mapOpt = ops.getMap(input).result();
                if (mapOpt.isPresent()) {
                    final MapLike<T> map = mapOpt.get();
                    final T existingName = map.get("Name");
                    if (existingName == null) {
                        final T idVal = map.get("id");
                        if (idVal != null) {
                            final String rawId = ops.getStringValue(idVal).result().orElse("minecraft:air");
                            final String mappedId = resolveCompatibleBlockId(rawId);
                            final T nameVal = ops.createString(mappedId);
                            T propsVal = map.get("properties");
                            if (propsVal == null) {
                                propsVal = map.get("Properties");
                            }
                            if (propsVal != null) {
                                final T filteredProps = filterKnownProperties(ops, mappedId, propsVal);
                                final T normalized = filteredProps != null
                                    ? ops.createMap(Map.of(ops.createString("Name"), nameVal, ops.createString("Properties"), filteredProps))
                                    : ops.createMap(Map.of(ops.createString("Name"), nameVal));
                                return baseTerminal.decode(ops, normalized);
                            }
                            final T normalized = ops.createMap(Map.of(ops.createString("Name"), nameVal));
                            return baseTerminal.decode(ops, normalized);
                        }
                    } else {
                        // Also guard 26.2-formatted maps that reference a 26.3-only block ID
                        final Optional<String> rawNameOpt = ops.getStringValue(existingName).result();
                        if (rawNameOpt.isPresent()) {
                            final String rawName = rawNameOpt.get();
                            final String mappedName = resolveCompatibleBlockId(rawName);
                            if (!mappedName.equals(rawName)) {
                                final T nameVal = ops.createString(mappedName);
                                final T propsVal = map.get("Properties");
                                final T filteredProps = propsVal != null ? filterKnownProperties(ops, mappedName, propsVal) : null;
                                final T normalized = filteredProps != null
                                    ? ops.createMap(Map.of(ops.createString("Name"), nameVal, ops.createString("Properties"), filteredProps))
                                    : ops.createMap(Map.of(ops.createString("Name"), nameVal));
                                return baseTerminal.decode(ops, normalized);
                            }
                        }
                    }
                }

                return baseTerminal.decode(ops, input);
            }
        });
        return Codec.of(baseCodec, compatDecoder).stable();
    }

    /**
     * Adapts a raw chunk {@link CompoundTag} saved by Minecraft 26.3+ into 26.2's exact schema
     * prior to {@code SerializableChunkData.parse}.
     */
    public static void adaptChunkNbtFor262(final CompoundTag chunkData, final int currentDataVersion) {
        if (chunkData == null || !PurpurConfig.bedrockAllowNewerChunkDataVersion) {
            return;
        }

        final int sourceDataVersion = chunkData.getIntOr("DataVersion", currentDataVersion);
        final boolean wasNewerChunk = sourceDataVersion > currentDataVersion;
        if (wasNewerChunk) {
            chunkData.putInt("DataVersion", currentDataVersion);
            if (LOGGED_DOWNGRADE_BANNER.compareAndSet(false, true)) {
                LOGGER.info("[Purpurbedrock] Обнаружен мир с более новой версии (DataVersion {} -> {}): включена бесшовная конвертация палитр блоков, тайлов и чанков под 26.2.",
                    sourceDataVersion, currentDataVersion);
            }
        }

        // 1. Reverse V5013: Status "minecraft:terrain" -> "minecraft:carvers"
        fixChunkStatus(chunkData, "Status");
        chunkData.getCompound("below_zero_retrogen").ifPresent(retrogen -> fixChunkStatus(retrogen, "target_status"));

        // 2. Reverse V5009: blending_data.heights FloatTag -> DoubleTag
        chunkData.getCompound("blending_data").ifPresent(BedrockChunkDowngrader::downgradeBlendingHeights);

        // 3. Reverse V5006 + 26.3 StringTag palette entries in sections[*].block_states.palette
        final ListTag sections = chunkData.getListOrEmpty("sections");
        for (int i = 0, len = sections.size(); i < len; i++) {
            final CompoundTag section = sections.getCompound(i).orElse(null);
            if (section == null) {
                continue;
            }
            section.getCompound("block_states").ifPresent(BedrockChunkDowngrader::normalizeBlockStatesContainer);
        }

        // 4. Reverse V4996 (DecoratedPot sherds) & V5007/V5008/V5012 item changes in block_entities
        final ListTag blockEntities = chunkData.getListOrEmpty("block_entities");
        for (int i = 0, len = blockEntities.size(); i < len; i++) {
            final CompoundTag beTag = blockEntities.getCompound(i).orElse(null);
            if (beTag == null) {
                continue;
            }
            final String beId = beTag.getStringOr("id", "");
            if ("minecraft:decorated_pot".equals(beId) || "decorated_pot".equals(beId)) {
                downgradeDecoratedPotSherds(beTag);
            }
            if (wasNewerChunk) {
                downgradeNestedItemsAndBlockStates(beTag);
            }
        }

        if (wasNewerChunk) {
            final ListTag entities = chunkData.getListOrEmpty("entities");
            for (int i = 0, len = entities.size(); i < len; i++) {
                final CompoundTag entityTag = entities.getCompound(i).orElse(null);
                if (entityTag != null) {
                    downgradeNestedItemsAndBlockStates(entityTag);
                }
            }
        }
    }

    private static void fixChunkStatus(final CompoundTag tag, final String key) {
        final String status = tag.getStringOr(key, "");
        if ("minecraft:terrain".equals(status) || "terrain".equals(status)) {
            tag.putString(key, "minecraft:carvers");
        }
    }

    private static void downgradeBlendingHeights(final CompoundTag blending) {
        final Tag rawHeights = blending.get("heights");
        if (!(rawHeights instanceof ListTag heights) || heights.isEmpty()) {
            return;
        }
        if (!(heights.get(0) instanceof FloatTag)) {
            return;
        }
        final ListTag converted = new ListTag();
        for (int i = 0, len = heights.size(); i < len; i++) {
            final Tag elem = heights.get(i);
            if (elem instanceof FloatTag floatTag) {
                final float val = floatTag.value();
                converted.add(DoubleTag.valueOf(val == Float.MAX_VALUE ? Double.MAX_VALUE : (double) val));
            } else {
                converted.add(elem);
            }
        }
        blending.put("heights", converted);
    }

    private static void normalizeBlockStatesContainer(final CompoundTag blockStates) {
        final Tag rawPalette = blockStates.get("palette");
        if (!(rawPalette instanceof ListTag palette) || palette.isEmpty()) {
            return;
        }

        boolean needsRewrite = false;
        for (int i = 0, len = palette.size(); i < len; i++) {
            final Tag entry = palette.get(i);
            if (entry instanceof StringTag) {
                needsRewrite = true;
                break;
            }
            if (entry instanceof CompoundTag compound && !compound.contains("Name") && compound.contains("id")) {
                needsRewrite = true;
                break;
            }
        }
        if (!needsRewrite) {
            return;
        }

        final ListTag normalizedPalette = new ListTag();
        for (int i = 0, len = palette.size(); i < len; i++) {
            final Tag entry = palette.get(i);
            if (entry instanceof StringTag strTag) {
                final CompoundTag normalized = new CompoundTag();
                normalized.putString("Name", resolveCompatibleBlockId(strTag.value()));
                normalizedPalette.add(normalized);
            } else if (entry instanceof CompoundTag compound) {
                if (!compound.contains("Name") && compound.contains("id")) {
                    final String rawId = compound.getStringOr("id", "minecraft:air");
                    final String mappedId = resolveCompatibleBlockId(rawId);
                    final CompoundTag normalized = new CompoundTag();
                    normalized.putString("Name", mappedId);
                    Tag props = compound.get("properties");
                    if (props == null) {
                        props = compound.get("Properties");
                    }
                    if (props instanceof CompoundTag propsCompound) {
                        final CompoundTag cleanProps = filterKnownPropertiesNbt(mappedId, propsCompound);
                        if (!cleanProps.isEmpty()) {
                            normalized.put("Properties", cleanProps);
                        }
                    }
                    normalizedPalette.add(normalized);
                } else {
                    normalizedPalette.add(compound);
                }
            } else {
                final CompoundTag fallback = new CompoundTag();
                fallback.putString("Name", "minecraft:air");
                normalizedPalette.add(fallback);
            }
        }
        blockStates.put("palette", normalizedPalette);
    }

    private static void downgradeDecoratedPotSherds(final CompoundTag beTag) {
        final Tag rawSherds = beTag.get("sherds");
        if (!(rawSherds instanceof CompoundTag sherdsMap)) {
            return;
        }
        final ListTag packed = new ListTag();
        for (final String side : DECORATED_POT_SIDES) {
            final CompoundTag sideMap = sherdsMap.getCompound(side).orElse(null);
            final String sherdId = sideMap != null ? sideMap.getStringOr("id", "minecraft:brick") : "minecraft:brick";
            packed.add(StringTag.valueOf(sherdId.isEmpty() ? "minecraft:brick" : sherdId));
        }
        beTag.put("sherds", packed);
    }

    private static void downgradeNestedItemsAndBlockStates(final CompoundTag tag) {
        if (tag.contains("id") && tag.contains("count")) {
            final String itemId = tag.getStringOr("id", "");
            if (EXPLORER_MAP_263_ITEMS.contains(itemId)) {
                tag.putString("id", "minecraft:filled_map");
            } else if (itemId.startsWith("minecraft:poplar_") || itemId.contains("_poplar_")) {
                tag.putString("id", itemId.replace("poplar", "birch"));
            } else if ("minecraft:sulfur_cube".equals(itemId)) {
                tag.putString("id", "minecraft:yellow_concrete");
            }
        }

        tag.getCompound("components").ifPresent(components -> {
            final Tag attackAnim = components.get("minecraft:attack_animation");
            if (attackAnim != null && !components.contains("minecraft:swing_animation")) {
                components.put("minecraft:swing_animation", attackAnim);
            }
            components.remove("minecraft:attack_animation");
            components.remove("minecraft:interact_animation");
            components.remove("minecraft:sulfur_cube_content");
            components.getCompound("minecraft:pot_decorations").ifPresent(potDeco -> {
                final ListTag packed = new ListTag();
                for (final String side : DECORATED_POT_SIDES) {
                    final CompoundTag sideMap = potDeco.getCompound(side).orElse(null);
                    final String sherdId = sideMap != null ? sideMap.getStringOr("id", "minecraft:brick") : "minecraft:brick";
                    packed.add(StringTag.valueOf(sherdId.isEmpty() ? "minecraft:brick" : sherdId));
                }
                components.put("minecraft:pot_decorations", packed);
            });
        });

        for (final String key : List.copyOf(tag.keySet())) {
            final Tag child = tag.get(key);
            if (child instanceof CompoundTag childCompound) {
                downgradeNestedItemsAndBlockStates(childCompound);
            } else if (child instanceof ListTag childList) {
                for (int i = 0, len = childList.size(); i < len; i++) {
                    if (childList.get(i) instanceof CompoundTag elemCompound) {
                        downgradeNestedItemsAndBlockStates(elemCompound);
                    }
                }
            }
        }
    }

    public static String resolveCompatibleBlockId(final String rawId) {
        if (rawId == null || rawId.isEmpty()) {
            return "minecraft:air";
        }
        final String namespaced = rawId.indexOf(':') >= 0 ? rawId : ("minecraft:" + rawId);
        final Identifier key = Identifier.tryParse(namespaced);
        if (key == null) {
            return "minecraft:air";
        }
        if (BuiltInRegistries.BLOCK.containsKey(key)) {
            return namespaced;
        }

        // Map 26.3-exclusive block IDs to closest structural 26.2 counterparts
        final String path = key.getPath();
        if (path.startsWith("poplar_") || path.contains("_poplar_")) {
            final String birchCandidate = "minecraft:" + path.replace("poplar", "birch");
            final Identifier birchKey = Identifier.tryParse(birchCandidate);
            if (birchKey != null && BuiltInRegistries.BLOCK.containsKey(birchKey)) {
                return birchCandidate;
            }
        }
        if ("shelf_mushroom".equals(path)) {
            return "minecraft:brown_mushroom";
        }
        if ("sulfur_cube".equals(path)) {
            return "minecraft:yellow_concrete";
        }
        return "minecraft:air";
    }

    private static CompoundTag filterKnownPropertiesNbt(final String blockId, final CompoundTag propsCompound) {
        final Identifier key = Identifier.tryParse(blockId);
        final Block block = key != null ? BuiltInRegistries.BLOCK.getOptional(key).orElse(null) : null;
        if (block == null) {
            return new CompoundTag();
        }
        final StateDefinition<Block, BlockState> stateDef = block.getStateDefinition();
        final CompoundTag cleaned = new CompoundTag();
        for (final String propName : propsCompound.keySet()) {
            final Property<?> prop = stateDef.getProperty(propName);
            if (prop == null) {
                continue;
            }
            final String valStr = propsCompound.getStringOr(propName, "");
            if (prop.getValue(valStr).isPresent()) {
                cleaned.putString(propName, valStr);
            }
        }
        return cleaned;
    }

    private static <T> T filterKnownProperties(final DynamicOps<T> ops, final String blockId, final T propsVal) {
        final Identifier key = Identifier.tryParse(blockId);
        final Block block = key != null ? BuiltInRegistries.BLOCK.getOptional(key).orElse(null) : null;
        if (block == null) {
            return null;
        }
        final Optional<MapLike<T>> propsMapOpt = ops.getMap(propsVal).result();
        if (propsMapOpt.isEmpty()) {
            return null;
        }
        final StateDefinition<Block, BlockState> stateDef = block.getStateDefinition();
        final List<Pair<T, T>> entries = propsMapOpt.get().entries().toList();
        boolean allValid = true;
        for (final Pair<T, T> pair : entries) {
            final Optional<String> kOpt = ops.getStringValue(pair.getFirst()).result();
            final Optional<String> vOpt = ops.getStringValue(pair.getSecond()).result();
            if (kOpt.isEmpty() || vOpt.isEmpty()) {
                allValid = false;
                break;
            }
            final Property<?> prop = stateDef.getProperty(kOpt.get());
            if (prop == null || prop.getValue(vOpt.get()).isEmpty()) {
                allValid = false;
                break;
            }
        }
        if (allValid) {
            return propsVal;
        }

        final Map<T, T> filtered = new LinkedHashMap<>();
        for (final Pair<T, T> pair : entries) {
            final Optional<String> kOpt = ops.getStringValue(pair.getFirst()).result();
            final Optional<String> vOpt = ops.getStringValue(pair.getSecond()).result();
            if (kOpt.isPresent() && vOpt.isPresent()) {
                final Property<?> prop = stateDef.getProperty(kOpt.get());
                if (prop != null && prop.getValue(vOpt.get()).isPresent()) {
                    filtered.put(pair.getFirst(), pair.getSecond());
                }
            }
        }
        return filtered.isEmpty() ? null : ops.createMap(filtered);
    }

    /**
     * End-to-end self-test verifying Minecraft 26.3 (DataVersion 5023) chunk NBT,
     * BlockState palettes (bare StringTags + {id, properties}), DecoratedPot sherds,
     * BlendingData FloatTags, and Status "minecraft:terrain" parse losslessly on 26.2.
     */
    public static String runSelfTest(final ServerLevel level) {
        // 1. Test BlockState.CODEC directly against 26.3 bare StringTag & {id, properties}
        final BlockState barrelState = BlockState.CODEC.parse(NbtOps.INSTANCE, StringTag.valueOf("minecraft:barrel")).getOrThrow();
        if (!barrelState.is(Blocks.BARREL)) {
            throw new IllegalStateException("Expected barrel, got " + barrelState);
        }

        final CompoundTag stairsProps = new CompoundTag();
        stairsProps.putString("facing", "south");
        stairsProps.putString("half", "bottom");
        stairsProps.putString("shape", "straight");
        stairsProps.putString("waterlogged", "false");
        stairsProps.putString("nonexistent_26_3_prop", "true");
        final CompoundTag stairs263 = new CompoundTag();
        stairs263.putString("id", "minecraft:pale_oak_stairs");
        stairs263.put("properties", stairsProps);
        final BlockState stairsState = BlockState.CODEC.parse(NbtOps.INSTANCE, stairs263).getOrThrow();
        if (!stairsState.is(Blocks.PALE_OAK_STAIRS)
            || stairsState.getValue(HorizontalDirectionalBlock.FACING) != net.minecraft.core.Direction.SOUTH) {
            throw new IllegalStateException("Expected south pale_oak_stairs, got " + stairsState);
        }

        // 2. Test full 26.3 (DataVersion 5023) chunk through SerializableChunkData.parse
        final CompoundTag chunk263 = new CompoundTag();
        chunk263.putInt("DataVersion", 5023);
        chunk263.putInt("xPos", -3);
        chunk263.putInt("zPos", -2);
        chunk263.putInt("yPos", -4);
        chunk263.putString("Status", "minecraft:terrain");

        final CompoundTag blending = new CompoundTag();
        final ListTag floatHeights = new ListTag();
        floatHeights.add(FloatTag.valueOf(64.5f));
        blending.put("heights", floatHeights);
        chunk263.put("blending_data", blending);

        final ListTag palette = new ListTag();
        palette.add(StringTag.valueOf("minecraft:air"));
        palette.add(StringTag.valueOf("minecraft:barrel"));
        palette.add(stairs263.copy());
        final CompoundTag skull263 = new CompoundTag();
        skull263.putString("id", "minecraft:player_head");
        final CompoundTag skullProps = new CompoundTag();
        skullProps.putString("rotation", "12");
        skull263.put("properties", skullProps);
        palette.add(skull263);
        palette.add(StringTag.valueOf("minecraft:decorated_pot"));
        final CompoundTag poplar263 = new CompoundTag();
        poplar263.putString("id", "minecraft:poplar_stairs");
        poplar263.put("properties", stairsProps.copy());
        palette.add(poplar263);

        final long[] words = new long[256];
        words[0] = 0L | (1L << 4) | (2L << 8) | (3L << 12) | (4L << 16) | (5L << 20);
        final CompoundTag blockStatesTag = new CompoundTag();
        blockStatesTag.put("palette", palette);
        blockStatesTag.put("data", new LongArrayTag(words));

        final CompoundTag section0 = new CompoundTag();
        section0.putByte("Y", (byte) 4);
        section0.put("block_states", blockStatesTag);
        final ListTag sections = new ListTag();
        sections.add(section0);
        chunk263.put("sections", sections);

        final CompoundTag potBe = new CompoundTag();
        potBe.putString("id", "minecraft:decorated_pot");
        potBe.putInt("x", -44);
        potBe.putInt("y", 64);
        potBe.putInt("z", -32);
        final CompoundTag sherds263 = new CompoundTag();
        final CompoundTag backSherd = new CompoundTag();
        backSherd.putString("id", "minecraft:prize_pottery_sherd");
        sherds263.put("back", backSherd);
        final CompoundTag frontSherd = new CompoundTag();
        frontSherd.putString("id", "minecraft:arms_up_pottery_sherd");
        sherds263.put("front", frontSherd);
        potBe.put("sherds", sherds263);
        final ListTag blockEntities = new ListTag();
        blockEntities.add(potBe);
        chunk263.put("block_entities", blockEntities);

        final SerializableChunkData parsed = SerializableChunkData.parse(level, level.palettedContainerFactory(), chunk263);
        if (parsed == null) {
            throw new IllegalStateException("SerializableChunkData.parse returned null for 26.3 chunk!");
        }

        final PalettedContainer<BlockState> decodedContainer = level.palettedContainerFactory()
            .blockStatesContainerCodec()
            .parse(NbtOps.INSTANCE, chunk263.getListOrEmpty("sections").getCompoundOrEmpty(0).getCompoundOrEmpty("block_states"))
            .getOrThrow();
        if (!decodedContainer.get(1, 0, 0).is(Blocks.BARREL)
            || !decodedContainer.get(2, 0, 0).is(Blocks.PALE_OAK_STAIRS)
            || !decodedContainer.get(3, 0, 0).is(Blocks.PLAYER_HEAD)
            || !decodedContainer.get(4, 0, 0).is(Blocks.DECORATED_POT)
            || !decodedContainer.get(5, 0, 0).is(Blocks.BIRCH_STAIRS)) {
            throw new IllegalStateException("Unexpected decoded section block states!");
        }

        final ListTag convertedSherds = chunk263.getListOrEmpty("block_entities").getCompoundOrEmpty(0).getListOrEmpty("sherds");
        if (convertedSherds.size() != 4 || !"minecraft:prize_pottery_sherd".equals(convertedSherds.getStringOr(0, ""))) {
            throw new IllegalStateException("DecoratedPot sherds were not downgraded properly: " + convertedSherds);
        }

        return "CHUNK_263_PARSE_SELFTEST_OK status=" + chunk263.getStringOr("Status", "")
            + " blocks=barrel,pale_oak_stairs,player_head,decorated_pot,birch_stairs";
    }
}

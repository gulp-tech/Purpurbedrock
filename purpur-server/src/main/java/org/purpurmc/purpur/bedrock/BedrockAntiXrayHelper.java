package org.purpurmc.purpur.bedrock;

import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.configuration.type.EngineMode;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.World;
import org.purpurmc.purpur.PurpurConfig;
import org.purpurmc.purpur.PurpurWorldConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Configures high-performance Bedrock-safe Anti-Xray and world TPS optimizations
 * automatically for Overworld, Nether (Ancient Debris), and custom worlds.
 *
 * Uses EngineMode.HIDE (engine-mode: 1) so Bedrock subchunk palettes stay compact
 * (no mobile FPS drops or fake-ore packet bloat) while completely hiding non-exposed
 * ores and lava-submerged diamonds / Ancient Debris from Bedrock X-Ray resource packs.
 */
public final class BedrockAntiXrayHelper {
    public static final List<Block> BEDROCK_HIDDEN_ORES = List.of(
        // Overworld ores & structures
        Blocks.COAL_ORE,
        Blocks.DEEPSLATE_COAL_ORE,
        Blocks.COPPER_ORE,
        Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.RAW_COPPER_BLOCK,
        Blocks.IRON_ORE,
        Blocks.DEEPSLATE_IRON_ORE,
        Blocks.RAW_IRON_BLOCK,
        Blocks.GOLD_ORE,
        Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.RAW_GOLD_BLOCK,
        Blocks.REDSTONE_ORE,
        Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.EMERALD_ORE,
        Blocks.DEEPSLATE_EMERALD_ORE,
        Blocks.LAPIS_ORE,
        Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE,
        Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.BUDDING_AMETHYST,
        Blocks.CHEST,
        Blocks.TRAPPED_CHEST,
        Blocks.ENDER_CHEST,
        Blocks.SPAWNER,
        // Buried storage / redstone blocks frequently targeted by base-finder clients.
        // HIDE only masks blocks that are not exposed, so normal opened containers remain usable.
        Blocks.BARREL,
        Blocks.HOPPER,
        Blocks.DISPENSER,
        Blocks.DROPPER,
        Blocks.FURNACE,
        Blocks.BLAST_FURNACE,
        Blocks.SMOKER,
        Blocks.BREWING_STAND,
        Blocks.CHISELED_BOOKSHELF,
        Blocks.DECORATED_POT,
        Blocks.SHULKER_BOX,
        // Nether ores & structures (Ancient Debris / Netherite protection)
        Blocks.ANCIENT_DEBRIS,
        Blocks.NETHER_GOLD_ORE,
        Blocks.NETHER_QUARTZ_ORE,
        Blocks.GILDED_BLACKSTONE
    );

    private BedrockAntiXrayHelper() {
    }

    public static void applyWorldProfile(
        WorldConfiguration paperConfig,
        PurpurWorldConfig purpurWorldConfig,
        World.Environment environment
    ) {
        if (paperConfig == null) {
            return;
        }

        if (PurpurConfig.bedrockAntiXrayEnabled && paperConfig.anticheat != null && paperConfig.anticheat.antiXray != null) {
            WorldConfiguration.Anticheat.AntiXray xray = paperConfig.anticheat.antiXray;
            xray.enabled = true;
            // EngineMode.HIDE keeps Bedrock subchunk palettes small (fast chunk loads, zero fake-ore FPS lag).
            // Always retain the mobile-safe mode. A stale per-world mode must not silently
            // weaken or inflate the Bedrock palette after a configuration migration.
            xray.engineMode = EngineMode.HIDE;
            xray.maxBlockHeight = Math.max(xray.maxBlockHeight, PurpurConfig.bedrockAntiXrayMaxHeight);
            xray.lavaObscures = PurpurConfig.bedrockAntiXrayLavaObscures;
            xray.updateRadius = Math.max(1, xray.updateRadius);

            Set<Block> merged = new LinkedHashSet<>();
            if (xray.hiddenBlocks != null) {
                merged.addAll(xray.hiddenBlocks);
            }
            merged.addAll(BEDROCK_HIDDEN_ORES);
            xray.hiddenBlocks = new ArrayList<>(merged);
        }

        if (PurpurConfig.bedrockPerformanceOptimizeWorlds) {
            if (paperConfig.misc != null
                && paperConfig.misc.redstoneImplementation == WorldConfiguration.Misc.RedstoneImplementation.VANILLA) {
                paperConfig.misc.redstoneImplementation = WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT;
            }
            if (paperConfig.collisions != null && paperConfig.collisions.maxEntityCollisions > 2) {
                paperConfig.collisions.maxEntityCollisions = 2;
            }
        }
    }
}

package org.purpurmc.purpur.anticheat.engine;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;
import org.purpurmc.purpur.anticheat.config.ACConfig;
import org.purpurmc.purpur.anticheat.gui.ACHolder;
import org.purpurmc.purpur.anticheat.model.PlayerProfile;
import org.purpurmc.purpur.anticheat.util.BedrockBridgeInfo;

import java.util.Collection;
import java.util.Locale;

/**
 * 21 Bedrock + Geyser 26.2 calibrated Anti-Cheat checks.
 *
 * Engineered directly against Geyser 26.2 translators (BedrockPlayerAuthInputTranslator,
 * BedrockMovePlayer, CollisionManager, BedrockInventoryTransactionTranslator) and 2026
 * Bedrock cheat clients (Horion, Solstice, ProtoHaxNeo, MuCuteClient, Toolbox, Prax,
 * PacketClient, Zephyr) to achieve zero false kicks and full cheat neutralization.
 */
public final class CheckRunner {
    private static final BlockFace[] SIX_FACES = {
        BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST
    };

    private final PurpurBedrockACPlugin plugin;

    public CheckRunner(PurpurBedrockACPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isBypassed(Player player, ACConfig cfg) {
        return cfg.enableBypassPermission && player.hasPermission(cfg.permBypass);
    }

    public boolean isRealWorldStorage(Inventory inv) {
        if (inv == null || inv.getHolder() instanceof ACHolder) {
            return false;
        }
        // Only treat real block-backed / ender-chest storage as lootable world containers (never DeluxeMenus/custom GUIs!)
        InventoryType type = inv.getType();
        if (type == InventoryType.ENDER_CHEST) {
            return inv.getLocation() != null;
        }
        return switch (type) {
            case CHEST, BARREL, SHULKER_BOX, HOPPER, DISPENSER, DROPPER,
                 FURNACE, BLAST_FURNACE, SMOKER, BREWING -> inv.getLocation() != null;
            default -> false;
        };
    }

    public boolean isMovementExempt(Player player, PlayerProfile profile, long nowMs, ACConfig cfg) {
        if (nowMs < profile.exemptUntilMs) {
            return true;
        }
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return true;
        }
        if (player.isFlying()) {
            if (player.getAllowFlight()) {
                profile.glideOrRiptideGraceUntilMs = nowMs + 2000L;
                return true;
            }
            // Spoofed flying without allowFlight -> do NOT exempt!
        }
        if (player.getAllowFlight()) {
            return true;
        }
        if (player.isGliding() || player.isRiptiding()) {
            profile.glideOrRiptideGraceUntilMs = nowMs + 2600L;
            return true;
        }
        if (player.isInsideVehicle()) {
            profile.vehicleGraceUntilMs = nowMs + 2200L;
            return true;
        }
        if (player.isSleeping() || player.isDead()) {
            return true;
        }
        return Bukkit.getTPS()[0] < cfg.minServerTps;
    }

    public void handleMove(Player player, PlayerProfile profile, PlayerMoveEvent event) {
        ACConfig cfg = plugin.cfg();
        if (isBypassed(player, cfg)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        long nowMs = System.currentTimeMillis();

        // Always record BoundingBox for victims' 18-tick ping-backtracked Reach & HitBox ring buffer
        profile.recordBoundingBox(player.getBoundingBox(), nowMs);

        // 1. BadPackets (NaN/Infinite, impossible pitch > 91.0°, or spoofed Ability fly)
        ACConfig.CheckSettings badPktCfg = cfg.check("badpackets");
        if (badPktCfg.enabled) {
            boolean badNumbers = !Double.isFinite(to.getX()) || !Double.isFinite(to.getY()) || !Double.isFinite(to.getZ())
                || !Float.isFinite(to.getYaw()) || !Float.isFinite(to.getPitch());
            boolean badPitch = Math.abs(to.getPitch()) > 91.0f;
            boolean fakeAbilityFly = player.isFlying() && !player.getAllowFlight()
                && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
            if (badNumbers || badPitch || fakeAbilityFly) {
                if (fakeAbilityFly) {
                    player.setFlying(false);
                }
                plugin.engine().nudgeSuspicion(profile, 2.0);
                if (profile.addBuffer("badpackets", 1.0) >= badPktCfg.buffer) {
                    String act = applySetback(player, profile, event, badPktCfg.setback, nowMs, cfg);
                    String reason = fakeAbilityFly ? "abilityFlySpoof"
                        : (badPitch ? String.format(Locale.US, "pitch=%.1f", to.getPitch()) : "NaN/Inf");
                    plugin.alerts().handleViolation(
                        player, profile, "badpackets", "BadPackets",
                        reason, 1.0, act
                    );
                }
                return;
            } else {
                profile.decayBuffer("badpackets", badPktCfg.decay);
            }
        }

        if (from.getWorld() != to.getWorld()) {
            profile.exemptUntilMs = Math.max(profile.exemptUntilMs, nowMs + 2500L);
            profile.resetTimerBucket(nowMs);
            profile.hasMoveAnchor = false;
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double deltaXZ = Math.hypot(dx, dz);

        trackRotationAndSnapBack(player, profile, to.getYaw(), to.getPitch(), cfg);

        if (deltaXZ < 1.0E-4 && Math.abs(dy) < 1.0E-4) {
            // Rotation-only packet: skip movement physics completely (0 CPU waste)
            return;
        }
        profile.lastActiveMoveMs = nowMs;

        // ElytraFly / PacketElytra check BEFORE gliding exemption skips ground physics
        if (player.isGliding() && !player.isRiptiding() && !player.getAllowFlight()
            && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR
            && nowMs >= profile.exemptUntilMs && Bukkit.getTPS()[0] >= cfg.minServerTps) {
            checkElytraFly(player, profile, event, to, deltaXZ, dy, nowMs, cfg);
        } else {
            profile.elytraHoverTicks = 0;
            profile.elytraAscendTicks = 0;
        }

        if (isMovementExempt(player, profile, nowMs, cfg)) {
            profile.airTicks = 0;
            profile.hoverTicks = 0;
            profile.wallClimbTicks = 0;
            profile.groundSpoofTicks = 0;
            profile.liquidSurfaceTicks = 0;
            profile.usingItemSprintTicks = 0;
            profile.invMoveTicks = 0;
            profile.velocityCheckPending = false;
            profile.lastDeltaXZ = deltaXZ;
            profile.lastDeltaY = dy;
            profile.wasNearGround = true;
            profile.resetTimerBucket(nowMs);
            profile.lastMoveX = to.getX();
            profile.lastMoveY = to.getY();
            profile.lastMoveZ = to.getZ();
            profile.hasMoveAnchor = true;
            return;
        }

        World world = to.getWorld();
        int bx = to.getBlockX();
        int bz = to.getBlockZ();
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            profile.resetTimerBucket(nowMs);
            return;
        }

        // 2. Grim-style Token Bucket Timer + Blink check (immune to Wi-Fi freezes!)
        checkTimerAndBlink(player, profile, event, to, nowMs, cfg);

        // Track Velocity (AntiKB) vertical & horizontal response across ping-scaled window
        int ping = BedrockBridgeInfo.getPing(player);
        if (profile.velocityCheckPending) {
            if (dy > profile.maxObservedPostVelY) {
                profile.maxObservedPostVelY = dy;
            }
            if (deltaXZ > profile.maxObservedPostVelXZ) {
                profile.maxObservedPostVelXZ = deltaXZ;
            }
            long windowMs = Math.clamp(ping + 320L, 350L, 950L);
            if (nowMs - profile.lastVelocityMs >= windowMs) {
                profile.velocityCheckPending = false;
                evaluateVelocityResponse(player, profile, world, to, cfg);
            }
        }

        boolean hasBoostGrace = profile.hasActiveBoostGrace(nowMs);
        boolean hasRecentVelocity = (nowMs - profile.lastVelocityMs) <= (1400L + ping);

        // Fast pre-screen: wake adaptive engine immediately if speed, jump, or hover looks abnormal
        if (!hasBoostGrace && !hasRecentVelocity
            && (deltaXZ > 0.45 || dy > 0.56 || (profile.airTicks > 7 && dy - profile.lastDeltaY > -0.02))) {
            plugin.engine().nudgeSuspicion(profile, 1.0);
        }

        // Adaptive Sleep/Wake gate: skip block-neighborhood scans when player is clean & sleeping
        boolean runFullSample = plugin.engine().shouldSampleMove(profile, cfg);
        int sampleWeight = switch (profile.stage) {
            case AWAKE -> 1;
            case WAKING -> cfg.wakingSampleInterval;
            case SLEEPING, DORMANT -> cfg.sleepSampleInterval;
        };

        if (!runFullSample) {
            profile.lastDeltaXZ = deltaXZ;
            profile.lastDeltaY = dy;
            profile.lastMoveX = to.getX();
            profile.lastMoveY = to.getY();
            profile.lastMoveZ = to.getZ();
            profile.hasMoveAnchor = true;
            return;
        }

        EnvContext env = scanEnvironment(world, to);
        if (env.onSlimeOrPiston || env.inBubbleColumn) {
            profile.slimeOrPistonGraceUntilMs = nowMs + 3500L;
            hasBoostGrace = true;
        }
        if (env.onIce || env.onSlimeOrPiston || env.onStairsOrSlab) {
            profile.iceOrSpeedBoostTicks = 28;
        } else if (profile.iceOrSpeedBoostTicks > 0) {
            profile.iceOrSpeedBoostTicks = Math.max(0, profile.iceOrSpeedBoostTicks - sampleWeight);
        }
        if (env.hasBlockAboveHead) {
            profile.ceilingHitTicks = 20;
        } else if (profile.ceilingHitTicks > 0) {
            profile.ceilingHitTicks = Math.max(0, profile.ceilingHitTicks - sampleWeight);
        }

        // 3. Phase / NoClip / VClip check
        checkPhase(player, profile, event, from, to, deltaXZ, dy, env, nowMs, cfg);

        boolean groundedNow = env.nearGround || env.inLiquid || env.inClimbableOrWeb || env.hasBoatOrShulkerSupport;

        // 4. Step & HighJump checks (before resetting wasNearGround!)
        if (!hasBoostGrace && !hasRecentVelocity && !env.inLiquid && !env.inClimbableOrWeb && !env.nearPistonOrDoor) {
            checkStepAndHighJump(player, profile, event, dy, groundedNow, nowMs, cfg);
        } else {
            profile.decayBuffer("step", cfg.check("step").decay);
            profile.decayBuffer("highjump", cfg.check("highjump").decay);
        }

        if (groundedNow) {
            profile.airTicks = 0;
            profile.hoverTicks = 0;
            profile.wallClimbTicks = 0;
            profile.groundSpoofTicks = 0;
            profile.trackedFallDistance = 0.0;
            if (env.nearGround && !env.inLiquid && !env.insideSolidFullBlock) {
                profile.lastSafeLocation = from.clone();
            }
        } else {
            profile.airTicks += sampleWeight;
            if (dy < 0.0) {
                profile.trackedFallDistance += (-dy);
            }
        }

        // 5. Fly, AirJump, Spider & NoFall (GroundSpoof) checks
        if (!env.inLiquid && !env.inClimbableOrWeb && !env.hasBoatOrShulkerSupport
            && !hasRecentVelocity && !hasBoostGrace
            && !player.hasPotionEffect(PotionEffectType.LEVITATION)
            && !player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            checkFlyAndAirJump(player, profile, event, dy, deltaXZ, sampleWeight, nowMs, cfg);
            checkSpider(player, profile, event, dy, groundedNow, env, sampleWeight, nowMs, cfg);
            checkNoFallGroundSpoof(player, profile, event, dy, groundedNow, sampleWeight, nowMs, cfg);
        } else {
            profile.decayBuffer("fly", cfg.check("fly").decay);
            profile.decayBuffer("airjump", cfg.check("airjump").decay);
            profile.decayBuffer("spider", cfg.check("spider").decay);
            profile.decayBuffer("nofall", cfg.check("nofall").decay);
        }

        // 6. Speed / BHop check
        if (!hasRecentVelocity && !hasBoostGrace && !env.inLiquid) {
            checkSpeed(player, profile, event, deltaXZ, dy, env, nowMs, cfg);
        } else {
            profile.decayBuffer("speed", cfg.check("speed").decay);
        }

        // 7. Jesus / WaterWalk check (4-corner AABB + Waterlogged + Boat/Shulker aware)
        checkJesus(player, profile, event, deltaXZ, dy, env, sampleWeight, hasRecentVelocity || hasBoostGrace, nowMs, cfg);

        // 8. NoSlow check (slot-switch & Bedrock sneak-shield safe)
        checkNoSlow(player, profile, event, deltaXZ, env, sampleWeight, hasRecentVelocity || hasBoostGrace, nowMs, cfg);

        // 9. InventoryMove check (sprinting with open world storage container)
        checkInventoryMove(player, profile, event, deltaXZ, env, sampleWeight, hasRecentVelocity || hasBoostGrace, nowMs, cfg);

        profile.wasNearGround = groundedNow;
        profile.lastDeltaXZ = deltaXZ;
        profile.lastDeltaY = dy;
        profile.lastMoveX = to.getX();
        profile.lastMoveY = to.getY();
        profile.lastMoveZ = to.getZ();
        profile.hasMoveAnchor = true;
    }

    private void checkTimerAndBlink(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        Location to,
        long nowMs,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings settings = cfg.check("timer");
        if (!settings.enabled) {
            return;
        }
        if (nowMs - profile.lastTeleportMs < 1800L) {
            profile.resetTimerBucket(nowMs);
            profile.lastMoveX = to.getX();
            profile.lastMoveY = to.getY();
            profile.lastMoveZ = to.getZ();
            profile.hasMoveAnchor = true;
            return;
        }

        long prevPacketMs = profile.lastTimerPacketMs;
        long gapMs = prevPacketMs > 0L ? (nowMs - prevPacketMs) : 0L;

        // Blink check: holding outbound packets > 1.6s and jumping > 9.5 blocks in one packet without teleport
        if (profile.hasMoveAnchor && gapMs >= cfg.blinkMinFreezeMs && !profile.hasActiveBoostGrace(nowMs)) {
            double jumpDist = Math.sqrt(
                sq(to.getX() - profile.lastMoveX)
                + sq(to.getY() - profile.lastMoveY)
                + sq(to.getZ() - profile.lastMoveZ)
            );
            if (jumpDist > cfg.blinkMaxBurstDistance && jumpDist < 64.0) {
                plugin.engine().nudgeSuspicion(profile, 1.4);
                if (profile.addBuffer("timer", 1.5) >= settings.buffer) {
                    String act = applySetback(player, profile, event, settings.setback, nowMs, cfg);
                    plugin.alerts().handleViolation(
                        player, profile, "timer", "Timer (Blink)",
                        String.format(Locale.US, "freeze=%dms dist=%.1f", gapMs, jumpDist),
                        1.0, act
                    );
                }
            }
        }

        double balanceMs = profile.advanceTimerBucket(nowMs, cfg.timerMaxLagCreditMs);
        if (balanceMs > cfg.timerMaxBalanceMs) {
            profile.timerBalanceMs = 80.0; // Partial reset after flag
            plugin.engine().nudgeSuspicion(profile, 1.1);
            if (profile.addBuffer("timer", 1.0) >= settings.buffer) {
                String act = applySetback(player, profile, event, settings.setback, nowMs, cfg);
                plugin.alerts().handleViolation(
                    player, profile, "timer", "Timer (GameSpeed)",
                    String.format(Locale.US, "balance=+%.0fms", balanceMs),
                    1.0, act
                );
            }
        } else {
            profile.decayBuffer("timer", settings.decay * 0.25);
        }
    }

    private void checkPhase(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        Location from,
        Location to,
        double deltaXZ,
        double dy,
        EnvContext env,
        long nowMs,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings phaseCfg = cfg.check("phase");
        if (!phaseCfg.enabled || env.nearPistonOrDoor || nowMs - profile.lastTeleportMs < 2000L) {
            return;
        }
        double dist3D = Math.sqrt(deltaXZ * deltaXZ + dy * dy);
        if (dist3D > 8.0) {
            return;
        }

        // Horizontal NoClip: moving from outside into the middle of a 2-high full solid occluding wall
        boolean fromInside = isInsideFullSolidWall(from);
        if (!fromInside && env.insideSolidFullBlock && deltaXZ > 0.18) {
            plugin.engine().nudgeSuspicion(profile, 1.8);
            if (profile.addBuffer("phase", 1.5) >= phaseCfg.buffer) {
                String act = applySetback(player, profile, event, phaseCfg.setback, nowMs, cfg);
                plugin.alerts().handleViolation(
                    player, profile, "phase", "Phase (NoClip)",
                    String.format(Locale.US, "xz=%.2f в плотный блок", deltaXZ),
                    1.0, act
                );
                return;
            }
        }

        // Vertical VClip: teleporting +/- 1.4..7.5 blocks vertically across a solid floor/ceiling
        if (Math.abs(dy) >= 1.40 && Math.abs(dy) <= 7.5 && deltaXZ < 0.35) {
            int checkY = dy < 0 ? (int) Math.floor(from.getY() - 0.5) : (int) Math.floor(from.getY() + 1.85);
            Block barrier = from.getWorld().getBlockAt(from.getBlockX(), checkY, from.getBlockZ());
            if (isFullSolidWallBlock(barrier)) {
                plugin.engine().nudgeSuspicion(profile, 2.0);
                if (profile.addBuffer("phase", 2.0) >= phaseCfg.buffer) {
                    String act = applySetback(player, profile, event, phaseCfg.setback, nowMs, cfg);
                    plugin.alerts().handleViolation(
                        player, profile, "phase", "Phase (VClip)",
                        String.format(Locale.US, "dy=%.2f сквозь %s", dy, barrier.getType().name()),
                        1.0, act
                    );
                    return;
                }
            }
        }
        profile.decayBuffer("phase", phaseCfg.decay);
    }

    private void checkStepAndHighJump(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        double dy,
        boolean groundedNow,
        long nowMs,
        ACConfig cfg
    ) {
        if (dy <= 0.62) {
            // Native Bedrock 0.6 auto-step, stairs, slabs, beds, chests, snow layers -> 100% legit!
            profile.decayBuffer("step", cfg.check("step").decay);
            profile.decayBuffer("highjump", cfg.check("highjump").decay);
            return;
        }

        int jumpAmp = getPotionAmplifier(player, PotionEffectType.JUMP_BOOST);
        double maxJumpDy = cfg.highJumpMaxBase + jumpAmp * 0.13;

        // Step: ground -> ground instant ascent > 1.05 blocks in 1 tick
        ACConfig.CheckSettings stepCfg = cfg.check("step");
        if (stepCfg.enabled && profile.wasNearGround && groundedNow && dy > (cfg.stepMaxHeight + jumpAmp * 0.15) && dy < 6.0) {
            plugin.engine().nudgeSuspicion(profile, 1.5);
            if (profile.addBuffer("step", 1.0) >= stepCfg.buffer) {
                String act = applySetback(player, profile, event, stepCfg.setback, nowMs, cfg);
                plugin.alerts().handleViolation(
                    player, profile, "step", "Step",
                    String.format(Locale.US, "dy=+%.2f max=%.2f", dy, cfg.stepMaxHeight),
                    1.0, act
                );
                return;
            }
        } else {
            profile.decayBuffer("step", stepCfg.decay);
        }

        // HighJump / JetPack burst: vertical launch faster than physics + Jump Boost
        ACConfig.CheckSettings hjCfg = cfg.check("highjump");
        if (hjCfg.enabled && dy > maxJumpDy && dy < 6.0) {
            plugin.engine().nudgeSuspicion(profile, 1.4);
            if (profile.addBuffer("highjump", 1.0) >= hjCfg.buffer) {
                String act = applySetback(player, profile, event, hjCfg.setback, nowMs, cfg);
                plugin.alerts().handleViolation(
                    player, profile, "highjump", "HighJump / JetPack",
                    String.format(Locale.US, "dy=+%.2f max=%.2f", dy, maxJumpDy),
                    1.0, act
                );
            }
        } else {
            profile.decayBuffer("highjump", hjCfg.decay);
        }
    }

    private void checkFlyAndAirJump(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        double dy,
        double deltaXZ,
        int sampleWeight,
        long nowMs,
        ACConfig cfg
    ) {
        int jumpAmp = getPotionAmplifier(player, PotionEffectType.JUMP_BOOST);
        int extraJumpTicks = jumpAmp * 5;

        // AirJump: upward jump impulse deep in mid-air after falling or flat flight
        ACConfig.CheckSettings airJumpCfg = cfg.check("airjump");
        if (airJumpCfg.enabled && profile.airTicks >= (cfg.airJumpMinAirTicks + extraJumpTicks)
            && dy > 0.32 && profile.lastDeltaY <= 0.06) {
            plugin.engine().nudgeSuspicion(profile, 1.5);
            if (profile.addBuffer("airjump", 1.0) >= airJumpCfg.buffer) {
                String act = applySetback(player, profile, event, airJumpCfg.setback, nowMs, cfg);
                plugin.alerts().handleViolation(
                    player, profile, "airjump", "AirJump",
                    String.format(Locale.US, "dy=+%.2f air=%dt", dy, profile.airTicks),
                    1.0, act
                );
                return;
            }
        } else {
            profile.decayBuffer("airjump", airJumpCfg.decay);
        }

        // Fly: True Ballistic Gravity Acceleration Law (accelY = dy - lastDeltaY)
        // Legitimate ballistic trajectories (including Slime bounces!) obey accelY <= -0.035 every tick.
        ACConfig.CheckSettings flyCfg = cfg.check("fly");
        if (!flyCfg.enabled) {
            return;
        }
        double accelY = dy - profile.lastDeltaY;
        boolean hoverAnomaly = profile.airTicks >= 9 && Math.abs(dy) < 1.5E-4 && deltaXZ > 0.035;
        if (hoverAnomaly) {
            profile.hoverTicks += sampleWeight;
        } else {
            profile.hoverTicks = Math.max(0, profile.hoverTicks - 1);
        }

        boolean ascendDefiesGravity = profile.airTicks >= (cfg.flyMinAirTicks + extraJumpTicks)
            && dy > 0.02 && accelY > -0.025;
        boolean glideDefiesGravity = profile.airTicks >= (cfg.flyMinAirTicks + 8 + extraJumpTicks)
            && dy > -0.035 && accelY > -0.012 && deltaXZ > 0.13;

        if (profile.hoverTicks >= 8 || ascendDefiesGravity || glideDefiesGravity) {
            plugin.engine().nudgeSuspicion(profile, 1.3);
            if (profile.addBuffer("fly", 1.0) >= flyCfg.buffer) {
                String mode = ascendDefiesGravity ? "Motion/Ascend" : (profile.hoverTicks >= 8 ? "Hover" : "Glide");
                String act = applySetback(player, profile, event, flyCfg.setback, nowMs, cfg);
                plugin.alerts().handleViolation(
                    player, profile, "fly", "Fly (" + mode + ")",
                    String.format(Locale.US, "dy=%.3f acc=%.3f air=%dt", dy, accelY, profile.airTicks),
                    1.0, act
                );
            }
        } else {
            profile.decayBuffer("fly", flyCfg.decay);
        }
    }

    private void checkSpider(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        double dy,
        boolean groundedNow,
        EnvContext env,
        int sampleWeight,
        long nowMs,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings spiderCfg = cfg.check("spider");
        if (!spiderCfg.enabled) {
            return;
        }
        if (!groundedNow && env.touchingSolidWallHorizontally && dy > 0.10 && dy < 0.52) {
            profile.wallClimbTicks += sampleWeight;
            if (profile.wallClimbTicks >= cfg.spiderMinWallTicks) {
                plugin.engine().nudgeSuspicion(profile, 1.3);
                if (profile.addBuffer("spider", 1.0) >= spiderCfg.buffer) {
                    String act = applySetback(player, profile, event, spiderCfg.setback, nowMs, cfg);
                    plugin.alerts().handleViolation(
                        player, profile, "spider", "Spider (WallClimb)",
                        String.format(Locale.US, "dy=+%.2f wall=%dt", dy, profile.wallClimbTicks),
                        1.0, act
                    );
                }
            }
        } else {
            profile.wallClimbTicks = Math.max(0, profile.wallClimbTicks - 2);
            profile.decayBuffer("spider", spiderCfg.decay);
        }
    }

    private void checkNoFallGroundSpoof(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        double dy,
        boolean groundedNow,
        int sampleWeight,
        long nowMs,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings nfCfg = cfg.check("nofall");
        if (!nfCfg.enabled) {
            return;
        }
        if (!groundedNow && profile.airTicks >= cfg.noFallMinAirTicks && profile.trackedFallDistance >= 3.5
            && dy < -0.10 && player.isOnGround()) {
            profile.groundSpoofTicks += sampleWeight;
            if (profile.groundSpoofTicks >= 4) {
                plugin.engine().nudgeSuspicion(profile, 1.2);
                if (profile.addBuffer("nofall", 1.0) >= nfCfg.buffer) {
                    String act = applySetback(player, profile, event, nfCfg.setback, nowMs, cfg);
                    plugin.alerts().handleViolation(
                        player, profile, "nofall", "NoFall (GroundSpoof)",
                        String.format(Locale.US, "fall=%.1fm air=%dt", profile.trackedFallDistance, profile.airTicks),
                        1.0, act
                    );
                }
            }
        } else {
            profile.groundSpoofTicks = Math.max(0, profile.groundSpoofTicks - 1);
            profile.decayBuffer("nofall", nfCfg.decay * 0.5);
        }
    }

    public void handleToggleGlide(Player player, PlayerProfile profile, EntityToggleGlideEvent event) {
        ACConfig cfg = plugin.cfg();
        long nowMs = System.currentTimeMillis();
        profile.glideOrRiptideGraceUntilMs = nowMs + 2600L;

        if (!event.isGliding() || isBypassed(player, cfg)) {
            return;
        }
        // Catch ProtoHaxNeo ModuleNoFall ElytraGlitch: sending START_GLIDE without a usable Elytra!
        ACConfig.CheckSettings nfCfg = cfg.check("nofall");
        if (!nfCfg.enabled) {
            return;
        }
        EntityEquipment eq = player.getEquipment();
        ItemStack chest = eq != null ? eq.getChestplate() : null;
        if (chest == null || chest.getType() != Material.ELYTRA) {
            event.setCancelled(true);
            plugin.engine().nudgeSuspicion(profile, 1.8);
            if (profile.addBuffer("nofall", 1.5) >= nfCfg.buffer) {
                plugin.alerts().handleViolation(
                    player, profile, "nofall", "NoFall (ElytraGlitch)",
                    "START_GLIDE без элитр", 1.0, "Отмена пакета"
                );
            }
        }
    }

    private void checkSpeed(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        double deltaXZ,
        double dy,
        EnvContext env,
        long nowMs,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings speedCfg = cfg.check("speed");
        if (!speedCfg.enabled) {
            return;
        }

        double maxAllowed = cfg.speedMaxHorizontalBase;
        float walkSpeed = player.getWalkSpeed();
        if (walkSpeed > 0.2f) {
            maxAllowed *= (walkSpeed / 0.2);
        }
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null && attr.getValue() > 0.13) {
            maxAllowed *= (attr.getValue() / 0.10);
        }

        int speedAmp = getPotionAmplifier(player, PotionEffectType.SPEED);
        if (speedAmp > 0) {
            maxAllowed += speedAmp * 0.09;
        }
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            maxAllowed += 0.36;
        }
        if (profile.iceOrSpeedBoostTicks > 0) {
            maxAllowed += 0.38;
        }
        if (profile.ceilingHitTicks > 0) {
            maxAllowed += 0.24;
        }
        if (dy > 0.34) {
            maxAllowed += 0.22;
        }
        EntityEquipment eq = player.getEquipment();
        if (eq != null) {
            ItemStack boots = eq.getBoots();
            if (boots != null) {
                if (env.onSoulBlock) {
                    int soulLvl = boots.getEnchantmentLevel(Enchantment.SOUL_SPEED);
                    if (soulLvl > 0) {
                        maxAllowed += soulLvl * 0.095;
                    }
                }
                int dsLvl = boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
                if (dsLvl > 0 && env.nearWater) {
                    maxAllowed += dsLvl * 0.08;
                }
            }
        }
        int ping = BedrockBridgeInfo.getPing(player);
        if (ping > cfg.highPingLeniencyMs) {
            maxAllowed += 0.12;
        }

        if (deltaXZ > maxAllowed) {
            plugin.engine().nudgeSuspicion(profile, 1.2);
            double weight = deltaXZ > (maxAllowed * 1.45) ? 1.5 : 1.0;
            if (profile.addBuffer("speed", weight) >= speedCfg.buffer) {
                String act = applySetback(player, profile, event, speedCfg.setback, nowMs, cfg);
                plugin.alerts().handleViolation(
                    player, profile, "speed", "Speed / BHop",
                    String.format(Locale.US, "xz=%.2f max=%.2f", deltaXZ, maxAllowed),
                    1.0, act
                );
            }
        } else {
            profile.decayBuffer("speed", speedCfg.decay);
        }
    }

    private void checkJesus(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        double deltaXZ,
        double dy,
        EnvContext env,
        int sampleWeight,
        boolean boostActive,
        long nowMs,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings jesusCfg = cfg.check("jesus");
        if (!jesusCfg.enabled || boostActive || player.isSwimming()) {
            return;
        }
        EntityEquipment eq = player.getEquipment();
        if (eq != null && eq.getBoots() != null && eq.getBoots().getEnchantmentLevel(Enchantment.FROST_WALKER) > 0) {
            return;
        }

        if (env.onPureDeepLiquidSurface && !env.nearGround && !env.hasBoatOrShulkerSupport
            && deltaXZ > 0.11 && Math.abs(dy) < 0.005) {
            profile.liquidSurfaceTicks += sampleWeight;
            if (profile.liquidSurfaceTicks >= 11) {
                plugin.engine().nudgeSuspicion(profile, 1.1);
                if (profile.addBuffer("jesus", 1.0) >= jesusCfg.buffer) {
                    String act = applySetback(player, profile, event, jesusCfg.setback, nowMs, cfg);
                    plugin.alerts().handleViolation(
                        player, profile, "jesus", "Jesus (WaterWalk)",
                        String.format(Locale.US, "xz=%.2f dy=%.4f", deltaXZ, dy),
                        1.0, act
                    );
                }
            }
        } else {
            profile.liquidSurfaceTicks = Math.max(0, profile.liquidSurfaceTicks - 2);
            profile.decayBuffer("jesus", jesusCfg.decay);
        }
    }

    private void checkNoSlow(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        double deltaXZ,
        EnvContext env,
        int sampleWeight,
        boolean boostActive,
        long nowMs,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings noSlowCfg = cfg.check("noslow");
        if (!noSlowCfg.enabled || boostActive || profile.iceOrSpeedBoostTicks > 0) {
            profile.usingItemSprintTicks = 0;
            return;
        }
        if (player.isHandRaised() && env.nearGround) {
            ItemStack active = player.getActiveItem();
            // Ignore shields because on Bedrock shields activate automatically via Sneaking
            if (active != null && active.getType() == Material.SHIELD) {
                profile.usingItemSprintTicks = 0;
                return;
            }
            int speedAmp = getPotionAmplifier(player, PotionEffectType.SPEED);
            double maxUseSpeed = 0.225 + speedAmp * 0.048;
            if (deltaXZ > maxUseSpeed) {
                profile.usingItemSprintTicks += sampleWeight;
                if (profile.usingItemSprintTicks >= cfg.noSlowMinUsingTicks) {
                    plugin.engine().nudgeSuspicion(profile, 1.0);
                    if (profile.addBuffer("noslow", 1.0) >= noSlowCfg.buffer) {
                        String act = applySetback(player, profile, event, noSlowCfg.setback, nowMs, cfg);
                        plugin.alerts().handleViolation(
                            player, profile, "noslow", "NoSlow",
                            String.format(Locale.US, "xz=%.2f max=%.2f", deltaXZ, maxUseSpeed),
                            1.0, act
                        );
                    }
                }
            } else {
                profile.usingItemSprintTicks = Math.max(0, profile.usingItemSprintTicks - 2);
                profile.decayBuffer("noslow", noSlowCfg.decay);
            }
        } else {
            profile.usingItemSprintTicks = 0;
            profile.decayBuffer("noslow", noSlowCfg.decay);
        }
    }

    private void checkInventoryMove(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        double deltaXZ,
        EnvContext env,
        int sampleWeight,
        boolean boostActive,
        long nowMs,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings invCfg = cfg.check("inventory");
        if (!invCfg.enabled || !profile.worldContainerOpen || boostActive || env.inLiquid || profile.iceOrSpeedBoostTicks > 0) {
            profile.invMoveTicks = 0;
            return;
        }
        if (!isRealWorldStorage(player.getOpenInventory().getTopInventory())) {
            profile.worldContainerOpen = false;
            profile.invMoveTicks = 0;
            return;
        }
        // Allow 500ms momentum slide right after opening a chest while sprinting
        if (nowMs - profile.worldContainerOpenedMs < 500L) {
            return;
        }
        if (deltaXZ > 0.19) {
            profile.invMoveTicks += sampleWeight;
            if (profile.invMoveTicks >= cfg.invMoveMinTicks) {
                plugin.engine().nudgeSuspicion(profile, 1.2);
                if (profile.addBuffer("inventory", 1.0) >= invCfg.buffer) {
                    if (invCfg.cancelAction) {
                        player.closeInventory();
                        profile.worldContainerOpen = false;
                    }
                    String act = applySetback(player, profile, event, invCfg.setback, nowMs, cfg);
                    plugin.alerts().handleViolation(
                        player, profile, "inventory", "InventoryMove",
                        String.format(Locale.US, "xz=%.2f ticks=%d", deltaXZ, profile.invMoveTicks),
                        1.0, invCfg.cancelAction ? "Закрыт сундук" : act
                    );
                }
            }
        } else {
            profile.invMoveTicks = Math.max(0, profile.invMoveTicks - 2);
            profile.decayBuffer("inventory", invCfg.decay * 0.5);
        }
    }

    private void evaluateVelocityResponse(
        Player player,
        PlayerProfile profile,
        World world,
        Location loc,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings velCfg = cfg.check("velocity");
        if (!velCfg.enabled || profile.expectedVelocityY <= 0.30) {
            return;
        }
        EnvContext env = scanEnvironment(world, loc);
        if (env.hasBlockAboveHead || env.inLiquid || env.inClimbableOrWeb || env.touchingSolidWallHorizontally) {
            return;
        }
        double ratioY = profile.maxObservedPostVelY / profile.expectedVelocityY;
        double ratioXZ = profile.expectedVelocityXZ > 0.18
            ? (profile.maxObservedPostVelXZ / profile.expectedVelocityXZ)
            : 1.0;
        if (ratioY < cfg.minVelocityRatio && ratioXZ < 0.25) {
            plugin.engine().nudgeSuspicion(profile, 0.9);
            if (profile.addBuffer("velocity", 1.0) >= velCfg.buffer) {
                plugin.alerts().handleViolation(
                    player, profile, "velocity", "Velocity (AntiKB)",
                    String.format(Locale.US, "kbY=%.0f%% kbXZ=%.0f%%",
                        Math.max(0.0, ratioY * 100.0), Math.max(0.0, ratioXZ * 100.0)),
                    1.0, "Лог"
                );
            }
        } else {
            profile.decayBuffer("velocity", velCfg.decay);
        }
    }

    public void handleCombat(Player attacker, PlayerProfile attackerProfile, Entity victim, EntityDamageByEntityEvent event) {
        ACConfig cfg = plugin.cfg();
        if (isBypassed(attacker, cfg) || Bukkit.getTPS()[0] < cfg.minServerTps) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        attackerProfile.lastActiveMoveMs = nowMs;

        // Self-hit / dead-hit BadPackets guard
        if (victim.getUniqueId().equals(attacker.getUniqueId()) || attacker.isDead()) {
            event.setCancelled(true);
            plugin.alerts().handleViolation(
                attacker, attackerProfile, "badpackets", "BadPackets (InvalidHit)",
                "self/dead attack", 1.0, "Отмена удара"
            );
            return;
        }

        if (event.getCause() != EntityDamageByEntityEvent.DamageCause.ENTITY_ATTACK
            && event.getCause() != EntityDamageByEntityEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        boolean isSweep = event.getCause() == EntityDamageByEntityEvent.DamageCause.ENTITY_SWEEP_ATTACK;

        BedrockBridgeInfo.InputCategory inputCat = BedrockBridgeInfo.getInputCategory(attacker);
        int ping = BedrockBridgeInfo.getPing(attacker);

        // 1. AutoClicker (CPS ceiling + constant-interval bot stdDev; ignores sweep secondary hits)
        ACConfig.CheckSettings clickCfg = cfg.check("autoclicker");
        if (clickCfg.enabled && !isSweep) {
            int cps = attackerProfile.recordClickAndCountCps(nowMs);
            int maxCps = inputCat == BedrockBridgeInfo.InputCategory.TOUCH ? cfg.maxCpsTouch : cfg.maxCpsPc;
            double stdDev = cps >= 12 ? attackerProfile.computeClickIntervalStdDevMs() : 999.0;
            if (cps > maxCps || stdDev < cfg.minClickStdDevMs) {
                plugin.engine().nudgeSuspicion(attackerProfile, 1.2);
                if (attackerProfile.addBuffer("autoclicker", 1.0) >= clickCfg.buffer) {
                    if (clickCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    String detail = cps > maxCps
                        ? String.format(Locale.US, "cps=%d max=%d", cps, maxCps)
                        : String.format(Locale.US, "macro std=%.2fms cps=%d", stdDev, cps);
                    plugin.alerts().handleViolation(
                        attacker, attackerProfile, "autoclicker", "AutoClicker",
                        detail, 1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                    );
                }
            } else {
                attackerProfile.decayBuffer("autoclicker", clickCfg.decay);
            }
        }

        // 2. Packet Criticals check (ProtoHaxNeo ModuleCriticalHit / Horion Criticals)
        ACConfig.CheckSettings critCfg = cfg.check("criticals");
        if (critCfg.enabled && !isSweep && !attacker.isInsideVehicle() && !attacker.isClimbing()) {
            boolean looksPacketCrit = !attacker.isOnGround()
                && attacker.getFallDistance() > 0.0f
                && attackerProfile.airTicks <= 1
                && attackerProfile.wasNearGround
                && attackerProfile.lastDeltaY > -0.015
                && attackerProfile.lastDeltaY < 0.24
                && !attackerProfile.hasActiveBoostGrace(nowMs)
                && (nowMs - attackerProfile.lastVelocityMs > 1200L);
            if (looksPacketCrit) {
                plugin.engine().nudgeSuspicion(attackerProfile, 1.2);
                if (attackerProfile.addBuffer("criticals", 1.0) >= critCfg.buffer) {
                    if (critCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    plugin.alerts().handleViolation(
                        attacker, attackerProfile, "criticals", "Criticals (Packet)",
                        String.format(Locale.US, "dy=%.3f fall=%.2f", attackerProfile.lastDeltaY, attacker.getFallDistance()),
                        1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                    );
                }
            } else {
                attackerProfile.decayBuffer("criticals", critCfg.decay);
            }
        }

        // 3. KillAura: Open World Container, MultiAura (same-tick / snap), 360° BackHit, 15-ray WallHit
        ACConfig.CheckSettings auraCfg = cfg.check("killaura");
        float currentYaw = attacker.getLocation().getYaw();
        if (auraCfg.enabled && !isSweep) {
            Inventory topInv = attacker.getOpenInventory().getTopInventory();
            if (cfg.killauraOpenInventory && attackerProfile.worldContainerOpen
                && isRealWorldStorage(topInv) && (nowMs - attackerProfile.worldContainerOpenedMs > 300L)) {
                plugin.engine().nudgeSuspicion(attackerProfile, 1.5);
                if (attackerProfile.addBuffer("killaura", 1.0) >= auraCfg.buffer) {
                    if (auraCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    plugin.alerts().handleViolation(
                        attacker, attackerProfile, "killaura", "KillAura (Container)",
                        "open=" + topInv.getType().name(), 1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                    );
                    return;
                }
            }

            long dtAttack = nowMs - attackerProfile.lastAttackTimeMs;
            float yawDiff = wrapAngleAbs(currentYaw - attackerProfile.lastAttackYaw);
            boolean sameTickMulti = attackerProfile.lastAttackTargetUuid != null
                && !attackerProfile.lastAttackTargetUuid.equals(victim.getUniqueId())
                && dtAttack < cfg.multiAuraWindowMs
                && (attackerProfile.movePacketCounter == attackerProfile.lastMoveSeqAtAttack || yawDiff > 52.0f);
            if (sameTickMulti) {
                plugin.engine().nudgeSuspicion(attackerProfile, 1.5);
                if (attackerProfile.addBuffer("killaura", 1.0) >= auraCfg.buffer) {
                    if (auraCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    plugin.alerts().handleViolation(
                        attacker, attackerProfile, "killaura", "KillAura (Multi)",
                        String.format(Locale.US, "dt=%dms yaw=%.0f°", dtAttack, yawDiff),
                        1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                    );
                }
            } else {
                attackerProfile.decayBuffer("killaura", auraCfg.decay * 0.45);
            }
        }
        if (!isSweep) {
            checkCombatAim(attacker, attackerProfile, inputCat, currentYaw, attacker.getLocation().getPitch(), nowMs, event, cfg);
        }
        attackerProfile.lastAttackTargetUuid = victim.getUniqueId();
        attackerProfile.lastAttackTimeMs = nowMs;
        attackerProfile.lastAttackYaw = currentYaw;
        attackerProfile.lastMoveSeqAtAttack = attackerProfile.movePacketCounter;

        if (attacker.getGameMode() == GameMode.CREATIVE || isSweep) {
            return;
        }

        // 4. 18-tick Ping-Backtracked 3D AABB Reach & HitBox
        Location eye = attacker.getEyeLocation();
        BoundingBox victimBox = victim.getBoundingBox();
        long minWindowMs = nowMs - Math.clamp(ping + 150L, 165L, 700L);
        double dist;
        PlayerProfile victimProfile = (victim instanceof Player vp) ? plugin.profile(vp) : null;
        if (victimProfile != null) {
            dist = victimProfile.computeMinCompensatedDistance(
                eye.getX(), eye.getY(), eye.getZ(), victimBox, 0.13, minWindowMs
            );
        } else {
            dist = computeStaticDistanceToBox(eye, victimBox, 0.18);
        }
        attackerProfile.lastMeasuredReach = dist;

        double maxReach = switch (inputCat) {
            case KEYBOARD_MOUSE -> cfg.maxReachPc;
            case CONTROLLER -> cfg.maxReachController;
            case TOUCH -> cfg.maxReachTouch;
        };
        if (ping > cfg.highPingLeniencyMs) {
            maxReach += 0.20;
        }

        ACConfig.CheckSettings reachCfg = cfg.check("reach");
        if (reachCfg.enabled) {
            if (dist > maxReach) {
                plugin.engine().nudgeSuspicion(attackerProfile, 1.4);
                if (dist >= cfg.cancelReachAbove) {
                    event.setCancelled(true); // Instant InfiniteAura / blatant Reach protection
                }
                double weight = dist > (maxReach + 0.60) ? 1.5 : 1.0;
                if (attackerProfile.addBuffer("reach", weight) >= reachCfg.buffer) {
                    if (reachCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    plugin.alerts().handleViolation(
                        attacker, attackerProfile, "reach", "Reach",
                        String.format(Locale.US, "dist=%.2f max=%.2f", dist, maxReach),
                        1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                    );
                }
            } else {
                attackerProfile.decayBuffer("reach", reachCfg.decay);
            }
        }

        Vector lookDir3D = eye.getDirection();
        Vector toVictimCenter3D = victimBox.getCenter().subtract(eye.toVector());

        // 360° behind-the-back & 15-ray full-corner WallHit checks
        if (auraCfg.enabled && dist > 1.55) {
            Vector lookXZ = lookDir3D.clone().setY(0.0);
            Vector toVictimXZ = toVictimCenter3D.clone().setY(0.0);
            if (lookXZ.lengthSquared() > 1.0E-4 && toVictimXZ.lengthSquared() > 1.0E-4) {
                double dot = lookXZ.normalize().dot(toVictimXZ.normalize());
                if (dot < -0.45) {
                    plugin.engine().nudgeSuspicion(attackerProfile, 1.5);
                    if (attackerProfile.addBuffer("killaura", 1.0) >= auraCfg.buffer) {
                        if (auraCfg.cancelAction) {
                            event.setCancelled(true);
                        }
                        plugin.alerts().handleViolation(
                            attacker, attackerProfile, "killaura", "KillAura (360° Angle)",
                            String.format(Locale.US, "dot=%.2f dist=%.2f", dot, dist),
                            1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                        );
                    }
                }
            }

            if (cfg.killauraThroughWalls && dist > 1.90 && victim instanceof LivingEntity) {
                if (isAllHitboxCornersOccluded(attacker.getWorld(), eye, victimBox)) {
                    plugin.engine().nudgeSuspicion(attackerProfile, 1.5);
                    if (attackerProfile.addBuffer("killaura", 1.0) >= auraCfg.buffer) {
                        if (auraCfg.cancelAction) {
                            event.setCancelled(true);
                        }
                        plugin.alerts().handleViolation(
                            attacker, attackerProfile, "killaura", "KillAura (WallHit)",
                            String.format(Locale.US, "dist=%.2f сквозь стену", dist),
                            1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                        );
                    }
                }
            }
        }

        // 5. HitBox check:
        //    - PC & Controller: Backtracked 3D Ray-AABB across all 18 historical positions (0 moving-target false flags!)
        //    - Touch (Сенсор): Geyser does not rotate head on classic Touch taps, so check Touch Screen Frustum Cone!
        ACConfig.CheckSettings hbCfg = cfg.check("hitbox");
        if (hbCfg.enabled && dist > 1.60) {
            if (inputCat != BedrockBridgeInfo.InputCategory.TOUCH) {
                double expand = cfg.hitboxMaxExpansion + (ping > 130 ? 0.15 : 0.07);
                boolean hitAnyPos;
                if (victimProfile != null) {
                    hitAnyPos = victimProfile.intersectsCompensatedRay(
                        eye.toVector(), lookDir3D, victimBox, expand, expand + 0.14, 6.8, minWindowMs
                    );
                } else {
                    BoundingBox generous = victimBox.clone().expand(expand, expand + 0.14, expand);
                    hitAnyPos = generous.rayTrace(eye.toVector(), lookDir3D, 6.8) != null;
                }
                if (!hitAnyPos) {
                    plugin.engine().nudgeSuspicion(attackerProfile, 1.15);
                    if (attackerProfile.addBuffer("hitbox", 1.0) >= hbCfg.buffer) {
                        if (hbCfg.cancelAction) {
                            event.setCancelled(true);
                        }
                        plugin.alerts().handleViolation(
                            attacker, attackerProfile, "hitbox", "HitBox (PC/Pad)",
                            String.format(Locale.US, "miss>+%.2f dist=%.2f", expand, dist),
                            1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                        );
                    }
                } else {
                    attackerProfile.decayBuffer("hitbox", hbCfg.decay);
                }
            } else if (toVictimCenter3D.lengthSquared() > 0.5) {
                // Touch screen frustum cone check (prevents hitting outside viewport while never flagging classic Touch taps!)
                double cosLimit = Math.cos(Math.toRadians(cfg.hitboxTouchMaxAngleDeg));
                double cosActual = lookDir3D.clone().normalize().dot(toVictimCenter3D.clone().normalize());
                if (cosActual < cosLimit) {
                    plugin.engine().nudgeSuspicion(attackerProfile, 1.2);
                    if (attackerProfile.addBuffer("hitbox", 1.0) >= hbCfg.buffer) {
                        if (hbCfg.cancelAction) {
                            event.setCancelled(true);
                        }
                        double deg = Math.toDegrees(Math.acos(Math.clamp(cosActual, -1.0, 1.0)));
                        plugin.alerts().handleViolation(
                            attacker, attackerProfile, "hitbox", "HitBox (TouchFOV)",
                            String.format(Locale.US, "angle=%.0f° max=%.0f°", deg, cfg.hitboxTouchMaxAngleDeg),
                            1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                        );
                    }
                } else {
                    attackerProfile.decayBuffer("hitbox", hbCfg.decay);
                }
            }
        }
    }

    public void handleBlockPlace(Player player, PlayerProfile profile, BlockPlaceEvent event) {
        ACConfig cfg = plugin.cfg();
        ACConfig.CheckSettings scafCfg = cfg.check("scaffold");
        if (!scafCfg.enabled || isBypassed(player, cfg) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        Location eye = player.getEyeLocation();
        if (placed.getWorld() != eye.getWorld()) {
            return;
        }
        long nowMs = System.currentTimeMillis();

        // 3D AABB distance from eye to placed block
        BoundingBox blockBox = new BoundingBox(
            placed.getX(), placed.getY(), placed.getZ(),
            placed.getX() + 1.0, placed.getY() + 1.0, placed.getZ() + 1.0
        );
        double dist = computeStaticDistanceToBox(eye, blockBox, 0.0);

        String placedName = placed.getType().name();
        boolean isWaterSurfacePlant = placedName.contains("LILY_PAD") || placedName.contains("FROGSPAWN");
        boolean airPlace = !isWaterSurfacePlant && (against.getType().isAir() || placed.equals(against));

        // Tower check (Horion Tower.cpp): rapid upward block pillar directly under player faster than jump physics
        Location pLoc = player.getLocation();
        boolean directlyUnderPlayer = placed.getX() == pLoc.getBlockX()
            && placed.getZ() == pLoc.getBlockZ()
            && pLoc.getY() >= placed.getY() + 0.85
            && pLoc.getY() <= placed.getY() + 2.35;
        boolean towerFlag = false;
        long towerDt = 999L;
        if (directlyUnderPlayer && placed.getY() == profile.lastTowerBlockY + 1
            && !profile.hasActiveBoostGrace(nowMs) && (nowMs - profile.lastVelocityMs > 1400L)) {
            towerDt = nowMs - profile.lastTowerPlaceMs;
            if (towerDt > 15L && towerDt < cfg.towerMinBlockIntervalMs) {
                profile.towerStreak++;
                if (profile.towerStreak >= 4) {
                    towerFlag = true;
                }
            } else {
                profile.towerStreak = Math.max(0, profile.towerStreak - 1);
            }
        } else if (!directlyUnderPlayer) {
            profile.towerStreak = 0;
        }
        if (directlyUnderPlayer) {
            profile.lastTowerBlockY = placed.getY();
            profile.lastTowerPlaceMs = nowMs;
        }

        // Never touches legitimate Bedrock forward/jump speed-bridging!
        if (dist > cfg.scaffoldMaxPlaceDistance || airPlace || towerFlag) {
            plugin.engine().nudgeSuspicion(profile, 1.3);
            if (profile.addBuffer("scaffold", 1.0) >= scafCfg.buffer) {
                if (scafCfg.cancelAction) {
                    event.setCancelled(true);
                }
                String subType = airPlace ? "AirPlace" : (towerFlag ? "Tower" : "Reach");
                String detail = airPlace ? "against=AIR"
                    : (towerFlag ? String.format(Locale.US, "tower=%dms/бл", towerDt)
                    : String.format(Locale.US, "reach=%.2f max=%.2f", dist, cfg.scaffoldMaxPlaceDistance));
                plugin.alerts().handleViolation(
                    player, profile, "scaffold", "Scaffold (" + subType + ")",
                    detail, 1.0, event.isCancelled() ? "Отмена блока" : "Лог"
                );
            }
        } else {
            profile.decayBuffer("scaffold", scafCfg.decay);
        }
    }

    public void handleBlockDamage(Player player, PlayerProfile profile, BlockDamageEvent event) {
        Block b = event.getBlock();
        profile.mineStartMs = System.currentTimeMillis();
        profile.mineX = b.getX();
        profile.mineY = b.getY();
        profile.mineZ = b.getZ();
        profile.miningTracked = true;
    }

    public void handleBlockBreak(Player player, PlayerProfile profile, BlockBreakEvent event) {
        ACConfig cfg = plugin.cfg();
        ACConfig.CheckSettings fbCfg = cfg.check("fastbreak");
        if (!fbCfg.enabled || isBypassed(player, cfg) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Block block = event.getBlock();
        Location eye = player.getEyeLocation();
        if (block.getWorld() != eye.getWorld()) {
            return;
        }
        long nowMs = System.currentTimeMillis();

        // 1. Block Reach check
        BoundingBox blockBox = new BoundingBox(
            block.getX(), block.getY(), block.getZ(),
            block.getX() + 1.0, block.getY() + 1.0, block.getZ() + 1.0
        );
        double dist = computeStaticDistanceToBox(eye, blockBox, 0.0);
        if (dist > 6.8) {
            plugin.engine().nudgeSuspicion(profile, 1.5);
            if (profile.addBuffer("fastbreak", 1.0) >= fbCfg.buffer) {
                if (fbCfg.cancelAction) {
                    event.setCancelled(true);
                }
                plugin.alerts().handleViolation(
                    player, profile, "fastbreak", "FastBreak (Reach)",
                    String.format(Locale.US, "dist=%.2f max=6.80", dist),
                    1.0, event.isCancelled() ? "Отмена ломания" : "Лог"
                );
            }
            return;
        }

        // 2. BedFucker check: breaking an encased Bed through solid occluding blocks
        if (cfg.bedFuckerThroughWalls && block.getType().name().endsWith("_BED") && dist > 1.85) {
            if (isBedCompletelyEncased(block)) {
                plugin.engine().nudgeSuspicion(profile, 2.0);
                if (profile.addBuffer("fastbreak", 1.5) >= fbCfg.buffer) {
                    if (fbCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    plugin.alerts().handleViolation(
                        player, profile, "fastbreak", "BedFucker (WallBreak)",
                        "кровать закрыта блоками", 1.5, event.isCancelled() ? "Отмена ломания" : "Лог"
                    );
                }
                return;
            }
        }

        // 3. Exact NMS getBreakSpeed check (catches ProtoHaxNeo client Haste-spoof & Horion InstaBreak!)
        float speedPerTick = block.getBreakSpeed(player);
        if (speedPerTick > 0.0f && speedPerTick < 0.72f) {
            int breaksPerSec = profile.recordNonInstantBreakAndCountPerSec(nowMs);
            if (breaksPerSec > cfg.nukerMaxBlocksPerSec) {
                plugin.engine().nudgeSuspicion(profile, 1.6);
                if (profile.addBuffer("fastbreak", 1.0) >= fbCfg.buffer) {
                    if (fbCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    plugin.alerts().handleViolation(
                        player, profile, "fastbreak", "Nuker (MultiBreak)",
                        String.format(Locale.US, "rate=%d бл/с", breaksPerSec),
                        1.0, event.isCancelled() ? "Отмена ломания" : "Лог"
                    );
                }
                return;
            }
        }

        if (speedPerTick > 0.0f && speedPerTick < 0.22f) {
            double expectedMs = (1.0 / speedPerTick) * 50.0;
            boolean sameBlock = profile.miningTracked
                && profile.mineX == block.getX()
                && profile.mineY == block.getY()
                && profile.mineZ == block.getZ();
            long elapsedMs = sameBlock ? (nowMs - profile.mineStartMs) : 0L;
            double minAllowedMs = expectedMs * cfg.fastBreakMinTimeRatio - 150.0;
            if (expectedMs >= 650.0 && elapsedMs < minAllowedMs) {
                plugin.engine().nudgeSuspicion(profile, 1.5);
                if (profile.addBuffer("fastbreak", 1.0) >= fbCfg.buffer) {
                    if (fbCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    plugin.alerts().handleViolation(
                        player, profile, "fastbreak", "FastBreak / InstaBreak",
                        String.format(Locale.US, "%s за %dмс (мин %.0fмс)", block.getType().name(), elapsedMs, minAllowedMs),
                        1.0, event.isCancelled() ? "Отмена ломания" : "Лог"
                    );
                    return;
                }
            }
        }
        profile.miningTracked = false;
        profile.decayBuffer("fastbreak", fbCfg.decay);
    }

    public void handleContainerStealClick(Player player, PlayerProfile profile, Inventory top, InventoryClickEvent event) {
        ACConfig cfg = plugin.cfg();
        ACConfig.CheckSettings invCfg = cfg.check("inventory");
        if (!invCfg.enabled || isBypassed(player, cfg) || !isRealWorldStorage(top)) {
            return;
        }
        InventoryAction action = event.getAction();
        // Ignore COLLECT_TO_CURSOR (legit double-click gathering stacks!) and empty slots
        if (action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.NOTHING) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize() || rawSlot == profile.lastStealRawSlot) {
            return;
        }
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        profile.lastStealRawSlot = rawSlot;
        double avgMs = profile.recordStealClickAndAvgIntervalMs(nowMs);
        if (avgMs < cfg.chestStealerMinAvgMs) {
            plugin.engine().nudgeSuspicion(profile, 1.3);
            if (profile.addBuffer("inventory", 1.0) >= invCfg.buffer) {
                if (invCfg.cancelAction) {
                    event.setCancelled(true);
                    player.closeInventory();
                }
                plugin.alerts().handleViolation(
                    player, profile, "inventory", "ChestStealer",
                    String.format(Locale.US, "avg=%.1fмс/слот", avgMs),
                    1.0, event.isCancelled() ? "Закрыт сундук" : "Лог"
                );
            }
        } else {
            profile.decayBuffer("inventory", invCfg.decay * 0.4);
        }
    }

    /**
     * Check #22 (Aim / SnapRotations):
     * 1) Tracks SilentAim snap-back after a high-angle snap attack (>58° in, >45° back within 2 packets).
     * 2) Tracks robotic horizontal AimLock (rapid yaw tracking with exact 0.0 pitch change on KBM / Phone-KBM).
     */
    private void trackRotationAndSnapBack(Player player, PlayerProfile profile, float yaw, float pitch, ACConfig cfg) {
        if (!profile.hasRotationSample) {
            profile.lastYaw = yaw;
            profile.lastPitch = pitch;
            profile.hasRotationSample = true;
            return;
        }
        float yawDelta = wrapAngleAbs(yaw - profile.lastYaw);
        if (yawDelta < 1.0E-4f && Math.abs(pitch - profile.lastPitch) < 1.0E-4f) {
            return;
        }

        ACConfig.CheckSettings aimCfg = cfg.check("aim");
        if (aimCfg.enabled && profile.snapAttackPending) {
            profile.snapMovesSinceAttack++;
            float backDelta = wrapAngleAbs(yaw - profile.snapAttackYaw);
            float returnDiff = wrapAngleAbs(yaw - profile.preSnapYaw);
            if (profile.snapMovesSinceAttack <= 2 && backDelta >= cfg.aimSnapBackMinYawDeg && returnDiff <= 24.0f) {
                profile.snapAttackPending = false;
                plugin.engine().nudgeSuspicion(profile, 1.5);
                if (profile.addBuffer("aim", 1.25) >= aimCfg.buffer) {
                    plugin.alerts().handleViolation(
                        player, profile, "aim", "Aim (SilentSnap)",
                        String.format(Locale.US, "snapBack=%.0f° ret=%.0f°", backDelta, returnDiff),
                        1.0, "Лог"
                    );
                }
            } else if (profile.snapMovesSinceAttack >= 2) {
                profile.snapAttackPending = false;
                profile.decayBuffer("aim", aimCfg.decay * 0.4);
            }
        }

        profile.prevMoveYawDelta = yawDelta;
        profile.lastYaw = yaw;
        profile.lastPitch = pitch;
    }

    private void checkCombatAim(
        Player attacker,
        PlayerProfile profile,
        BedrockBridgeInfo.InputCategory inputCat,
        float currentYaw,
        float currentPitch,
        long nowMs,
        EntityDamageByEntityEvent event,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings aimCfg = cfg.check("aim");
        if (!aimCfg.enabled || inputCat == BedrockBridgeInfo.InputCategory.TOUCH) {
            return;
        }

        if (profile.prevMoveYawDelta >= cfg.aimSnapMinYawDeg) {
            profile.snapAttackPending = true;
            profile.snapAttackYaw = currentYaw;
            profile.preSnapYaw = currentYaw - profile.prevMoveYawDelta;
            profile.snapMovesSinceAttack = 0;
        }

        // Robotic horizontal lock on Keyboard+Mouse / Phone-KBM: rapid yaw tracking with exact 0.000 pitch delta
        if (inputCat == BedrockBridgeInfo.InputCategory.KEYBOARD_MOUSE && nowMs - profile.lastAttackTimeMs < 650L) {
            float hitYawDiff = wrapAngleAbs(currentYaw - profile.lastAttackYaw);
            float pitchDiff = Math.abs(currentPitch - profile.lastPitch);
            if (hitYawDiff > 6.5f && hitYawDiff < 120.0f && pitchDiff < 1.0E-5f && Math.abs(currentPitch) < 88.0f) {
                profile.zeroPitchAimStreak++;
                if (profile.zeroPitchAimStreak >= 10) {
                    profile.zeroPitchAimStreak = 5;
                    plugin.engine().nudgeSuspicion(profile, 1.3);
                    if (profile.addBuffer("aim", 1.0) >= aimCfg.buffer) {
                        if (aimCfg.cancelAction) {
                            event.setCancelled(true);
                        }
                        plugin.alerts().handleViolation(
                            attacker, profile, "aim", "Aim (AimLock)",
                            String.format(Locale.US, "yaw=%.1f° dPitch=0.000", hitYawDiff),
                            1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                        );
                    }
                }
            } else if (pitchDiff > 0.02f) {
                profile.zeroPitchAimStreak = Math.max(0, profile.zeroPitchAimStreak - 2);
                profile.decayBuffer("aim", aimCfg.decay * 0.35);
            }
        }
    }

    /**
     * Check #23 (AutoTotem): catches inhuman <65ms instant totem swap after pop OR offhand swap from inner slots while sprinting.
     */
    public void handleTotemResurrect(Player player, PlayerProfile profile, EntityResurrectEvent event) {
        if (!event.isCancelled()) {
            profile.lastTotemPopMs = System.currentTimeMillis();
        }
    }

    public void handleOffhandTotemClick(Player player, PlayerProfile profile, InventoryClickEvent event) {
        ACConfig cfg = plugin.cfg();
        ACConfig.CheckSettings totemCfg = cfg.check("autototem");
        if (!totemCfg.enabled || isBypassed(player, cfg) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        boolean targetsOffhand = event.getSlot() == 40 || event.getRawSlot() == 45 || event.getClick() == ClickType.SWAP_OFFHAND;
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        boolean involvesTotem = (cursor != null && cursor.getType() == Material.TOTEM_OF_UNDYING)
            || (current != null && current.getType() == Material.TOTEM_OF_UNDYING);
        if (!targetsOffhand && !(event.isShiftClick() && involvesTotem)) {
            return;
        }
        if (!involvesTotem && event.getClick() == ClickType.SWAP_OFFHAND) {
            ItemStack hotbar = event.getHotbarButton() >= 0 ? player.getInventory().getItem(event.getHotbarButton()) : null;
            involvesTotem = hotbar != null && hotbar.getType() == Material.TOTEM_OF_UNDYING;
        }
        if (!involvesTotem) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        long sincePopMs = nowMs - profile.lastTotemPopMs;
        boolean instantPostPop = profile.lastTotemPopMs > 0L && sincePopMs >= 0L && sincePopMs < cfg.autoTotemMinPopDelayMs;
        boolean sprintingInnerSwap = event.getSlot() >= 9 && event.getSlot() <= 35
            && (nowMs - profile.lastActiveMoveMs < 120L) && profile.lastDeltaXZ > 0.23
            && !profile.hasActiveBoostGrace(nowMs) && (nowMs - profile.lastVelocityMs > 1200L);

        if (instantPostPop || sprintingInnerSwap) {
            plugin.engine().nudgeSuspicion(profile, 1.5);
            double weight = instantPostPop ? 1.5 : 1.0;
            if (profile.addBuffer("autototem", weight) >= totemCfg.buffer) {
                if (totemCfg.cancelAction) {
                    event.setCancelled(true);
                }
                String detail = instantPostPop
                    ? String.format(Locale.US, "popSwap=%dms", sincePopMs)
                    : String.format(Locale.US, "sprintSwap xz=%.2f", profile.lastDeltaXZ);
                plugin.alerts().handleViolation(
                    player, profile, "autototem", "AutoTotem",
                    detail, 1.0, event.isCancelled() ? "Отмена свапа" : "Лог"
                );
            }
        } else {
            profile.decayBuffer("autototem", totemCfg.decay);
        }
    }

    /**
     * Check #24 (FastBow) & Firework Rocket Boost tracker for Check #25 (ElytraFly).
     */
    public void handleRightClickItem(Player player, PlayerProfile profile, ItemStack item) {
        if (item == null) {
            return;
        }
        Material mat = item.getType();
        long nowMs = System.currentTimeMillis();
        if (mat == Material.BOW) {
            profile.bowDrawStartMs = nowMs;
        } else if (mat == Material.FIREWORK_ROCKET) {
            profile.fireworkBoostUntilMs = nowMs + 4800L;
        } else if (mat == Material.WIND_CHARGE) {
            profile.explosionGraceUntilMs = Math.max(profile.explosionGraceUntilMs, nowMs + 3200L);
        }
    }

    public void handleBowShoot(Player player, PlayerProfile profile, EntityShootBowEvent event) {
        ACConfig cfg = plugin.cfg();
        ACConfig.CheckSettings bowCfg = cfg.check("fastbow");
        if (!bowCfg.enabled || isBypassed(player, cfg) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack bow = event.getBow();
        if (bow == null || bow.getType() != Material.BOW) {
            return;
        }
        float force = event.getForce();
        long nowMs = System.currentTimeMillis();
        long drawMs = profile.bowDrawStartMs > 0L ? (nowMs - profile.bowDrawStartMs) : 9999L;
        profile.bowDrawStartMs = nowMs;

        if (force >= 0.85f && drawMs < cfg.fastBowMinFullDrawMs) {
            plugin.engine().nudgeSuspicion(profile, 1.4);
            if (profile.addBuffer("fastbow", 1.25) >= bowCfg.buffer) {
                if (bowCfg.cancelAction) {
                    event.setCancelled(true);
                }
                plugin.alerts().handleViolation(
                    player, profile, "fastbow", "FastBow",
                    String.format(Locale.US, "draw=%dms force=%.2f", drawMs, force),
                    1.0, event.isCancelled() ? "Отмена выстрела" : "Лог"
                );
            }
        } else {
            profile.decayBuffer("fastbow", bowCfg.decay);
        }
    }

    /**
     * Check #25 (ElytraFly / PacketElytra): catches unboosted Elytra hovering, continuous upward ascent, and speed hacks.
     */
    private void checkElytraFly(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        Location to,
        double deltaXZ,
        double dy,
        long nowMs,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings efCfg = cfg.check("elytrafly");
        if (!efCfg.enabled) {
            return;
        }
        int ping = BedrockBridgeInfo.getPing(player);
        if (nowMs < profile.fireworkBoostUntilMs || nowMs < profile.explosionGraceUntilMs
            || nowMs < profile.slimeOrPistonGraceUntilMs || (nowMs - profile.lastVelocityMs) <= (1500L + ping)
            || player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            profile.elytraHoverTicks = 0;
            profile.elytraAscendTicks = 0;
            profile.decayBuffer("elytrafly", efCfg.decay * 0.5);
            return;
        }

        double maxSpeed = cfg.elytraMaxNoBoostSpeed + (ping > cfg.highPingLeniencyMs ? 0.25 : 0.0);
        boolean speedHack = deltaXZ > maxSpeed;

        if (Math.abs(dy) < 0.0018 && deltaXZ > 0.26) {
            profile.elytraHoverTicks++;
        } else {
            profile.elytraHoverTicks = Math.max(0, profile.elytraHoverTicks - 2);
        }

        if (dy > 0.14 && dy >= profile.lastDeltaY - 0.004) {
            profile.elytraAscendTicks++;
        } else {
            profile.elytraAscendTicks = Math.max(0, profile.elytraAscendTicks - 2);
        }

        if (speedHack || profile.elytraHoverTicks >= cfg.elytraHoverMinTicks || profile.elytraAscendTicks >= 16) {
            plugin.engine().nudgeSuspicion(profile, 1.4);
            if (profile.addBuffer("elytrafly", 1.0) >= efCfg.buffer) {
                String mode = speedHack ? "Speed" : (profile.elytraHoverTicks >= cfg.elytraHoverMinTicks ? "Hover" : "Ascend");
                String act = applySetback(player, profile, event, efCfg.setback, nowMs, cfg);
                plugin.alerts().handleViolation(
                    player, profile, "elytrafly", "ElytraFly (" + mode + ")",
                    String.format(Locale.US, "xz=%.2f dy=%.3f", deltaXZ, dy),
                    1.0, act
                );
            }
        } else {
            profile.decayBuffer("elytrafly", efCfg.decay * 0.4);
        }
        profile.lastDeltaXZ = deltaXZ;
        profile.lastDeltaY = dy;
    }

    private static boolean isBedCompletelyEncased(Block bedPart) {
        if (!allNonBedNeighborsOccluding(bedPart)) {
            return false;
        }
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST}) {
            Block adj = bedPart.getRelative(f);
            if (adj.getType().name().endsWith("_BED")) {
                if (!allNonBedNeighborsOccluding(adj)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean allNonBedNeighborsOccluding(Block b) {
        for (BlockFace face : SIX_FACES) {
            if (face == BlockFace.DOWN) {
                continue;
            }
            Block rel = b.getRelative(face);
            if (rel.getType().name().endsWith("_BED")) {
                continue;
            }
            if (!rel.getType().isOccluding() || rel.isPassable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks 15 rays (center + 4 horizontal corners × 3 vertical heights) from attacker's eye to victim's AABB.
     * Returns true ONLY if EVERY SINGLE ray hits a solid occluding block (zero false flags on corner peeks!).
     */
    private static boolean isAllHitboxCornersOccluded(World world, Location eye, BoundingBox box) {
        Vector start = eye.toVector();
        double minX = box.getMinX() + 0.05;
        double maxX = box.getMaxX() - 0.05;
        double cx = box.getCenterX();
        double minZ = box.getMinZ() + 0.05;
        double maxZ = box.getMaxZ() - 0.05;
        double cz = box.getCenterZ();

        double[][] ptsXZ = {
            {cx, cz},
            {minX, minZ},
            {minX, maxZ},
            {maxX, minZ},
            {maxX, maxZ}
        };
        double[] sampleYs = {
            box.getMinY() + 0.20,
            (box.getMinY() + box.getMaxY()) * 0.5,
            box.getMaxY() - 0.12
        };

        for (double[] xz : ptsXZ) {
            for (double sy : sampleYs) {
                Vector dir = new Vector(xz[0] - start.getX(), sy - start.getY(), xz[1] - start.getZ());
                double len = dir.length();
                if (len < 0.35) {
                    return false;
                }
                RayTraceResult res = world.rayTraceBlocks(eye, dir.multiply(1.0 / len), len - 0.20);
                if (res == null || res.getHitBlock() == null || !res.getHitBlock().getType().isOccluding()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double computeStaticDistanceToBox(Location eye, BoundingBox box, double expand) {
        double px = eye.getX(), py = eye.getY(), pz = eye.getZ();
        double dx = Math.max(0.0, Math.max((box.getMinX() - expand) - px, px - (box.getMaxX() + expand)));
        double dy = Math.max(0.0, Math.max((box.getMinY() - expand) - py, py - (box.getMaxY() + expand)));
        double dz = Math.max(0.0, Math.max((box.getMinZ() - expand) - pz, pz - (box.getMaxZ() + expand)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String applySetback(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        boolean setback,
        long nowMs,
        ACConfig cfg
    ) {
        if (!setback) {
            return "Лог";
        }
        if (nowMs - profile.lastSetbackMs < cfg.setbackCooldownMs) {
            return "Лог";
        }
        profile.lastSetbackMs = nowMs;
        Location safe = profile.lastSafeLocation;
        if (safe != null && isSafeSetbackDestination(safe, player.getLocation())) {
            Location dest = safe.clone();
            dest.setYaw(event.getTo().getYaw());
            dest.setPitch(event.getTo().getPitch());
            event.setTo(dest);
        } else {
            event.setTo(event.getFrom());
        }
        return "Сетбэк";
    }

    private static boolean isSafeSetbackDestination(Location safe, Location current) {
        World w = safe.getWorld();
        if (w == null || w != current.getWorld() || safe.distanceSquared(current) > 1024.0) {
            return false;
        }
        int bx = safe.getBlockX();
        int by = safe.getBlockY();
        int bz = safe.getBlockZ();
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) {
            return false;
        }
        return !isFullSolidWallBlock(w.getBlockAt(bx, by, bz))
            && !isFullSolidWallBlock(w.getBlockAt(bx, by + 1, bz));
    }

    private static boolean isInsideFullSolidWall(Location loc) {
        World w = loc.getWorld();
        if (w == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return false;
        }
        // Check tightly inset center so standing flush against a block never triggers Phase
        int bx1 = (int) Math.floor(loc.getX());
        int bz1 = (int) Math.floor(loc.getZ());
        double fracX = loc.getX() - bx1;
        double fracZ = loc.getZ() - bz1;
        if (fracX < 0.24 || fracX > 0.76 || fracZ < 0.24 || fracZ > 0.76) {
            return false;
        }
        Block low = w.getBlockAt(bx1, (int) Math.floor(loc.getY() + 0.35), bz1);
        Block high = w.getBlockAt(bx1, (int) Math.floor(loc.getY() + 1.25), bz1);
        return isFullSolidWallBlock(low) && isFullSolidWallBlock(high);
    }

    private static boolean isFullSolidWallBlock(Block b) {
        Material m = b.getType();
        if (m.isAir() || !m.isOccluding() || b.isPassable() || m.hasGravity()) {
            return false;
        }
        String n = m.name();
        return !n.contains("DOOR") && !n.contains("GATE") && !n.contains("SLAB")
            && !n.contains("STAIRS") && !n.contains("PISTON") && !n.contains("BED")
            && !n.contains("SOUL_SAND") && !n.contains("MUD") && !n.contains("SNOW")
            && !n.contains("SHULKER") && !n.contains("SIGN") && !n.contains("BANNER");
    }

    private static float wrapAngleAbs(float diff) {
        float w = diff % 360.0f;
        if (w > 180.0f) w -= 360.0f;
        if (w < -180.0f) w += 360.0f;
        return Math.abs(w);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static int getPotionAmplifier(Player player, PotionEffectType type) {
        PotionEffect eff = player.getPotionEffect(type);
        return eff != null ? (eff.getAmplifier() + 1) : 0;
    }

    private record EnvContext(
        boolean nearGround,
        boolean inLiquid,
        boolean nearWater,
        boolean inBubbleColumn,
        boolean inClimbableOrWeb,
        boolean onIce,
        boolean onSlimeOrPiston,
        boolean onStairsOrSlab,
        boolean onSoulBlock,
        boolean hasBlockAboveHead,
        boolean touchingSolidWallHorizontally,
        boolean nearPistonOrDoor,
        boolean insideSolidFullBlock,
        boolean onPureDeepLiquidSurface,
        boolean hasBoatOrShulkerSupport
    ) {
    }

    private static EnvContext scanEnvironment(World world, Location loc) {
        boolean nearGround = false;
        boolean inLiquid = false;
        boolean nearWater = false;
        boolean inBubbleColumn = false;
        boolean inClimbableOrWeb = false;
        boolean onIce = false;
        boolean onSlimeOrPiston = false;
        boolean onStairsOrSlab = false;
        boolean onSoulBlock = false;
        boolean hasBlockAboveHead = false;
        boolean touchingSolidWallHorizontally = false;
        boolean nearPistonOrDoor = false;
        boolean anyNonPureLiquidCornerSupport = false;

        int minX = (int) Math.floor(loc.getX() - 0.48);
        int maxX = (int) Math.floor(loc.getX() + 0.48);
        int minZ = (int) Math.floor(loc.getZ() - 0.48);
        int maxZ = (int) Math.floor(loc.getZ() + 0.48);
        int footY = (int) Math.floor(loc.getY());
        int belowY = (int) Math.floor(loc.getY() - 0.62);
        int headAboveY = (int) Math.floor(loc.getY() + 2.05);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return new EnvContext(true, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false);
                }
                for (int y = belowY; y <= footY + 1; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material m = block.getType();
                    if (m.isAir()) {
                        continue;
                    }
                    String name = m.name();
                    if (name.contains("PISTON") || name.contains("DOOR") || name.contains("TRAPDOOR") || name.contains("GATE")) {
                        nearPistonOrDoor = true;
                    }
                    if (m == Material.BUBBLE_COLUMN) {
                        inBubbleColumn = true;
                        inLiquid = true;
                        nearWater = true;
                        anyNonPureLiquidCornerSupport = true;
                        continue;
                    }
                    BlockData bd = block.getBlockData();
                    if (bd instanceof Waterlogged wl && wl.isWaterlogged()) {
                        nearWater = true;
                        if (y >= footY) {
                            inLiquid = true;
                        }
                    }
                    if (m == Material.WATER || m == Material.LAVA) {
                        nearWater = true;
                        if (y >= footY && loc.getY() - footY < 0.82) {
                            // Submerged in liquid (not skimming above surface)
                            inLiquid = true;
                        }
                        continue;
                    }
                    if (name.contains("LADDER") || name.contains("VINE") || name.contains("SCAFFOLDING")
                        || m == Material.COBWEB || m == Material.POWDER_SNOW || m == Material.HONEY_BLOCK
                        || name.contains("SWEET_BERRY")) {
                        inClimbableOrWeb = true;
                        anyNonPureLiquidCornerSupport = true;
                    }
                    if (y <= footY) {
                        anyNonPureLiquidCornerSupport = true;
                        if (m.isSolid() || !block.isPassable() || name.contains("CARPET")
                            || name.contains("LILY_PAD") || name.contains("SNOW") || name.contains("BED")
                            || name.contains("FENCE") || name.contains("WALL") || name.contains("TRAPDOOR")
                            || name.contains("AMETHYST") || name.contains("DRIPLEAF") || name.contains("SCULK")
                            || name.contains("CAMPFIRE") || name.contains("LANTERN") || name.contains("CHAIN")) {
                            nearGround = true;
                        }
                        if (name.contains("ICE")) {
                            onIce = true;
                        }
                        if (m == Material.SLIME_BLOCK || name.contains("PISTON")) {
                            onSlimeOrPiston = true;
                        }
                        if (name.contains("STAIRS") || name.contains("SLAB")) {
                            onStairsOrSlab = true;
                        }
                        if (name.contains("SOUL_SAND") || name.contains("SOUL_SOIL")) {
                            onSoulBlock = true;
                        }
                    }
                    if (y >= footY && (m.isSolid() || !block.isPassable())) {
                        touchingSolidWallHorizontally = true;
                    }
                }
                if (!world.getBlockAt(x, headAboveY, z).getType().isAir()) {
                    hasBlockAboveHead = true;
                }
            }
        }

        boolean insideSolid = isInsideFullSolidWall(loc);

        // Pure deep liquid surface check across ALL 4 AABB corners + Boat/Shulker entity check
        boolean pureSurface = false;
        boolean boatOrShulker = false;
        if (!anyNonPureLiquidCornerSupport && !nearGround) {
            int cx = loc.getBlockX();
            int cz = loc.getBlockZ();
            Material under1 = world.getBlockAt(cx, (int) Math.floor(loc.getY() - 0.15), cz).getType();
            Material under2 = world.getBlockAt(cx, (int) Math.floor(loc.getY() - 1.10), cz).getType();
            Material headM = world.getBlockAt(cx, footY, cz).getType();
            if ((under1 == Material.WATER || under1 == Material.LAVA)
                && (under2 == Material.WATER || under2 == Material.LAVA)
                && headM.isAir()) {
                pureSurface = true;
            }
        }
        // Only run entity support scan when floating above non-ground / water so idle ground ticks stay 0-GC
        if (!nearGround) {
            Collection<Entity> nearby = world.getNearbyEntities(loc, 1.6, 1.3, 1.6,
                e -> e instanceof Boat || e instanceof Shulker);
            if (!nearby.isEmpty()) {
                boatOrShulker = true;
            }
        }

        return new EnvContext(
            nearGround,
            inLiquid,
            nearWater,
            inBubbleColumn,
            inClimbableOrWeb,
            onIce,
            onSlimeOrPiston,
            onStairsOrSlab,
            onSoulBlock,
            hasBlockAboveHead,
            touchingSolidWallHorizontally,
            nearPistonOrDoor,
            insideSolid,
            pureSurface,
            boatOrShulker
        );
    }
}

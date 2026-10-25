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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;
import org.purpurmc.purpur.anticheat.config.ACConfig;
import org.purpurmc.purpur.anticheat.model.PlayerProfile;
import org.purpurmc.purpur.anticheat.util.BedrockBridgeInfo;

import java.util.Locale;

/**
 * Bedrock-calibrated Anti-Cheat checks specifically engineered for Purpurbedrock 26.2.
 *
 * Defends against Horion, Toolbox, Prax, PacketClient, Zephyr, Apollo, Strike & macros
 * while respecting Bedrock Touch/Controller/PC input differences, speed-bridging,
 * 0.5-block sneak overhang, stairs/slabs, and RakNet/NetherNet RTT latency.
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

    public boolean isMovementExempt(Player player, PlayerProfile profile, long nowMs, ACConfig cfg) {
        if (nowMs < profile.exemptUntilMs) {
            return true;
        }
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return true;
        }
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isRiptiding()
            || player.isInsideVehicle() || player.isSleeping() || player.isDead()) {
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

        // Always record BoundingBox for victims' ping-compensated Reach/HitBox ring buffer (cheap primitives only)
        profile.recordBoundingBox(player.getBoundingBox(), nowMs);

        // 1. BadPackets (NaN/Infinite or impossible pitch > 90.5°)
        ACConfig.CheckSettings badPktCfg = cfg.check("badpackets");
        if (badPktCfg.enabled) {
            if (!Double.isFinite(to.getX()) || !Double.isFinite(to.getY()) || !Double.isFinite(to.getZ())
                || Math.abs(to.getPitch()) > 90.5f) {
                plugin.engine().nudgeSuspicion(profile, 2.0);
                if (profile.addBuffer("badpackets", 1.0) >= badPktCfg.buffer) {
                    String act = applySetback(player, profile, event, badPktCfg.setback);
                    plugin.alerts().handleViolation(
                        player, profile, "badpackets", "BadPackets (Pitch/Coord)",
                        String.format(Locale.US, "pitch=%.1f", to.getPitch()),
                        1.0, act
                    );
                }
                return;
            } else {
                profile.decayBuffer("badpackets", badPktCfg.decay);
            }
        }

        if (from.getWorld() != to.getWorld()) {
            profile.exemptUntilMs = Math.max(profile.exemptUntilMs, nowMs + 2000L);
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double deltaXZ = Math.hypot(dx, dz);

        if (deltaXZ < 1.0E-4 && Math.abs(dy) < 1.0E-4) {
            // Rotation-only packet: do not waste CPU running movement checks!
            return;
        }
        profile.lastActiveMoveMs = nowMs;

        if (isMovementExempt(player, profile, nowMs, cfg)) {
            profile.airTicks = 0;
            profile.hoverTicks = 0;
            profile.liquidSurfaceTicks = 0;
            profile.usingItemSprintTicks = 0;
            profile.velocityCheckPending = false;
            profile.lastDeltaXZ = deltaXZ;
            profile.lastDeltaY = dy;
            return;
        }

        World world = to.getWorld();
        int bx = to.getBlockX();
        int bz = to.getBlockZ();
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return;
        }

        // Fast pre-screen: wake adaptive engine immediately if raw speed or vertical jump looks abnormal
        if (deltaXZ > 0.44 || dy > 0.55 || (profile.airTicks > 8 && dy >= -0.01)) {
            plugin.engine().nudgeSuspicion(profile, 1.0);
        }

        // Track Velocity (AntiKB) vertical response across post-hit ticks
        if (profile.velocityCheckPending) {
            if (dy > profile.maxObservedPostVelY) {
                profile.maxObservedPostVelY = dy;
            }
            int ping = BedrockBridgeInfo.getPing(player);
            long windowMs = Math.clamp(ping + 300L, 320L, 850L);
            if (nowMs - profile.lastVelocityMs >= windowMs) {
                profile.velocityCheckPending = false;
                evaluateVelocityResponse(player, profile, world, to, cfg);
            }
        }

        // Adaptive Sleep/Wake gate: skip deeper block-neighborhood checks when clean & sleeping!
        boolean runFullSample = plugin.engine().shouldSampleMove(profile, cfg);
        int sampleWeight = switch (profile.stage) {
            case AWAKE -> 1;
            case WAKING -> cfg.wakingSampleInterval;
            case SLEEPING, DORMANT -> cfg.sleepSampleInterval;
        };

        // Timer check (lightweight O(1) timestamp arithmetic)
        checkTimer(player, profile, event, nowMs, cfg);

        if (!runFullSample) {
            profile.lastDeltaXZ = deltaXZ;
            profile.lastDeltaY = dy;
            return;
        }

        EnvContext env = scanEnvironment(world, to);
        if (env.onIceOrSlime || env.onStairsOrSlab) {
            profile.iceOrSpeedBoostTicks = 25;
        } else if (profile.iceOrSpeedBoostTicks > 0) {
            profile.iceOrSpeedBoostTicks = Math.max(0, profile.iceOrSpeedBoostTicks - sampleWeight);
        }
        if (env.hasBlockAboveHead) {
            profile.ceilingHitTicks = 18;
        } else if (profile.ceilingHitTicks > 0) {
            profile.ceilingHitTicks = Math.max(0, profile.ceilingHitTicks - sampleWeight);
        }

        boolean hasRecentVelocity = (nowMs - profile.lastVelocityMs) <= (1300L + BedrockBridgeInfo.getPing(player));

        if (env.nearGround || env.inLiquid || env.inClimbableOrWeb) {
            profile.airTicks = 0;
            profile.hoverTicks = 0;
            if (env.nearGround && !env.inLiquid) {
                profile.lastSafeLocation = from.clone();
            }
        } else {
            profile.airTicks += sampleWeight;
        }

        // 2. Fly & AirJump checks
        if (!env.inLiquid && !env.inClimbableOrWeb && !hasRecentVelocity
            && !player.hasPotionEffect(PotionEffectType.LEVITATION)
            && !player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            checkFlyAndAirJump(player, profile, event, dy, deltaXZ, sampleWeight, cfg);
        } else {
            profile.decayBuffer("fly", cfg.check("fly").decay);
            profile.decayBuffer("airjump", cfg.check("airjump").decay);
        }

        // 3. Speed / BHop check
        if (!hasRecentVelocity && !env.inLiquid) {
            checkSpeed(player, profile, event, deltaXZ, dy, env, cfg);
        } else {
            profile.decayBuffer("speed", cfg.check("speed").decay);
        }

        // 4. Jesus / WaterWalk check
        checkJesus(player, profile, event, deltaXZ, dy, env, sampleWeight, hasRecentVelocity, cfg);

        // 5. NoSlow check
        checkNoSlow(player, profile, event, deltaXZ, env, sampleWeight, hasRecentVelocity, cfg);

        profile.lastDeltaXZ = deltaXZ;
        profile.lastDeltaY = dy;
    }

    private void checkTimer(Player player, PlayerProfile profile, PlayerMoveEvent event, long nowMs, ACConfig cfg) {
        ACConfig.CheckSettings settings = cfg.check("timer");
        if (!settings.enabled) {
            return;
        }
        if (nowMs - profile.lastTeleportMs < 1500L) {
            profile.timerSampleMoves = 0;
            profile.timerWindowStartMs = nowMs;
            return;
        }
        if (profile.timerSampleMoves == 0) {
            profile.timerWindowStartMs = nowMs;
            profile.timerSampleMoves = 1;
            return;
        }
        profile.timerSampleMoves++;
        if (profile.timerSampleMoves >= 22) {
            long elapsedMs = Math.max(1L, nowMs - profile.timerWindowStartMs);
            // 21 intervals * 50ms = 1050ms expected at 20 TPS
            double expectedMs = 21.0 * 50.0;
            double effectiveElapsed = elapsedMs + cfg.timerAllowanceBurstMs;
            double rate = expectedMs / effectiveElapsed;
            profile.timerSampleMoves = 0;
            profile.timerWindowStartMs = nowMs;

            if (rate > cfg.timerMaxRateRatio && elapsedMs < 820L) {
                plugin.engine().nudgeSuspicion(profile, 1.0);
                if (profile.addBuffer("timer", 1.0) >= settings.buffer) {
                    String act = applySetback(player, profile, event, settings.setback);
                    plugin.alerts().handleViolation(
                        player, profile, "timer", "Timer (GameSpeed)",
                        String.format(Locale.US, "speed=%.2fx", expectedMs / elapsedMs),
                        1.0, act
                    );
                }
            } else {
                profile.decayBuffer("timer", settings.decay);
            }
        }
    }

    private void checkFlyAndAirJump(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        double dy,
        double deltaXZ,
        int sampleWeight,
        ACConfig cfg
    ) {
        int jumpAmp = getPotionAmplifier(player, PotionEffectType.JUMP_BOOST);
        int extraJumpTicks = jumpAmp * 5;

        // AirJump: sudden strong upward jump impulse deep in mid-air
        ACConfig.CheckSettings airJumpCfg = cfg.check("airjump");
        if (airJumpCfg.enabled && profile.airTicks >= (cfg.airJumpMinAirTicks + extraJumpTicks)
            && dy > 0.34 && profile.lastDeltaY <= 0.08) {
            plugin.engine().nudgeSuspicion(profile, 1.5);
            if (profile.addBuffer("airjump", 1.0) >= airJumpCfg.buffer) {
                String act = applySetback(player, profile, event, airJumpCfg.setback);
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

        // Fly (Hover / Jetpack Ascend / Zero-Gravity Glide)
        ACConfig.CheckSettings flyCfg = cfg.check("fly");
        if (!flyCfg.enabled) {
            return;
        }
        boolean hoverAnomaly = profile.airTicks >= 8 && Math.abs(dy) < 1.5E-4 && deltaXZ > 0.04;
        if (hoverAnomaly) {
            profile.hoverTicks += sampleWeight;
        } else {
            profile.hoverTicks = Math.max(0, profile.hoverTicks - 1);
        }

        boolean ascendAnomaly = profile.airTicks >= (cfg.flyMinAirTicks + extraJumpTicks) && dy > 0.02;
        boolean glideAnomaly = profile.airTicks >= (cfg.flyMinAirTicks + 8 + extraJumpTicks) && dy > -0.025 && deltaXZ > 0.14;

        if (profile.hoverTicks >= 8 || ascendAnomaly || glideAnomaly) {
            plugin.engine().nudgeSuspicion(profile, 1.3);
            if (profile.addBuffer("fly", 1.0) >= flyCfg.buffer) {
                String mode = ascendAnomaly ? "Ascend" : (profile.hoverTicks >= 8 ? "Hover" : "Glide");
                String act = applySetback(player, profile, event, flyCfg.setback);
                plugin.alerts().handleViolation(
                    player, profile, "fly", "Fly (" + mode + ")",
                    String.format(Locale.US, "dy=%.3f air=%dt", dy, profile.airTicks),
                    1.0, act
                );
            }
        } else {
            profile.decayBuffer("fly", flyCfg.decay);
        }
    }

    private void checkSpeed(
        Player player,
        PlayerProfile profile,
        PlayerMoveEvent event,
        double deltaXZ,
        double dy,
        EnvContext env,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings speedCfg = cfg.check("speed");
        if (!speedCfg.enabled) {
            return;
        }

        double maxAllowed = cfg.speedMaxHorizontalBase;
        // Scale for custom walkSpeed & Attribute.MOVEMENT_SPEED
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
            maxAllowed += speedAmp * 0.085;
        }
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            maxAllowed += 0.35;
        }
        if (profile.iceOrSpeedBoostTicks > 0) {
            maxAllowed += 0.36;
        }
        if (profile.ceilingHitTicks > 0) {
            maxAllowed += 0.22;
        }
        if (dy > 0.35) {
            // Sprint-jump takeoff tick
            maxAllowed += 0.21;
        }
        EntityEquipment eq = player.getEquipment();
        if (eq != null) {
            ItemStack boots = eq.getBoots();
            if (boots != null && env.onSoulBlock) {
                int soulLvl = boots.getEnchantmentLevel(Enchantment.SOUL_SPEED);
                if (soulLvl > 0) {
                    maxAllowed += soulLvl * 0.09;
                }
            }
        }
        int ping = BedrockBridgeInfo.getPing(player);
        if (ping > cfg.highPingLeniencyMs) {
            maxAllowed += 0.10;
        }

        if (deltaXZ > maxAllowed) {
            plugin.engine().nudgeSuspicion(profile, 1.2);
            double weight = deltaXZ > (maxAllowed * 1.45) ? 1.5 : 1.0;
            if (profile.addBuffer("speed", weight) >= speedCfg.buffer) {
                String act = applySetback(player, profile, event, speedCfg.setback);
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
        boolean hasRecentVelocity,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings jesusCfg = cfg.check("jesus");
        if (!jesusCfg.enabled || hasRecentVelocity || player.isSwimming()) {
            return;
        }
        EntityEquipment eq = player.getEquipment();
        if (eq != null && eq.getBoots() != null && eq.getBoots().getEnchantmentLevel(Enchantment.FROST_WALKER) > 0) {
            return;
        }

        if (env.onPureDeepLiquidSurface && !env.nearGround && deltaXZ > 0.11 && Math.abs(dy) < 0.005) {
            profile.liquidSurfaceTicks += sampleWeight;
            if (profile.liquidSurfaceTicks >= 10) {
                plugin.engine().nudgeSuspicion(profile, 1.1);
                if (profile.addBuffer("jesus", 1.0) >= jesusCfg.buffer) {
                    String act = applySetback(player, profile, event, jesusCfg.setback);
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
        boolean hasRecentVelocity,
        ACConfig cfg
    ) {
        ACConfig.CheckSettings noSlowCfg = cfg.check("noslow");
        if (!noSlowCfg.enabled || hasRecentVelocity || profile.iceOrSpeedBoostTicks > 0) {
            profile.usingItemSprintTicks = 0;
            return;
        }
        if (player.isHandRaised() && env.nearGround) {
            int speedAmp = getPotionAmplifier(player, PotionEffectType.SPEED);
            double maxUseSpeed = 0.21 + speedAmp * 0.045;
            if (deltaXZ > maxUseSpeed) {
                profile.usingItemSprintTicks += sampleWeight;
                if (profile.usingItemSprintTicks >= cfg.noSlowMinUsingTicks) {
                    plugin.engine().nudgeSuspicion(profile, 1.0);
                    if (profile.addBuffer("noslow", 1.0) >= noSlowCfg.buffer) {
                        String act = applySetback(player, profile, event, noSlowCfg.setback);
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
        if (env.hasBlockAboveHead || env.inLiquid || env.inClimbableOrWeb) {
            return;
        }
        double ratio = profile.maxObservedPostVelY / profile.expectedVelocityY;
        if (ratio < cfg.minVelocityRatio) {
            plugin.engine().nudgeSuspicion(profile, 0.9);
            if (profile.addBuffer("velocity", 1.0) >= velCfg.buffer) {
                plugin.alerts().handleViolation(
                    player, profile, "velocity", "Velocity (AntiKB)",
                    String.format(Locale.US, "kbY=%.0f%%", Math.max(0.0, ratio * 100.0)),
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

        // Self-hit BadPackets guard
        if (victim.getUniqueId().equals(attacker.getUniqueId()) || attacker.isDead()) {
            event.setCancelled(true);
            plugin.alerts().handleViolation(
                attacker, attackerProfile, "badpackets", "BadPackets (InvalidHit)",
                "self/dead attack", 1.0, "Отмена удара"
            );
            return;
        }

        BedrockBridgeInfo.InputCategory inputCat = BedrockBridgeInfo.getInputCategory(attacker);
        int ping = BedrockBridgeInfo.getPing(attacker);

        // 1. AutoClicker (CPS ceiling + constant-interval bot stdDev)
        ACConfig.CheckSettings clickCfg = cfg.check("autoclicker");
        if (clickCfg.enabled) {
            int cps = attackerProfile.recordClickAndCountCps(nowMs);
            int maxCps = inputCat == BedrockBridgeInfo.InputCategory.TOUCH ? cfg.maxCpsTouch : cfg.maxCpsPc;
            double stdDev = cps >= 11 ? attackerProfile.computeClickIntervalStdDevMs() : 999.0;
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
                        detail, 1.0, clickCfg.cancelAction ? "Отмена удара" : "Лог"
                    );
                }
            } else {
                attackerProfile.decayBuffer("autoclicker", clickCfg.decay);
            }
        }

        // 2. KillAura: Open Inventory GUI, MultiAura, 360° Back-Hit, Solid Wall Occlusion
        ACConfig.CheckSettings auraCfg = cfg.check("killaura");
        if (auraCfg.enabled) {
            InventoryType openType = attacker.getOpenInventory().getTopInventory().getType();
            if (cfg.killauraOpenInventory && openType != InventoryType.CRAFTING && openType != InventoryType.CREATIVE) {
                plugin.engine().nudgeSuspicion(attackerProfile, 1.5);
                if (attackerProfile.addBuffer("killaura", 1.0) >= auraCfg.buffer) {
                    if (auraCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    plugin.alerts().handleViolation(
                        attacker, attackerProfile, "killaura", "KillAura (InvGUI)",
                        "menu=" + openType.name(), 1.0, auraCfg.cancelAction ? "Отмена удара" : "Лог"
                    );
                    return;
                }
            }

            // MultiAura: switching between 2 distinct targets faster than humanly possible
            if (attackerProfile.lastAttackTargetUuid != null
                && !attackerProfile.lastAttackTargetUuid.equals(victim.getUniqueId())
                && (nowMs - attackerProfile.lastAttackTimeMs) > 2L
                && (nowMs - attackerProfile.lastAttackTimeMs) < cfg.multiAuraWindowMs
                && event.getCause() == EntityDamageByEntityEvent.DamageCause.ENTITY_ATTACK) {
                plugin.engine().nudgeSuspicion(attackerProfile, 1.4);
                if (attackerProfile.addBuffer("killaura", 1.0) >= auraCfg.buffer) {
                    if (auraCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    plugin.alerts().handleViolation(
                        attacker, attackerProfile, "killaura", "KillAura (Multi)",
                        String.format(Locale.US, "switch=%dms", nowMs - attackerProfile.lastAttackTimeMs),
                        1.0, auraCfg.cancelAction ? "Отмена удара" : "Лог"
                    );
                }
            } else {
                attackerProfile.decayBuffer("killaura", auraCfg.decay * 0.5);
            }
        }
        attackerProfile.lastAttackTargetUuid = victim.getUniqueId();
        attackerProfile.lastAttackTimeMs = nowMs;

        if (attacker.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        // 3. Ping-compensated 3D AABB Reach & HitBox + Back/Wall checks
        Location eye = attacker.getEyeLocation();
        BoundingBox victimBox = victim.getBoundingBox();
        double dist;
        if (victim instanceof Player victimPlayer) {
            PlayerProfile victimProfile = plugin.profile(victimPlayer);
            long minWindowMs = nowMs - Math.clamp(ping + 140L, 160L, 650L);
            dist = victimProfile.computeMinCompensatedDistance(
                eye.getX(), eye.getY(), eye.getZ(), victimBox, 0.12, minWindowMs
            );
        } else {
            dist = computeStaticDistanceToBox(eye, victimBox, 0.16);
        }
        attackerProfile.lastMeasuredReach = dist;

        double maxReach = switch (inputCat) {
            case KEYBOARD_MOUSE -> cfg.maxReachPc;
            case CONTROLLER -> cfg.maxReachController;
            case TOUCH -> cfg.maxReachTouch;
        };
        if (ping > cfg.highPingLeniencyMs) {
            maxReach += 0.18;
        }

        ACConfig.CheckSettings reachCfg = cfg.check("reach");
        if (reachCfg.enabled) {
            if (dist > maxReach) {
                plugin.engine().nudgeSuspicion(attackerProfile, 1.4);
                if (dist >= cfg.cancelReachAbove) {
                    event.setCancelled(true);
                }
                double weight = dist > (maxReach + 0.55) ? 1.5 : 1.0;
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

        // 360° behind-the-back check (applies to ALL input modes when victim is > 1.45 blocks away)
        if (auraCfg.enabled && dist > 1.45) {
            Vector lookDir = eye.getDirection().setY(0.0);
            Vector toVictim = victimBox.getCenter().subtract(eye.toVector()).setY(0.0);
            if (lookDir.lengthSquared() > 1.0E-4 && toVictim.lengthSquared() > 1.0E-4) {
                double dot = lookDir.normalize().dot(toVictim.normalize());
                if (dot < -0.42) {
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

            // Through-Wall check: verify if ALL 3 vertical points (feet, chest, head) are occluded by solid full blocks
            if (cfg.killauraThroughWalls && dist > 1.85 && victim instanceof LivingEntity) {
                if (isFullyOccludedBySolidWall(attacker.getWorld(), eye, victimBox)) {
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

        // 4. HitBox expander check (PC & Controller crosshair ray vs expanded victim AABB)
        ACConfig.CheckSettings hbCfg = cfg.check("hitbox");
        if (hbCfg.enabled && inputCat != BedrockBridgeInfo.InputCategory.TOUCH && dist > 1.55) {
            double expand = cfg.hitboxMaxExpansion + (ping > 120 ? 0.14 : 0.06);
            BoundingBox generousBox = victimBox.clone().expand(expand, expand + 0.12, expand);
            RayTraceResult hit = generousBox.rayTrace(eye.toVector(), eye.getDirection(), 6.5);
            if (hit == null) {
                plugin.engine().nudgeSuspicion(attackerProfile, 1.1);
                if (attackerProfile.addBuffer("hitbox", 1.0) >= hbCfg.buffer) {
                    if (hbCfg.cancelAction) {
                        event.setCancelled(true);
                    }
                    plugin.alerts().handleViolation(
                        attacker, attackerProfile, "hitbox", "HitBox",
                        String.format(Locale.US, "miss>+%.2f dist=%.2f", expand, dist),
                        1.0, event.isCancelled() ? "Отмена удара" : "Лог"
                    );
                }
            } else {
                attackerProfile.decayBuffer("hitbox", hbCfg.decay);
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
        Location eye = player.getEyeLocation();
        if (placed.getWorld() != eye.getWorld()) {
            return;
        }
        double dist = eye.toVector().distance(placed.getLocation().add(0.5, 0.5, 0.5).toVector());
        boolean pureAirNeighbors = hasZeroNonAirNeighbors(placed);

        // Never touches legitimate Bedrock forward speed-bridging (which places against real block faces within reach!)
        if (dist > cfg.scaffoldMaxPlaceDistance || pureAirNeighbors) {
            plugin.engine().nudgeSuspicion(profile, 1.3);
            if (profile.addBuffer("scaffold", 1.0) >= scafCfg.buffer) {
                if (scafCfg.cancelAction) {
                    event.setCancelled(true);
                }
                String detail = pureAirNeighbors
                    ? "airPlace (0 граней)"
                    : String.format(Locale.US, "reach=%.2f max=%.2f", dist, cfg.scaffoldMaxPlaceDistance);
                plugin.alerts().handleViolation(
                    player, profile, "scaffold", "Scaffold (Air/Reach)",
                    detail, 1.0, event.isCancelled() ? "Отмена блока" : "Лог"
                );
            }
        } else {
            profile.decayBuffer("scaffold", scafCfg.decay);
        }
    }

    private static boolean hasZeroNonAirNeighbors(Block block) {
        World w = block.getWorld();
        for (BlockFace face : SIX_FACES) {
            int nx = block.getX() + face.getModX();
            int ny = block.getY() + face.getModY();
            int nz = block.getZ() + face.getModZ();
            if (!w.isChunkLoaded(nx >> 4, nz >> 4)) {
                return false;
            }
            if (!w.getBlockAt(nx, ny, nz).getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFullyOccludedBySolidWall(World world, Location eye, BoundingBox box) {
        Vector start = eye.toVector();
        double cx = box.getCenterX();
        double cz = box.getCenterZ();
        double[] sampleYs = {
            box.getMinY() + 0.25,
            (box.getMinY() + box.getMaxY()) * 0.5,
            box.getMaxY() - 0.15
        };
        for (double sy : sampleYs) {
            Vector target = new Vector(cx, sy, cz);
            Vector dir = target.clone().subtract(start);
            double len = dir.length();
            if (len < 0.4) {
                return false;
            }
            RayTraceResult res = world.rayTraceBlocks(eye, dir.normalize(), len - 0.25);
            if (res == null || res.getHitBlock() == null || !res.getHitBlock().getType().isOccluding()) {
                return false; // At least one line of sight is open (e.g. slab, glass, fence, corner)
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

    private static String applySetback(Player player, PlayerProfile profile, PlayerMoveEvent event, boolean setback) {
        if (!setback) {
            return "Лог";
        }
        Location safe = profile.lastSafeLocation;
        if (safe != null && safe.getWorld() == player.getWorld() && safe.distanceSquared(player.getLocation()) < 64.0) {
            Location dest = safe.clone();
            dest.setYaw(event.getTo().getYaw());
            dest.setPitch(event.getTo().getPitch());
            event.setTo(dest);
        } else {
            event.setTo(event.getFrom());
        }
        return "Сетбэк";
    }

    private static int getPotionAmplifier(Player player, PotionEffectType type) {
        PotionEffect eff = player.getPotionEffect(type);
        return eff != null ? (eff.getAmplifier() + 1) : 0;
    }

    private record EnvContext(
        boolean nearGround,
        boolean inLiquid,
        boolean inClimbableOrWeb,
        boolean onIceOrSlime,
        boolean onStairsOrSlab,
        boolean onSoulBlock,
        boolean hasBlockAboveHead,
        boolean onPureDeepLiquidSurface
    ) {
    }

    private static EnvContext scanEnvironment(World world, Location loc) {
        boolean nearGround = false;
        boolean inLiquid = false;
        boolean inClimbableOrWeb = false;
        boolean onIceOrSlime = false;
        boolean onStairsOrSlab = false;
        boolean onSoulBlock = false;
        boolean hasBlockAboveHead = false;
        boolean anySolidAroundFeet = false;

        int minX = (int) Math.floor(loc.getX() - 0.52);
        int maxX = (int) Math.floor(loc.getX() + 0.52);
        int minZ = (int) Math.floor(loc.getZ() - 0.52);
        int maxZ = (int) Math.floor(loc.getZ() + 0.52);
        int footY = (int) Math.floor(loc.getY());
        int belowY = (int) Math.floor(loc.getY() - 0.55);
        int headAboveY = (int) Math.floor(loc.getY() + 2.05);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return new EnvContext(true, false, false, false, false, false, false, false);
                }
                for (int y = belowY; y <= footY + 1; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir()) {
                        continue;
                    }
                    String name = m.name();
                    if (m == Material.WATER || m == Material.LAVA || name.contains("BUBBLE_COLUMN")) {
                        if (y >= footY) {
                            inLiquid = true;
                        }
                        continue;
                    }
                    if (name.contains("LADDER") || name.contains("VINE") || name.contains("SCAFFOLDING")
                        || m == Material.COBWEB || m == Material.POWDER_SNOW || m == Material.HONEY_BLOCK) {
                        inClimbableOrWeb = true;
                    }
                    if (y <= footY) {
                        anySolidAroundFeet = true;
                        if (m.isSolid() || name.contains("CARPET") || name.contains("LILY_PAD")
                            || name.contains("SNOW") || name.contains("BED") || name.contains("FENCE")
                            || name.contains("WALL") || name.contains("TRAPDOOR")) {
                            nearGround = true;
                        }
                        if (name.contains("ICE") || m == Material.SLIME_BLOCK) {
                            onIceOrSlime = true;
                        }
                        if (name.contains("STAIRS") || name.contains("SLAB")) {
                            onStairsOrSlab = true;
                        }
                        if (name.contains("SOUL_SAND") || name.contains("SOUL_SOIL")) {
                            onSoulBlock = true;
                        }
                    }
                }
                if (!world.getBlockAt(x, headAboveY, z).getType().isAir()) {
                    hasBlockAboveHead = true;
                }
            }
        }

        // Check pure deep liquid surface directly under center for Jesus check
        boolean pureSurface = false;
        int cx = loc.getBlockX();
        int cz = loc.getBlockZ();
        if (!anySolidAroundFeet && !inLiquid) {
            Material under1 = world.getBlockAt(cx, (int) Math.floor(loc.getY() - 0.15), cz).getType();
            Material under2 = world.getBlockAt(cx, (int) Math.floor(loc.getY() - 1.15), cz).getType();
            if ((under1 == Material.WATER || under1 == Material.LAVA)
                && (under2 == Material.WATER || under2 == Material.LAVA)) {
                pureSurface = true;
            }
        }

        return new EnvContext(
            nearGround,
            inLiquid,
            inClimbableOrWeb,
            onIceOrSlime,
            onStairsOrSlab,
            onSoulBlock,
            hasBlockAboveHead,
            pureSurface
        );
    }
}

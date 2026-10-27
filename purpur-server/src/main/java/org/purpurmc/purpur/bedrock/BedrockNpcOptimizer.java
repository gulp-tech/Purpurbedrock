package org.purpurmc.purpur.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.purpurmc.purpur.PurpurConfig;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Device-aware Bedrock NPC visibility culler.
 *
 * <p>Citizens and metadata-tagged NPCs remain fully alive server-side. Only entities outside each
 * Bedrock viewer's useful device/RTT budget are hidden, cutting entity spawn, metadata, equipment,
 * skin and movement packets without changing NPC interactions, traits, navigation or Java plugins.</p>
 */
public final class BedrockNpcOptimizer implements Listener, Runnable {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final int FALLBACK_DISCOVERY_TICKS = 100;
    private static final double INTERACTION_DISTANCE_SQ = 64.0D;

    private final Plugin owner;
    private final Map<UUID, Entity> trackedNpcs = new HashMap<>();
    private final Map<UUID, Set<UUID>> hiddenByViewer = new HashMap<>();
    private BukkitTask task;
    private int discoveryCountdown;

    private ClassLoader citizensLoader;
    private Method citizensGetRegistry;
    private Method npcGetEntity;

    private record Candidate(Entity entity, double distanceSq) {
    }

    private record NpcBudget(double distanceSq, int maxVisible) {
    }

    private BedrockNpcOptimizer(Plugin owner) {
        this.owner = owner;
    }

    /** Starts once after all STARTUP/POSTWORLD plugins, including Citizens, are enabled. */
    public static void initAfterPlugins() {
        if (!PurpurConfig.bedrockNpcOptimizationEnabled || !STARTED.compareAndSet(false, true)) {
            return;
        }
        Plugin owner = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (owner == null || !owner.isEnabled()) {
            STARTED.set(false);
            Bukkit.getLogger().warning("[Purpurbedrock] NPC optimizer ожидает активный встроенный Bedrock протокол.");
            return;
        }
        BedrockNpcOptimizer optimizer = new BedrockNpcOptimizer(owner);
        Bukkit.getPluginManager().registerEvents(optimizer, owner);
        optimizer.task = Bukkit.getScheduler().runTaskTimer(
            owner,
            optimizer,
            20L,
            Math.max(2, PurpurConfig.bedrockNpcUpdateIntervalTicks)
        );
        Bukkit.getLogger().info(
            "[Purpurbedrock] Device-Adaptive NPC optimizer активен: SAFE/BALANCED/HIGH, без изменения AI и трейтов."
        );
    }

    @Override
    public void run() {
        if (!PurpurConfig.bedrockNpcOptimizationEnabled) {
            restoreAll();
            return;
        }
        if (discoveryCountdown-- <= 0) {
            discoverNpcs();
            discoveryCountdown = FALLBACK_DISCOVERY_TICKS / Math.max(2, PurpurConfig.bedrockNpcUpdateIntervalTicks);
        } else {
            trackedNpcs.values().removeIf(entity -> entity == null || !entity.isValid());
        }

        if (trackedNpcs.isEmpty()) {
            restoreAll();
            return;
        }

        Set<UUID> activeViewers = new HashSet<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!Boolean.parseBoolean(BedrockPlaceholderHook.resolveOnline(viewer, "is_bedrock"))) {
                continue;
            }
            activeViewers.add(viewer.getUniqueId());
            updateViewer(viewer);
        }
        for (UUID viewerId : new ArrayList<>(hiddenByViewer.keySet())) {
            if (!activeViewers.contains(viewerId)) {
                restoreViewer(viewerId);
            }
        }
    }

    private void updateViewer(Player viewer) {
        NpcBudget budget = budgetFor(viewer);
        List<Candidate> nearby = new ArrayList<>();
        World world = viewer.getWorld();
        Location viewerLocation = viewer.getLocation();
        Location npcLocation = new Location(world, 0.0D, 0.0D, 0.0D);
        for (Entity npc : trackedNpcs.values()) {
            if (npc == null || !npc.isValid() || npc.getWorld() != world || npc.getUniqueId().equals(viewer.getUniqueId())) {
                continue;
            }
            double distanceSq = npc.getLocation(npcLocation).distanceSquared(viewerLocation);
            if (distanceSq <= budget.distanceSq()) {
                nearby.add(new Candidate(npc, distanceSq));
            }
        }
        nearby.sort(Comparator.comparingDouble(Candidate::distanceSq));

        Set<UUID> shouldShow = new HashSet<>();
        // Never cull an NPC close enough to interact with, even when a dense hub exceeds its profile budget.
        for (Candidate candidate : nearby) {
            if (candidate.distanceSq() <= INTERACTION_DISTANCE_SQ || shouldShow.size() < budget.maxVisible()) {
                shouldShow.add(candidate.entity().getUniqueId());
            }
        }

        Set<UUID> hidden = hiddenByViewer.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>());
        for (Entity npc : trackedNpcs.values()) {
            if (npc == null || !npc.isValid() || npc.getUniqueId().equals(viewer.getUniqueId())) {
                continue;
            }
            UUID npcId = npc.getUniqueId();
            if (shouldShow.contains(npcId)) {
                if (hidden.remove(npcId)) {
                    viewer.showEntity(owner, npc);
                }
            } else if (hidden.add(npcId)) {
                viewer.hideEntity(owner, npc);
            }
        }
    }

    private NpcBudget budgetFor(Player viewer) {
        String profile = BedrockPlaceholderHook.analyzePerformanceProfile(viewer);
        double distance;
        int maxVisible;
        switch (profile) {
            case "SAFE" -> {
                distance = PurpurConfig.bedrockNpcSafeDistance;
                maxVisible = PurpurConfig.bedrockNpcSafeMaxVisible;
            }
            case "BALANCED" -> {
                distance = PurpurConfig.bedrockNpcBalancedDistance;
                maxVisible = PurpurConfig.bedrockNpcBalancedMaxVisible;
            }
            default -> {
                distance = PurpurConfig.bedrockNpcHighDistance;
                maxVisible = PurpurConfig.bedrockNpcHighMaxVisible;
            }
        }
        if (Bukkit.getTPS()[0] < PurpurConfig.bedrockNpcPressureTps) {
            distance *= 0.75;
            maxVisible = Math.max(4, maxVisible / 2);
        }
        return new NpcBudget(distance * distance, Math.max(1, maxVisible));
    }

    private void discoverNpcs() {
        Map<UUID, Entity> discovered = new HashMap<>();
        discoverCitizens(discovered);
        // Generic fallback supports FancyNPCs/ZNPCs/custom NPCs that expose conventional metadata/tags.
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isNpc(entity)) {
                    discovered.put(entity.getUniqueId(), entity);
                }
            }
        }
        // Undo only our own visibility override when an entity stops being classified as an NPC.
        for (Map.Entry<UUID, Entity> entry : trackedNpcs.entrySet()) {
            if (discovered.containsKey(entry.getKey())) {
                continue;
            }
            for (Map.Entry<UUID, Set<UUID>> hiddenEntry : hiddenByViewer.entrySet()) {
                if (hiddenEntry.getValue().remove(entry.getKey())) {
                    Player viewer = Bukkit.getPlayer(hiddenEntry.getKey());
                    if (viewer != null && entry.getValue().isValid()) {
                        viewer.showEntity(owner, entry.getValue());
                    }
                }
            }
        }
        trackedNpcs.clear();
        trackedNpcs.putAll(discovered);
    }

    private void discoverCitizens(Map<UUID, Entity> discovered) {
        Plugin citizens = Bukkit.getPluginManager().getPlugin("Citizens");
        if (citizens == null || !citizens.isEnabled()) {
            return;
        }
        try {
            ClassLoader loader = citizens.getClass().getClassLoader();
            if (loader != citizensLoader || citizensGetRegistry == null || npcGetEntity == null) {
                Class<?> apiClass = Class.forName("net.citizensnpcs.api.CitizensAPI", true, loader);
                Class<?> npcClass = Class.forName("net.citizensnpcs.api.npc.NPC", true, loader);
                citizensGetRegistry = apiClass.getMethod("getNPCRegistry");
                npcGetEntity = npcClass.getMethod("getEntity");
                citizensLoader = loader;
            }
            Object registry = citizensGetRegistry.invoke(null);
            if (registry instanceof Iterable<?> iterable) {
                for (Object npc : iterable) {
                    Object rawEntity = npcGetEntity.invoke(npc);
                    if (rawEntity instanceof Entity entity && entity.isValid()) {
                        discovered.put(entity.getUniqueId(), entity);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Metadata discovery below remains available if a Citizens API version changes.
        }
    }

    private static boolean isNpc(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return false;
        }
        if (entity.hasMetadata("NPC") || entity.hasMetadata("npc") || entity.hasMetadata("CitizensNPC")
            || entity.hasMetadata("FancyNPC") || entity.hasMetadata("ZNPC")) {
            return true;
        }
        for (String tag : entity.getScoreboardTags()) {
            String normalized = tag.toLowerCase(Locale.ROOT);
            if (normalized.equals("npc") || normalized.equals("citizens_npc") || normalized.equals("fancynpc")
                || normalized.equals("fancynpcs") || normalized.equals("znpc") || normalized.equals("znpcs")) {
                return true;
            }
        }
        return false;
    }

    private void restoreAll() {
        for (UUID viewerId : new ArrayList<>(hiddenByViewer.keySet())) {
            restoreViewer(viewerId);
        }
    }

    private void restoreViewer(UUID viewerId) {
        Set<UUID> hidden = hiddenByViewer.remove(viewerId);
        Player viewer = Bukkit.getPlayer(viewerId);
        if (hidden == null || viewer == null) {
            return;
        }
        for (UUID npcId : hidden) {
            Entity npc = trackedNpcs.get(npcId);
            if (npc != null && npc.isValid()) {
                viewer.showEntity(owner, npc);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        hiddenByViewer.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == owner) {
            restoreAll();
            if (task != null) {
                task.cancel();
                task = null;
            }
            STARTED.set(false);
        }
    }

    /** Pure self-test for profile budgets used by the live smoke test. */
    public static String runSelfTest() {
        if (!(PurpurConfig.bedrockNpcSafeDistance <= PurpurConfig.bedrockNpcBalancedDistance
            && PurpurConfig.bedrockNpcBalancedDistance <= PurpurConfig.bedrockNpcHighDistance
            && PurpurConfig.bedrockNpcSafeMaxVisible <= PurpurConfig.bedrockNpcBalancedMaxVisible
            && PurpurConfig.bedrockNpcBalancedMaxVisible <= PurpurConfig.bedrockNpcHighMaxVisible
            && INTERACTION_DISTANCE_SQ <= PurpurConfig.bedrockNpcSafeDistance * PurpurConfig.bedrockNpcSafeDistance)) {
            throw new IllegalStateException("NPC adaptive budget ordering mismatch");
        }
        return "NPC_OPTIMIZER_SELFTEST_OK profiles=SAFE|BALANCED|HIGH metadata=NPC citizens=REFLECTION interactionSafe=true";
    }
}

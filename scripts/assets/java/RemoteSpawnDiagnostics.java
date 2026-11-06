package org.geysermc.geyser.session;

import org.geysermc.geyser.GeyserImpl;

import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/** Opt-in, bounded, 30-second aggregate counters. Never inspects packet contents. */
public final class RemoteSpawnDiagnostics {
    private static final int SECONDS = 30;
    private static volatile Probe current;

    private RemoteSpawnDiagnostics() { }

    public static synchronized String begin(String playerUuid) {
        if (current != null) return "Диагностика уже идёт; дождитесь результата.";
        UUID target;
        try {
            target = UUID.fromString(playerUuid);
        } catch (IllegalArgumentException e) {
            return "Некорректный идентификатор игрока.";
        }
        GeyserSession selected = null;
        for (GeyserSession session : GeyserImpl.getInstance().getSessionManager().getAllSessions()) {
            if (session.getPlayerEntity() != null && target.equals(session.getPlayerEntity().uuid()) && !session.isClosed()) {
                selected = session;
                break;
            }
        }
        if (selected == null) return "Активная Bedrock-сессия игрока не найдена.";
        Probe probe = new Probe(selected);
        current = probe;
        var executor = GeyserImpl.getInstance().getScheduledThread();
        try {
            probe.heartbeat = executor.scheduleAtFixedRate(() -> heartbeat(probe), 0, 1, TimeUnit.SECONDS);
            executor.schedule(() -> finish(probe), SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException ex) {
            current = null;
            if (probe.heartbeat != null) probe.heartbeat.cancel(false);
            throw ex;
        }
        return "Диагностика запущена на 30 секунд. Воспроизведите фриз; итог появится в консоли.";
    }

    public static void recordJava(GeyserSession session, Object packet) {
        Probe probe = current;
        if (probe != null && probe.session == session) probe.record(probe.javaPackets, probe.javaPerSecond, packet);
    }

    public static void recordBedrock(GeyserSession session, Object packet) {
        Probe probe = current;
        if (probe != null && probe.session == session) probe.record(probe.bedrockPackets, probe.bedrockPerSecond, packet);
    }

    private static void heartbeat(Probe probe) {
        if (current != probe || probe.session.isClosed()) return;
        long scheduled = System.nanoTime();
        probe.session.ensureInEventLoop(() -> {
            if (current != probe) return;
            long delayMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scheduled);
            probe.maxEventLoopDelayMs.accumulateAndGet(delayMs, Math::max);
            probe.maxPingMs.accumulateAndGet(probe.session.ping(), Math::max);
            probe.heartbeatCount.increment();
        });
    }

    private static synchronized void finish(Probe probe) {
        if (current != probe) return;
        current = null;
        if (probe.heartbeat != null) probe.heartbeat.cancel(false);
        String result = "[Purpurbedrock] DIAG_30S protocol=" + probe.session.protocolVersion()
            + " closed=" + probe.session.isClosed()
            + " eventLoopMaxMs=" + probe.maxEventLoopDelayMs.get()
            + " heartbeatSamples=" + probe.heartbeatCount.sum()
            + " maxPingMs=" + probe.maxPingMs.get()
            + " javaPerSecond=" + values(probe.javaPerSecond)
            + " bedrockPerSecond=" + values(probe.bedrockPerSecond)
            + " javaTop=" + top(probe.javaPackets)
            + " bedrockTop=" + top(probe.bedrockPackets);
        GeyserImpl.getInstance().getLogger().info(result);
    }

    private static String values(AtomicIntegerArray counts) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < counts.length(); i++) {
            if (i != 0) result.append(',');
            result.append(counts.get(i));
        }
        return result.append(']').toString();
    }

    private static String top(ConcurrentHashMap<String, LongAdder> counts) {
        return counts.entrySet().stream()
            .sorted(Comparator.<java.util.Map.Entry<String, LongAdder>>comparingLong(e -> e.getValue().sum()).reversed())
            .limit(15)
            .map(e -> e.getKey() + '=' + e.getValue().sum())
            .collect(Collectors.joining(",", "[", "]"));
    }

    private static final class Probe {
        final GeyserSession session;
        final long started = System.nanoTime();
        final ConcurrentHashMap<String, LongAdder> javaPackets = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> bedrockPackets = new ConcurrentHashMap<>();
        final AtomicIntegerArray javaPerSecond = new AtomicIntegerArray(SECONDS);
        final AtomicIntegerArray bedrockPerSecond = new AtomicIntegerArray(SECONDS);
        final AtomicLong maxEventLoopDelayMs = new AtomicLong();
        final AtomicLong maxPingMs = new AtomicLong();
        final LongAdder heartbeatCount = new LongAdder();
        volatile ScheduledFuture<?> heartbeat;

        Probe(GeyserSession session) { this.session = session; }

        void record(ConcurrentHashMap<String, LongAdder> counts, AtomicIntegerArray perSecond, Object packet) {
            long second = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
            if (second < 0 || second >= SECONDS) return;
            perSecond.incrementAndGet((int) second);
            counts.computeIfAbsent(packet.getClass().getSimpleName(), ignored -> new LongAdder()).increment();
        }
    }
}

package org.geysermc.geyser.entity.type.player;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Event-loop confined: coalesces remote visuals, never inventory state or movement. */
public final class RemoteEquipmentBatch {
    public static final int ARMOR = 1, MAIN_HAND = 2, OFF_HAND = 4;
    private final Consumer<Runnable> schedule;
    private final IntConsumer send;
    private int pending;
    private boolean queued;
    private long generation;

    public RemoteEquipmentBatch(Consumer<Runnable> schedule, IntConsumer send) {
        this.schedule = schedule;
        this.send = send;
    }

    public void update(int flags) {
        if (flags == 0) return;
        pending |= flags;
        if (queued) return;
        queued = true;
        long expected = generation;
        schedule.accept(() -> {
            if (generation != expected) return;
            int ready = pending;
            pending = 0;
            queued = false;
            if (ready != 0) send.accept(ready);
        });
    }

    public void invalidate() {
        generation++;
        pending = 0;
        queued = false;
    }
}

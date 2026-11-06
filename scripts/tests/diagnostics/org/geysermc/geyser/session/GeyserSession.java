package org.geysermc.geyser.session;
import java.util.UUID;
public class GeyserSession {
    public final UUID id = UUID.randomUUID();
    public Player getPlayerEntity() { return new Player(); }
    public class Player { public UUID uuid() { return id; } }
    public boolean isClosed() { return false; }
    public int ping() { return 42; }
    public int protocolVersion() { return 2225; }
    public void ensureInEventLoop(Runnable task) { task.run(); }
}

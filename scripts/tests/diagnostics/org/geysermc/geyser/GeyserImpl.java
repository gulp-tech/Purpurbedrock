package org.geysermc.geyser;
import java.util.List;
import java.util.concurrent.*;
import org.geysermc.geyser.session.GeyserSession;
public class GeyserImpl {
    public static final GeyserImpl INSTANCE = new GeyserImpl();
    public final GeyserSession session = new GeyserSession();
    public final MockScheduler scheduler = new MockScheduler();
    public final Log logger = new Log();
    public static GeyserImpl getInstance() { return INSTANCE; }
    public Manager getSessionManager() { return new Manager(); }
    public class Manager { public List<GeyserSession> getAllSessions() { return List.of(session); } }
    public MockScheduler getScheduledThread() { return scheduler; }
    public Log getLogger() { return logger; }
    public static class Log { public String message; public void info(String value) { message = value; } }
    public static class MockScheduler extends ScheduledThreadPoolExecutor {
        public Runnable heartbeat, finish;
        public MockScheduler() { super(1); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable r, long initial, long period, TimeUnit unit) {
            heartbeat = r; return new Ticket();
        }
        @Override public ScheduledFuture<?> schedule(Runnable r, long delay, TimeUnit unit) {
            finish = r; return new Ticket();
        }
    }
    public static class Ticket implements ScheduledFuture<Object> {
        public long getDelay(TimeUnit unit) { return 0; }
        public int compareTo(Delayed other) { return 0; }
        public boolean cancel(boolean interrupt) { return true; }
        public boolean isCancelled() { return false; }
        public boolean isDone() { return false; }
        public Object get() { return null; }
        public Object get(long timeout, TimeUnit unit) { return null; }
    }
}

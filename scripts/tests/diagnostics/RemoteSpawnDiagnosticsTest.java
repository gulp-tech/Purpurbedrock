import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.RemoteSpawnDiagnostics;
public class RemoteSpawnDiagnosticsTest {
    static class AddPlayerPacket { }
    static class PrivatePayload { public String toString() { throw new AssertionError("payload accessed"); } }
    public static void main(String[] args) {
        var core = GeyserImpl.getInstance();
        if (!RemoteSpawnDiagnostics.begin(core.session.id.toString()).contains("30 секунд")) throw new AssertionError();
        if (!RemoteSpawnDiagnostics.begin(core.session.id.toString()).contains("уже идёт")) throw new AssertionError();
        RemoteSpawnDiagnostics.recordBedrock(core.session, new AddPlayerPacket());
        RemoteSpawnDiagnostics.recordBedrock(core.session, new AddPlayerPacket());
        RemoteSpawnDiagnostics.recordJava(core.session, new PrivatePayload());
        RemoteSpawnDiagnostics.recordBedrock(new GeyserSession(), new PrivatePayload());
        core.scheduler.heartbeat.run();
        core.scheduler.finish.run();
        String result = core.logger.message;
        if (!result.contains("AddPlayerPacket=2") || !result.contains("PrivatePayload=1")
            || !result.contains("maxPingMs=42") || !result.contains("heartbeatSamples=1")
            || result.contains(core.session.id.toString())) throw new AssertionError(result);
        RemoteSpawnDiagnostics.recordJava(core.session, new AddPlayerPacket());
        if (!RemoteSpawnDiagnostics.begin(core.session.id.toString()).contains("30 секунд")) throw new AssertionError();
        core.scheduler.finish.run();
        if (core.logger.message.contains("AddPlayerPacket=1")) throw new AssertionError("inactive recorded");
        if (!RemoteSpawnDiagnostics.begin(java.util.UUID.randomUUID().toString()).contains("не найдена")) throw new AssertionError();
        System.out.println("RemoteSpawnDiagnostics tests passed");
    }
}

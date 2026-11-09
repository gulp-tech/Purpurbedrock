package org.purpurmc.purpur.spectate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Simulated adapter tests only; no network/client protection is implemented here. */
public final class AdmissionTest {
    private static final class FakeBridge implements SpectateAdmission.PrivacyBridge {
        final Path returns, audit;
        final ReturnStore.Snapshot snapshot;
        final List<String> calls = new ArrayList<>();
        boolean available = true, confirmed = true, failArm, failFollow;
        int checks, failConfirmationAt = -1;
        FakeBridge(Path root, ReturnStore.Snapshot snapshot) {
            this.snapshot = snapshot; returns = root.resolve("returns"); audit = root.resolve("audit.jsonl");
        }
        @Override public boolean availableWithReconnectGate() { calls.add("available"); return available; }
        @Override public void arm(UUID observer) throws Exception {
            calls.add("arm");
            check(new ReturnStore(returns).load(observer).orElseThrow().equals(snapshot), "arm before persisted return");
            if (failArm) throw new IOException("arm failed");
        }
        @Override public boolean protectionConfirmed(UUID observer) {
            calls.add("confirm"); checks++; return confirmed && checks != failConfirmationAt;
        }
        @Override public void follow(UUID observer, UUID target) throws Exception {
            calls.add("follow");
            check(Files.readString(audit).contains(snapshot.session().toString()), "follow before durable audit");
            if (failFollow) throw new IOException("follow failed after movement could have started");
        }
    }
    public static void main(String[] args) throws Exception {
        UUID observer = UUID.randomUUID(), target = UUID.randomUUID(), world = UUID.randomUUID();
        var snapshot = new ReturnStore.Snapshot(UUID.randomUUID(), observer, "Moderator", target, "Player",
                world, 12, 70, -34, 0, 0, "SURVIVAL", false, false);
        for (String scenario : List.of("unavailable", "denied", "arm-failure", "mask-unconfirmed", "mask-lost", "audit-failure", "follow-failure", "ok")) {
            Path root = Files.createTempDirectory("spectate-admission-");
            var returns = new ReturnStore(root.resolve("returns"));
            var bridge = new FakeBridge(root, snapshot);
            var audit = new SpectateAudit(scenario.equals("audit-failure") ? root.resolve("missing-parent").resolve("audit.jsonl") : root.resolve("audit.jsonl"));
            if (scenario.equals("audit-failure")) Files.writeString(root.resolve("missing-parent"), "not a directory");
            if (scenario.equals("unavailable")) bridge.available = false;
            if (scenario.equals("arm-failure")) bridge.failArm = true;
            if (scenario.equals("mask-unconfirmed")) bridge.confirmed = false;
            if (scenario.equals("mask-lost")) bridge.failConfirmationAt = 2;
            if (scenario.equals("follow-failure")) bridge.failFollow = true;
            var controller = new SpectateAdmission(returns, audit, (o, t) -> !scenario.equals("denied"), bridge);
            var result = controller.start(snapshot);
            if (scenario.equals("denied")) {
                check(result.outcome() == SpectateAdmission.Outcome.DENIED && bridge.calls.isEmpty(), "permission bypass");
                check(returns.load(observer).isEmpty(), "denied request persisted");
            } else if (scenario.equals("unavailable")) {
                check(result.outcome() == SpectateAdmission.Outcome.UNAVAILABLE && bridge.calls.equals(List.of("available")), "unsafe admission without bridge");
                check(returns.load(observer).isEmpty(), "unsupported request persisted");
            } else if (scenario.equals("ok")) {
                check(result.outcome() == SpectateAdmission.Outcome.STARTED, "protected admission failed");
                check(bridge.calls.equals(List.of("available", "arm", "confirm", "confirm", "follow")), "incorrect order");
                check(controller.phase(observer) == SpectateState.Phase.ACTIVE, "not active");
                check(controller.start(snapshot).outcome() == SpectateAdmission.Outcome.DENIED, "duplicate admitted");
            } else {
                check(result.outcome() == SpectateAdmission.Outcome.RECOVERY_REQUIRED, scenario + " not fail-closed");
                check(controller.phase(observer) == SpectateState.Phase.RECOVERY_REQUIRED, scenario + " missing recovery");
                check(returns.load(observer).orElseThrow().equals(snapshot), scenario + " lost saved state");
                check(controller.start(snapshot).outcome() == SpectateAdmission.Outcome.DENIED, scenario + " allowed a retry over pending state");
                if (!scenario.equals("follow-failure")) check(!bridge.calls.contains("follow"), scenario + " exposed target");
            }
        }
        System.out.println("Admission sequencing and injected failures passed (fake bridge only)");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

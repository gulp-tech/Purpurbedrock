package org.purpurmc.purpur.spectate;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Admission sequencing only, not a working plugin or a packet-privacy implementation.
 * The caller and adapters must run on one server thread. No production bridge exists yet.
 */
public final class SpectateAdmission {
    public enum Outcome { STARTED, UNAVAILABLE, DENIED, RECOVERY_REQUIRED }
    public record Result(Outcome outcome, String reason) { }

    public interface Eligibility {
        /** Must include permission, online status, Bedrock support and target validation. */
        boolean permits(UUID observer, UUID target);
    }
    public interface PrivacyBridge {
        /** Includes a pre-login quarantine/recovery gate; a HUD-only solution is never sufficient. */
        boolean availableWithReconnectGate();
        /** Must quarantine before changing packet routing. Receives no target location. */
        void arm(UUID observer) throws Exception;
        /** True only when all outgoing and incoming paths are protected and caches are safe. */
        boolean protectionConfirmed(UUID observer);
        /** This is the first method allowed to expose the target to the observer. */
        void follow(UUID observer, UUID target) throws Exception;
    }
    private final ReturnStore returns;
    private final SpectateAudit audit;
    private final Eligibility eligibility;
    private final PrivacyBridge bridge;
    private final Map<UUID, SpectateState> states = new HashMap<>();

    public SpectateAdmission(ReturnStore returns, SpectateAudit audit, Eligibility eligibility, PrivacyBridge bridge) {
        this.returns = Objects.requireNonNull(returns); this.audit = Objects.requireNonNull(audit);
        this.eligibility = Objects.requireNonNull(eligibility); this.bridge = Objects.requireNonNull(bridge);
    }

    /**
     * Persist before arming. Never expose the target before both masking and audit are confirmed.
     * Any failure after save retains recovery state; no best-effort removal or unsafe unmasking.
     */
    public Result start(ReturnStore.Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        UUID observer = snapshot.observer(), target = snapshot.target();
        if (states.containsKey(observer) || !eligibility.permits(observer, target)) return new Result(Outcome.DENIED, "not eligible or already active");
        if (!bridge.availableWithReconnectGate()) return new Result(Outcome.UNAVAILABLE, "verified protocol and reconnect gate unavailable");
        SpectateState state = new SpectateState();
        states.put(observer, state);
        state.prepare();
        try {
            // CREATE_NEW semantics: never overwrite an outstanding recovery snapshot.
            returns.save(snapshot);
            bridge.arm(observer);
            if (!bridge.protectionConfirmed(observer)) throw new IOException("protocol protection not confirmed");
            state.armedAfterDurableSave();
            audit.record(SpectateAudit.Event.START, snapshot.session(), observer, snapshot.observerName(), target, snapshot.targetName());
            state.auditCommitted();
            // Check again after synchronous I/O. The bridge itself must quarantine before it reports failure.
            if (!bridge.protectionConfirmed(observer) || !eligibility.permits(observer, target))
                throw new IOException("protection or eligibility changed");
            state.activate();
            bridge.follow(observer, target);
            return new Result(Outcome.STARTED, "protected observation started");
        } catch (Exception failure) {
            // save() itself may have succeeded before an I/O failure, so recovery is required even then.
            state.failedOrDisconnected();
            return new Result(Outcome.RECOVERY_REQUIRED, "admission failed; retain original state and quarantine");
        }
    }

    public SpectateState.Phase phase(UUID observer) {
        SpectateState state = states.get(observer);
        return state == null ? SpectateState.Phase.IDLE : state.phase();
    }
}

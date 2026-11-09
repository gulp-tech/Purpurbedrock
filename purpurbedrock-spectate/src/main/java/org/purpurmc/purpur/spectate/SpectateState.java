package org.purpurmc.purpur.spectate;

/**
 * Main-thread-confined lifecycle gate, not a transport or a security proof.
 * The eventual adapters must perform the real durable and protocol operations before advancing.
 */
public final class SpectateState {
    public enum Phase { IDLE, PREPARING, ARMED, AUDITED, ACTIVE, RESTORING, RECOVERY_REQUIRED, CLOSED }
    private Phase phase = Phase.IDLE;
    public Phase phase() { return phase; }

    public void prepare() { require(Phase.IDLE); phase = Phase.PREPARING; }
    /** Call only after the original state is durable and the protocol bridge is actually ready. */
    public void armedAfterDurableSave() { require(Phase.PREPARING); phase = Phase.ARMED; }
    /** Call only after the START event has been durably written. */
    public void auditCommitted() { require(Phase.ARMED); phase = Phase.AUDITED; }
    /** Only now may the adapter start moving the observer to the target. */
    public void activate() { require(Phase.AUDITED); phase = Phase.ACTIVE; }
    public void beginReturn() { require(Phase.ACTIVE); phase = Phase.RESTORING; }
    /** The old state must not be deleted until restoration and protocol cleanup are confirmed. */
    public void restoredAndCleared() { require(Phase.RESTORING); phase = Phase.CLOSED; }
    public void failedOrDisconnected() {
        if (phase == Phase.IDLE || phase == Phase.CLOSED) throw new IllegalStateException("No observation to recover");
        phase = Phase.RECOVERY_REQUIRED;
    }
    public void beginRecovery() { require(Phase.RECOVERY_REQUIRED); phase = Phase.RESTORING; }
    public boolean mayMoveToTarget() { return phase == Phase.ACTIVE; }
    private void require(Phase expected) {
        if (phase != expected) throw new IllegalStateException("Expected " + expected + ", got " + phase);
    }
}

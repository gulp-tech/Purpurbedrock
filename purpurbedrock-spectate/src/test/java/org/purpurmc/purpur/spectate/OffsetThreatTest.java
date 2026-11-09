package org.purpurmc.purpur.spectate;

/** Demonstrates a known-anchor attack. A pass means the attack was reproduced, not mitigated. */
public final class OffsetThreatTest {
    public static void main(String[] args) {
        CoordinateOffset mask = new CoordinateOffset(127, -319);
        int knownSpawnX = 120, knownSpawnZ = -44;
        int shownSpawnX = mask.blockX(knownSpawnX), shownSpawnZ = mask.blockZ(knownSpawnZ);
        int secretTargetX = 19423, secretTargetZ = -29071;
        int shownTargetX = mask.blockX(secretTargetX), shownTargetZ = mask.blockZ(secretTargetZ);
        int recoveredX = shownTargetX - (shownSpawnX - knownSpawnX);
        int recoveredZ = shownTargetZ - (shownSpawnZ - knownSpawnZ);
        if (recoveredX != secretTargetX || recoveredZ != secretTargetZ)
            throw new AssertionError("Expected deterministic offset to be recoverable from a known anchor");
        System.out.println("Known-anchor attack reproduced: a plain fixed offset alone is not private");
    }
}

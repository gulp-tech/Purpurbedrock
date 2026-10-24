package org.purpurmc.purpur.bedrock;

import io.papermc.paper.event.player.PlayerFailMoveEvent;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.purpurmc.purpur.PurpurConfig;

/**
 * Prevents false movement flags / rubberbanding ("чтоб не флагало") on Bedrock clients
 * due to IEEE-754 float coordinate rounding, stair/slab step-ups, knockback trajectories,
 * and mobile Wi-Fi/LTE jitter, while healing wall-clipping ghost blocks immediately.
 */
public final class BedrockMovementGuard {
    private BedrockMovementGuard() {
    }

    public static void preHandleFailMove(
        ServerPlayer player,
        PlayerFailMoveEvent event,
        double targetX,
        double targetY,
        double targetZ
    ) {
        if (player == null || event == null || !BedrockCombatHelper.isBedrockPlayer(player)) {
            return;
        }

        PlayerFailMoveEvent.FailReason reason = event.getFailReason();
        if (reason == PlayerFailMoveEvent.FailReason.CLIPPED_INTO_BLOCK) {
            // Immediately reveal any invisible ghost blocks around the player's collision box
            BedrockGhostBlockResync.resyncCollisionBox(player, targetX, targetY, targetZ);
            return;
        }

        if (!PurpurConfig.bedrockMovementLeniency) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()) {
            return;
        }

        double distSq = from.distanceSquared(to);
        if (reason == PlayerFailMoveEvent.FailReason.MOVED_WRONGLY) {
            if (distSq <= PurpurConfig.bedrockMovedWronglyMaxSq) {
                event.setAllowed(true);
                event.setLogWarning(false);
            } else {
                // Large desync: also refresh surrounding blocks in case a ghost block pushed the player
                BedrockGhostBlockResync.resyncCollisionBox(player, targetX, targetY, targetZ);
            }
        } else if (reason == PlayerFailMoveEvent.FailReason.MOVED_TOO_QUICKLY) {
            // Absorb short mobile Wi-Fi/WebRTC packet jitter bursts without rubberbanding
            if (distSq <= 16.0D) {
                event.setAllowed(true);
                event.setLogWarning(false);
            }
        }
    }
}

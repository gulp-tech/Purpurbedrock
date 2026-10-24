package org.purpurmc.purpur.bedrock;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlocksAttacks;
import org.jspecify.annotations.Nullable;
import org.purpurmc.purpur.PurpurConfig;

/**
 * Native Bedrock Edition combat mechanics & player detection helper.
 * Provides 1.8/Bedrock zero-cooldown PvP, Bedrock weapon damage scaling,
 * sprint-critical hits, and instant/sneak shield blocking.
 */
public final class BedrockCombatHelper {
    private BedrockCombatHelper() {
    }

    public static boolean isBedrockPlayer(@Nullable Entity entity) {
        if (!(entity instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (PurpurConfig.bedrockOnlyAllowBedrock) {
            return true;
        }
        if (serverPlayer.connection != null && BedrockGatekeeper.isBedrockConnection(serverPlayer.connection.connection)) {
            return true;
        }
        return BedrockGatekeeper.isBedrockUuid(serverPlayer.getUUID());
    }

    public static float adjustBaseDamage(Player attacker, ItemStack weapon, float javaBaseDamage) {
        if (!attacker.level().purpurConfig.bedrockCombatEnabled || !attacker.level().purpurConfig.bedrockAdjustWeaponDamage) {
            return javaBaseDamage;
        }
        if (weapon.isEmpty()) {
            return javaBaseDamage;
        }

        Item item = weapon.getItem();
        // In Bedrock Edition axes deal 1 less damage than swords of the same tier (not Java's slow 9-10 dmg axes)
        if (item == Items.WOODEN_AXE || item == Items.GOLDEN_AXE) {
            return Math.max(1.0F, javaBaseDamage - 3.0F);
        }
        if (item == Items.STONE_AXE || item == Items.COPPER_AXE) {
            return Math.max(1.0F, javaBaseDamage - 4.0F);
        }
        if (item == Items.IRON_AXE) {
            return Math.max(1.0F, javaBaseDamage - 3.0F);
        }
        if (item == Items.DIAMOND_AXE) {
            return Math.max(1.0F, javaBaseDamage - 2.0F);
        }
        if (item == Items.NETHERITE_AXE) {
            return Math.max(1.0F, javaBaseDamage - 2.0F);
        }
        return javaBaseDamage;
    }

    public static boolean canBedrockCriticalAttack(Player attacker, Entity target) {
        return attacker.fallDistance > 0.0D
            && !attacker.onGround()
            && !attacker.onClimbable()
            && !attacker.isInWater()
            && !attacker.hasEffect(MobEffects.BLINDNESS)
            && !attacker.isPassenger()
            && target instanceof LivingEntity;
    }

    public static @Nullable ItemStack getEffectiveBlockingItem(LivingEntity entity) {
        if (!entity.level().purpurConfig.bedrockCombatEnabled) {
            return entity.getItemBlockingWith();
        }

        // 1. Standard item use + instant shield block (bypass Java's 5-tick startup delay)
        if (entity.isUsingItem()) {
            ItemStack useItem = entity.getUseItem();
            if (!useItem.isEmpty()) {
                BlocksAttacks blocksAttacks = useItem.get(DataComponents.BLOCKS_ATTACKS);
                if (blocksAttacks != null) {
                    if (entity instanceof Player player && player.getCooldowns().isOnCooldown(useItem)) {
                        return null;
                    }
                    if (entity.level().purpurConfig.bedrockInstantShieldBlock) {
                        return useItem;
                    }
                }
            }
        }

        // 2. Native Bedrock sneak-to-block shield behavior
        if (entity instanceof Player player && entity.level().purpurConfig.bedrockSneakShieldBlock && player.isShiftKeyDown()) {
            ItemStack offhand = player.getOffhandItem();
            if (isReadyShield(player, offhand)) {
                return offhand;
            }
            ItemStack mainhand = player.getMainHandItem();
            if (isReadyShield(player, mainhand)) {
                return mainhand;
            }
        }

        return entity.getItemBlockingWith();
    }

    public static InteractionHand getEffectiveBlockingHand(LivingEntity entity, ItemStack blockingItem) {
        if (entity.isUsingItem() && entity.getUseItem() == blockingItem) {
            return entity.getUsedItemHand();
        }
        if (entity.getOffhandItem() == blockingItem) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    private static boolean isReadyShield(Player player, ItemStack stack) {
        if (stack.isEmpty() || stack.get(DataComponents.BLOCKS_ATTACKS) == null) {
            return false;
        }
        return !player.getCooldowns().isOnCooldown(stack);
    }
}

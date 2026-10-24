package org.purpurmc.purpur.bedrock;

import java.util.Objects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Implements Minecraft: Bedrock Edition combat mechanics on top of Purpur:
 *  - Zero 1.9+ weapon recharge cooldown (full melee & enchantment strength per hit)
 *  - Authentic Bedrock sword/axe/tool damage table (preserving custom plugin attributes)
 *  - Sprinting critical hits (jump-crits register while sprinting)
 *  - Instant 0-tick shield blocking & Bedrock sneak-to-block shield activation
 *  - Configurable sweep suppression, sprint combo retention, and projectile knockback
 */
public final class BedrockCombatHelper {
    private BedrockCombatHelper() {
    }

    public static float adjustBaseDamage(final Player attacker, final ItemStack weapon, final float currentBaseDamage) {
        if (!attacker.level().purpurConfig.bedrockCombatEnabled || !attacker.level().purpurConfig.bedrockUseBedrockWeaponDamage) {
            return currentBaseDamage;
        }
        if (weapon == null || weapon.isEmpty()) {
            return currentBaseDamage;
        }
        // Respect custom plugin attribute modifiers (MMOItems, ExecutableItems, custom kits, etc.)
        if (!Objects.equals(
            weapon.get(DataComponents.ATTRIBUTE_MODIFIERS),
            weapon.getItem().components().get(DataComponents.ATTRIBUTE_MODIFIERS)
        )) {
            return currentBaseDamage;
        }

        Identifier key = BuiltInRegistries.ITEM.getKey(weapon.getItem());
        if (!"minecraft".equals(key.getNamespace())) {
            return currentBaseDamage;
        }

        float delta = switch (key.getPath()) {
            // Bedrock swords: Wood/Gold 5, Stone/Copper 6, Iron 7, Diamond 8, Netherite 9
            case "wooden_sword", "golden_sword", "stone_sword", "copper_sword",
                 "iron_sword", "diamond_sword", "netherite_sword" -> 1.0F;
            // Bedrock axes: Wood/Gold 4, Stone/Copper 5, Iron 6, Diamond 7, Netherite 8
            case "wooden_axe", "golden_axe", "iron_axe" -> -3.0F;
            case "stone_axe", "copper_axe" -> -4.0F;
            case "diamond_axe", "netherite_axe" -> -2.0F;
            // Bedrock shovels
            case "wooden_shovel", "golden_shovel", "stone_shovel", "copper_shovel",
                 "iron_shovel", "diamond_shovel", "netherite_shovel" -> -0.5F;
            default -> 0.0F;
        };

        if (delta == 0.0F) {
            return currentBaseDamage;
        }
        return Math.max(1.0F, currentBaseDamage + delta);
    }

    public static boolean canBedrockCriticalAttack(final Player player, final Entity target) {
        return player.fallDistance > 0.0D
            && !player.onGround()
            && !player.onClimbable()
            && !player.isInWater()
            && !player.hasEffect(MobEffects.BLINDNESS)
            && !player.isPassenger()
            && target instanceof LivingEntity;
    }

    public static @Nullable ItemStack getEffectiveBlockingItem(final LivingEntity entity) {
        ItemStack active = entity.getItemBlockingWith();
        if (active != null) {
            return active;
        }
        if (!entity.level().purpurConfig.bedrockCombatEnabled || !(entity instanceof Player player)) {
            return null;
        }

        // Instant 0-tick shield warmup when actively using a shield
        if (entity.level().purpurConfig.bedrockInstantShieldBlock && player.isUsingItem()) {
            ItemStack useItem = player.getUseItem();
            if (!useItem.isEmpty() && useItem.has(DataComponents.BLOCKS_ATTACKS) && !player.getCooldowns().isOnCooldown(useItem)) {
                return useItem;
            }
        }

        // Bedrock sneak-to-shield activation
        if (entity.level().purpurConfig.bedrockSneakShieldBlocking && player.isShiftKeyDown()) {
            ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!main.isEmpty() && main.has(DataComponents.BLOCKS_ATTACKS) && !player.getCooldowns().isOnCooldown(main)) {
                return main;
            }
            ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
            if (!off.isEmpty() && off.has(DataComponents.BLOCKS_ATTACKS) && !player.getCooldowns().isOnCooldown(off)) {
                return off;
            }
        }

        return null;
    }

    public static InteractionHand getEffectiveBlockingHand(final LivingEntity entity, final ItemStack blockingWith) {
        if (entity.isUsingItem() && entity.getUseItem() == blockingWith) {
            return entity.getUsedItemHand();
        }
        if (entity.getItemInHand(InteractionHand.OFF_HAND) == blockingWith) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }
}

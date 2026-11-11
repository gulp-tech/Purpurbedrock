package org.geysermc.geyser.item.custom;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.geyser.api.predicate.MinecraftPredicate;
import org.geysermc.geyser.api.predicate.context.item.ItemPredicateContext;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

/** A mapping-only visual predicate; gameplay authority remains on the server. */
public record ServerMarkerPredicate(String key, String fallbackKey, String value) implements MinecraftPredicate<ItemPredicateContext> {
    @Override
    public boolean test(ItemPredicateContext context) {
        if (!(context instanceof GeyserItemPredicateContext item)) return false;
        NbtMap data = item.serverComponents().get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return false;
        NbtMap pdc = data.getCompound("PublicBukkitValues", null);
        if (pdc == null) return false;
        Object current = pdc.get(key);
        // Mirror Bukkit's STRING lookup: a current string takes precedence, even if
        // it has a different value. Only a missing/non-string key allows fallback.
        if (current instanceof String) return value.equals(current);
        return fallbackKey != null && value.equals(pdc.get(fallbackKey));
    }
}

package org.purpurmc.purpur.anticheat.model;

public enum SeverityColor {
    GREEN("&a", "🟢 Зелёный (Чисто)"),
    ORANGE("&6", "🟠 Оранжевый (Подозрение)"),
    RED("&c", "🔴 Красный (Софт / Риск)");

    private final String colorCode;
    private final String displayName;

    SeverityColor(String colorCode, String displayName) {
        this.colorCode = colorCode;
        this.displayName = displayName;
    }

    public String colorCode() {
        return colorCode;
    }

    public String displayName() {
        return displayName;
    }
}

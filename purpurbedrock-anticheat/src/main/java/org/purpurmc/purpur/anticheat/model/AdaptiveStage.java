package org.purpurmc.purpur.anticheat.model;

public enum AdaptiveStage {
    DORMANT("Глубокий сон", "&8СОН (0%)"),
    SLEEPING("Спящий пульс", "&aСОН"),
    WAKING("Плавное пробуждение", "&eПРОБУЖДЕНИЕ"),
    AWAKE("Полный контроль", "&cАКТИВЕН");

    private final String title;
    private final String badge;

    AdaptiveStage(String title, String badge) {
        this.title = title;
        this.badge = badge;
    }

    public String title() {
        return title;
    }

    public String badge() {
        return badge;
    }
}

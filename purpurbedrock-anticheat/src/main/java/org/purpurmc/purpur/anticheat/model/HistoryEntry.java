package org.purpurmc.purpur.anticheat.model;

public record HistoryEntry(
    long timestampMs,
    SeverityColor severity,
    String checkName,
    String details,
    double vlAfter,
    double avgSnapshot,
    int pingMs,
    double serverTps,
    String device,
    String input,
    String actionTaken
) {
}

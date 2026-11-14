package com.pexserver.completions;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletionCapacityTest {
    @Test void increasesOnlyTheUnchangedLegacyDefaults() {
        var upgraded = CompletionCapacity.effective(128, 128, 1024, true);
        assertEquals(new CompletionCapacity.Values(512, 512, 4096), upgraded);
        assertEquals(new CompletionCapacity.Values(128, 128, 1024), CompletionCapacity.effective(128, 128, 1024, false));
        assertEquals(new CompletionCapacity.Values(128, 128, 2048), CompletionCapacity.effective(128, 128, 2048, true));
        assertEquals(new CompletionCapacity.Values(256, 128, 1024), CompletionCapacity.effective(256, 128, 1024, true));
    }
}

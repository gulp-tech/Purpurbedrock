import org.purpurmc.purpur.bedrock.BedrockCommandLimits;

public final class BedrockCommandLimitsTest {
    private static void expect(int expected, int actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + " got " + actual);
    }
    public static void main(String[] args) {
        expect(512, BedrockCommandLimits.advertised(96, true));
        expect(96, BedrockCommandLimits.advertised(96, false));
        expect(80, BedrockCommandLimits.advertised(80, true));
        expect(256, BedrockCommandLimits.advertised(256, true));
        expect(32, BedrockCommandLimits.advertised(1, true));
        expect(1024, BedrockCommandLimits.advertised(100000, true));
        System.out.println("BEDROCK_COMMAND_LIMITS_OK");
    }
}

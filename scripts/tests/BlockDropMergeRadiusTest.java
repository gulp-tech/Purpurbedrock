import org.purpurmc.purpur.bedrock.BlockDropMergeRadius;

public final class BlockDropMergeRadiusTest {
    public static void main(String[] args) {
        check(3.0, BlockDropMergeRadius.resolve(0.5, 3.0, true));
        check(0.5, BlockDropMergeRadius.resolve(0.5, 3.0, false));
        check(5.0, BlockDropMergeRadius.resolve(5.0, 3.0, true));
        check(0.0, BlockDropMergeRadius.resolve(0.0, 3.0, true));
        check(0.5, BlockDropMergeRadius.resolve(0.5, 0.0, true));
        check(0.5, BlockDropMergeRadius.resolve(0.5, Double.NaN, true));
        check(0.5, BlockDropMergeRadius.resolve(0.5, Double.POSITIVE_INFINITY, true));
        check(8.0, BlockDropMergeRadius.resolve(0.5, 100.0, true));
    }

    private static void check(double expected, double actual) {
        if (Double.compare(expected, actual) != 0) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }
}

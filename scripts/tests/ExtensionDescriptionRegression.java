import java.io.StringReader;
import org.geysermc.geyser.extension.GeyserExtensionDescription;
import org.geysermc.geyser.api.extension.exception.InvalidDescriptionException;

public class ExtensionDescriptionRegression {
    private static final String BASE = "id: fixture\nname: Fixture\nmain: test.Fixture\napi: 2.0.0\nversion: 1.0.0\n";
    private static void rejected(String yaml) throws Exception {
        try {
            GeyserExtensionDescription.fromYaml(new StringReader(yaml));
            throw new AssertionError("invalid extension.yml was accepted: " + yaml);
        } catch (InvalidDescriptionException expected) {
            // Strict validation of mandatory properties and unknown keys is retained.
        }
    }
    public static void main(String[] args) throws Exception {
        var parsed = GeyserExtensionDescription.fromYaml(new StringReader(BASE + "description: Example extension\n"));
        if (!parsed.id().equals("fixture") || !parsed.main().equals("test.Fixture")) throw new AssertionError(parsed);
        GeyserExtensionDescription.fromYaml(new StringReader(BASE));
        rejected(BASE.replace("main: test.Fixture\n", ""));
        rejected(BASE.replace("id: fixture", "id: INVALID!"));
        rejected(BASE + "unknown_fixture_property: true\n");
        System.out.println("EXTENSION_DESCRIPTION_REGRESSION_OK");
    }
}

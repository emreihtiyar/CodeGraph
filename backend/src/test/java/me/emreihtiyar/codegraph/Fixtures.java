package me.emreihtiyar.codegraph;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

/** Test fixture'larına erişim. */
public final class Fixtures {

    private Fixtures() {}

    /** PRD NFR-09 mini sistemi: 1 frontend, 2 BFF, 2 API, 1 adaptör. Beklenen sonuçlar {@code EXPECTED.md}'de. */
    public static Path miniSystem() {
        return resource("fixtures/mini-system");
    }

    public static Path miniSystemConfig() {
        return miniSystem().resolve("codegraph-repos.yml");
    }

    public static Path resource(String name) {
        try {
            return Path.of(Objects.requireNonNull(Fixtures.class.getClassLoader().getResource(name), name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}

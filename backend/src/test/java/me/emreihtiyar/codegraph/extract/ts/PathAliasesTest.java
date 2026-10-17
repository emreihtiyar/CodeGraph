package me.emreihtiyar.codegraph.extract.ts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathAliasesTest {

    @TempDir
    Path root;

    @Test
    void resolvesWildcardTargetsRelativeToBaseUrl() throws IOException {
        // Windows'ta '*' yol karakteri olamaz; hedefler Path'e çevrilmeden çözülmeli
        Files.writeString(root.resolve("tsconfig.json"), """
                {
                  "compilerOptions": {
                    "baseUrl": "./src",
                    "paths": {
                      "@app/*": ["projects/*", "../shared/*"],
                      "config": ["./config/index.ts"],
                      "outside/*": ["../../x/*"]
                    }
                  }
                }
                """);
        List<ParseIssue> issues = new ArrayList<>();

        PathAliases aliases = PathAliases.load(root, issues);

        assertThat(issues).isEmpty();
        assertThat(aliases.resolve("@app/orders/api"))
                .containsExactly("src/projects/orders/api", "shared/orders/api", "src/@app/orders/api");
        assertThat(aliases.resolve("config")).containsExactly("src/config/index.ts", "src/config");
        assertThat(aliases.resolve("outside/a")).containsExactly("src/outside/a");
    }

    @Test
    void reportsUnreadableConfigInsteadOfFailing() throws IOException {
        Files.writeString(root.resolve("tsconfig.json"), "{ \"compilerOptions\": ");
        List<ParseIssue> issues = new ArrayList<>();

        PathAliases aliases = PathAliases.load(root, issues);

        assertThat(issues).singleElement().satisfies(i -> assertThat(i.message()).startsWith("tsconfig okunamadı"));
        assertThat(aliases.resolve("@/x")).containsExactly("src/x");
    }
}

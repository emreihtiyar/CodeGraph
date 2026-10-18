package me.emreihtiyar.codegraph.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.emreihtiyar.codegraph.Fixtures;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.config.RepoDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoScannerTest {

    private final RepoScanner scanner = new RepoScanner();

    @TempDir
    Path tempDir;

    @Test
    void scansJavaRepoWithoutTestSources() {
        ScannedRepo scanned = scanner.scan(repo("credit-api", Fixtures.miniSystem().resolve("credit-api"), Layer.API));

        assertThat(paths(scanned.javaSources()))
                .contains("src/main/java/com/bank/credit/api/CreditController.java")
                .noneMatch(p -> p.contains("src/test/"))
                .isSorted();
        assertThat(paths(scanned.appConfigs())).containsExactly("src/main/resources/application.yml");
        assertThat(scanned.apiCallFiles()).isEmpty();
        assertThat(scanned.frontendSources()).isEmpty();
    }

    @Test
    void scansFrontendRepo() {
        ScannedRepo scanned =
                scanner.scan(repo("leasing-web", Fixtures.miniSystem().resolve("leasing-web"), Layer.FRONTEND));

        assertThat(paths(scanned.apiCallFiles()))
                .containsExactly("src/api/creditApplicationApiCalls.ts", "src/api/scoringApiCalls.js");
        assertThat(paths(scanned.frontendSources()))
                .contains("src/routes.ts", "src/shared/asyncComponent.tsx", "src/components/ScoreBadge.tsx",
                        "src/api/callApi.ts", "src/shared/hooks/useScoring.ts")
                .doesNotContain("src/components/ScoreBadge.test.tsx");
        assertThat(scanned.javaSources()).isEmpty();
    }

    @Test
    void skipsOutputDirsOnlyOutsideSrc() throws IOException {
        touch("target/classes/Generated.java");
        touch("module-a/build/Out.java");
        touch("module-a/src/main/java/com/x/build/Builder.java");
        touch("src/main/java/com/x/out/Writer.java");
        touch("src/main/java/com/x/test/Fixture.java");
        touch("src/test/java/com/x/SomeTest.java");
        touch("src/main/resources/application-prod.yaml");
        touch("src/main/resources/bootstrap.properties");
        touch("src/main/resources/logback.xml");
        touch(".git/objects/Foo.java");

        ScannedRepo scanned = scanner.scan(repo("x", tempDir, Layer.API));

        assertThat(paths(scanned.javaSources())).containsExactly(
                "module-a/src/main/java/com/x/build/Builder.java",
                "src/main/java/com/x/out/Writer.java",
                "src/main/java/com/x/test/Fixture.java");
        assertThat(paths(scanned.appConfigs()))
                .containsExactly("src/main/resources/application-prod.yaml", "src/main/resources/bootstrap.properties");
    }

    @Test
    void skipsNodeModulesAndDeclarationFiles() throws IOException {
        touch("node_modules/lib/fooApiCalls.ts");
        touch("src/types.d.ts");
        touch("src/api/OrdersAPICalls.tsx");
        touch("src/App.jsx");
        touch("src/App.spec.jsx");

        ScannedRepo scanned = scanner.scan(repo("web", tempDir, Layer.FRONTEND));

        assertThat(paths(scanned.apiCallFiles())).containsExactly("src/api/OrdersAPICalls.tsx");
        assertThat(paths(scanned.frontendSources())).containsExactly("src/App.jsx");
    }

    private void touch(String relative) throws IOException {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
    }

    private static RepoDefinition repo(String name, Path path, Layer layer) {
        return new RepoDefinition(name, path, layer, null, null, List.of());
    }

    private static List<String> paths(List<Path> paths) {
        return paths.stream().map(p -> p.toString().replace('\\', '/')).toList();
    }
}

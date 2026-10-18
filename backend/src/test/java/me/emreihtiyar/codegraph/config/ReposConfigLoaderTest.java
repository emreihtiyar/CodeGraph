package me.emreihtiyar.codegraph.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.emreihtiyar.codegraph.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReposConfigLoaderTest {

    private final ReposConfigLoader loader = new ReposConfigLoader();

    @TempDir
    Path tempDir;

    @Test
    void loadsMiniSystemConfig() {
        ReposConfig config = loader.load(Fixtures.miniSystemConfig());

        assertThat(config.repos()).extracting(RepoDefinition::name).containsExactly(
                "leasing-web", "leasing-credit-bff", "scoring-bff", "credit-api", "sales-api", "core-adapter");
        RepoDefinition scoring = config.repo("scoring-bff").orElseThrow();
        assertThat(scoring.layer()).isEqualTo(Layer.BFF);
        assertThat(scoring.gatewayPrefix()).isEqualTo("scoring");
        assertThat(scoring.path()).isEqualTo(Fixtures.miniSystem().resolve("scoring-bff"));
        assertThat(config.repo("leasing-web").orElseThrow().layer()).isEqualTo(Layer.FRONTEND);
        assertThat(config.placeholders()).isEmpty();
    }

    @Test
    void readsOptionalFieldsAndNormalizesGatewayPrefix() throws IOException {
        Files.createDirectories(tempDir.resolve("repos/bff"));
        Path file = write("""
                placeholders:
                  clients.credit.name: credit-api
                repos:
                  - name: bff
                    path: repos/bff
                    layer: bff
                    serviceName: my-bff
                    gatewayPrefix: /my-bff/
                    feignName: MY-BFF-SERVICE
                    aliases: [my-bff-v2, legacy-bff]
                """);

        ReposConfig config = loader.load(file);

        RepoDefinition repo = config.repos().get(0);
        assertThat(repo.layer()).isEqualTo(Layer.BFF);
        assertThat(repo.serviceName()).isEqualTo("my-bff");
        assertThat(repo.gatewayPrefix()).isEqualTo("my-bff");
        assertThat(repo.feignName()).isEqualTo("MY-BFF-SERVICE");
        assertThat(repo.aliases()).containsExactly("my-bff-v2", "legacy-bff");
        assertThat(config.placeholders()).containsEntry("clients.credit.name", "credit-api");
    }

    @Test
    void reportsAllErrorsTogether() throws IOException {
        Files.createDirectories(tempDir.resolve("a"));
        Path file = write("""
                repoz: []
                repos:
                  - name: a
                    path: a
                    layer: GATEWAY
                  - name: a
                    path: does-not-exist
                    layer: API
                    colour: red
                  - path: a
                    layer: API
                """);

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ReposConfigException.class)
                .satisfies(e -> assertThat(((ReposConfigException) e).errors())
                        .anySatisfy(m -> assertThat(m).contains("bilinmeyen alan 'repoz'"))
                        .anySatisfy(m -> assertThat(m).contains("geçersiz 'layer' değeri 'GATEWAY'"))
                        .anySatisfy(m -> assertThat(m).contains("'name' tekrar ediyor"))
                        .anySatisfy(m -> assertThat(m).contains("bir dizin değil"))
                        .anySatisfy(m -> assertThat(m).contains("bilinmeyen alan 'colour'"))
                        .anySatisfy(m -> assertThat(m).contains("repos[2]: 'name' zorunlu")));
    }

    @Test
    void rejectsMissingOrEmptyFile() throws IOException {
        assertThatThrownBy(() -> loader.load(tempDir.resolve("nope.yml")))
                .isInstanceOf(ReposConfigException.class)
                .hasMessageContaining("Dosya bulunamadı");
        Path empty = write("repos: []");
        assertThatThrownBy(() -> loader.load(empty)).hasMessageContaining("'repos' boş olmayan bir liste olmalı");
        Path invalid = write("repos: [ : :");
        assertThatThrownBy(() -> loader.load(invalid)).hasMessageContaining("Dosya okunamadı");
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("repos-" + System.nanoTime() + ".yml");
        Files.writeString(file, content);
        return file;
    }
}

package me.emreihtiyar.codegraph.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import me.emreihtiyar.codegraph.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** {@code application.yml} içinden repo tanımı (qa.md S3) ve dosya ile birlikte kullanımı. */
class ReposConfigProviderTest {

    @Test
    void inlineReposWinOverFile() {
        Path web = Fixtures.miniSystem().resolve("leasing-web");
        Map<String, String> props = new LinkedHashMap<>();
        props.put("codegraph.repos-file", "/does/not/exist.yml");
        props.put("codegraph.repos[0].name", "web");
        props.put("codegraph.repos[0].path", web.toString());
        props.put("codegraph.repos[0].layer", "frontend");
        props.put("codegraph.repos[1].name", "bff");
        props.put("codegraph.repos[1].path", Fixtures.miniSystem().resolve("scoring-bff").toString());
        props.put("codegraph.repos[1].layer", "BFF");
        props.put("codegraph.repos[1].gateway-prefix", "/scoring/");
        props.put("codegraph.repos[1].aliases[0]", "scoring");
        props.put("codegraph.placeholders.[clients.credit.name]", "credit-api");

        ReposConfig config = provider(props).load();

        assertThat(config.repos()).extracting(RepoDefinition::name).containsExactly("web", "bff");
        RepoDefinition bff = config.repo("bff").orElseThrow();
        assertThat(bff.layer()).isEqualTo(Layer.BFF);
        assertThat(bff.gatewayPrefix()).isEqualTo("scoring");
        assertThat(bff.aliases()).containsExactly("scoring");
        assertThat(config.repo("web").orElseThrow().path()).isEqualTo(web);
        assertThat(config.placeholders()).containsEntry("clients.credit.name", "credit-api");
    }

    @Test
    void fileIsUsedWhenNoInlineReposAndPlaceholdersAreMerged() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("codegraph.repos-file", Fixtures.miniSystemConfig().toString());
        props.put("codegraph.placeholders.[legacy.name]", "legacy-score-service");

        ReposConfig config = provider(props).load();

        assertThat(config.repos()).hasSize(6);
        assertThat(config.placeholders()).containsEntry("legacy.name", "legacy-score-service");
    }

    @Test
    void inlineReposAreValidatedLikeFile() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("codegraph.repos[0].name", "x");
        props.put("codegraph.repos[0].path", "/does/not/exist");
        props.put("codegraph.repos[0].layer", "GATEWAY");
        props.put("codegraph.repos[1].path", Fixtures.miniSystem().toString());
        props.put("codegraph.repos[1].layer", "API");

        assertThatThrownBy(() -> provider(props).load())
                .isInstanceOf(ReposConfigException.class)
                .satisfies(e -> assertThat(((ReposConfigException) e).errors()).hasSize(3)
                        .anyMatch(m -> m.contains("'path' bir dizin değil"))
                        .anyMatch(m -> m.contains("geçersiz 'layer'"))
                        .anyMatch(m -> m.contains("'name' zorunlu")));
    }

    private static ReposConfigProvider provider(Map<String, String> props) {
        CodeGraphProperties properties = new Binder(new MapConfigurationPropertySource(props))
                .bindOrCreate("codegraph", CodeGraphProperties.class);
        return new ReposConfigProvider(properties, new ReposConfigLoader());
    }
}

package me.emreihtiyar.codegraph.extract.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringPropertiesTest {

    @TempDir
    Path dir;

    @Test
    void baseFileWinsOverProfilesAndFallbacks() throws IOException {
        Files.writeString(dir.resolve("application-dev.yml"), "spring.application.name: dev-name\nx: dev\n");
        Files.writeString(dir.resolve("application.yml"), """
                spring:
                  application:
                    name: credit-api
                server:
                  servlet:
                    context-path: /credit-api
                list:
                  - a
                  - b
                ---
                spring.application.name: second-doc
                """);
        Files.writeString(dir.resolve("bootstrap.properties"), "y=boot\n");
        List<ParseIssue> issues = new ArrayList<>();
        SpringProperties props = SpringProperties.load(dir,
                List.of(Path.of("application-dev.yml"), Path.of("application.yml"), Path.of("bootstrap.properties")),
                Map.of("z", "fallback", "x", "ignored"), issues);

        assertThat(issues).isEmpty();
        assertThat(props.get("spring.application.name")).contains("credit-api");
        assertThat(props.get("server.servlet.context-path")).contains("/credit-api");
        assertThat(props.get("list[1]")).contains("b");
        assertThat(props.get("x")).contains("dev");
        assertThat(props.get("y")).contains("boot");
        assertThat(props.get("z")).contains("fallback");
    }

    @Test
    void resolvesPlaceholdersWithDefaults() {
        SpringProperties props = new SpringProperties(Map.of("a", "${b}-x", "b", "B", "loop", "${loop}"));
        assertThat(props.resolve("${a}/${missing:def}")).isEqualTo("B-x/def");
        assertThat(props.resolve("${unknown}")).isEqualTo("${unknown}");
        assertThat(SpringProperties.isResolved(props.resolve("${unknown}"))).isFalse();
        assertThat(SpringProperties.isResolved(props.resolve("${loop}"))).isFalse();
    }

    @Test
    void unreadableFileIsReportedNotThrown() throws IOException {
        Files.writeString(dir.resolve("application.yml"), "a: [unclosed\n");
        List<ParseIssue> issues = new ArrayList<>();
        SpringProperties.load(dir, List.of(Path.of("application.yml")), Map.of(), issues);
        assertThat(issues).hasSize(1);
    }
}

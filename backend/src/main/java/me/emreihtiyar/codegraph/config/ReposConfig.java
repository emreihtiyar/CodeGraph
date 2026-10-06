package me.emreihtiyar.codegraph.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repo yapılandırma dosyasının (PRD FR-01) çözümlenmiş hali.
 *
 * @param source       okunan dosya
 * @param repos        tanım sırasıyla repolar
 * @param placeholders application dosyalarında çözülemeyen {@code ${...}} değerleri için elle verilen karşılıklar
 *                     (ör. config server'dan gelen Feign adları, PRD §11.1)
 */
public record ReposConfig(Path source, List<RepoDefinition> repos, Map<String, String> placeholders) {

    public ReposConfig {
        repos = List.copyOf(repos);
        placeholders = Map.copyOf(placeholders);
    }

    public Optional<RepoDefinition> repo(String name) {
        return repos.stream().filter(r -> r.name().equals(name)).findFirst();
    }
}

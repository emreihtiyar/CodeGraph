package me.emreihtiyar.codegraph.scan;

import java.nio.file.Path;
import java.util.List;
import me.emreihtiyar.codegraph.config.RepoDefinition;

/**
 * Bir reponun Scan aşaması çıktısı. Tüm yollar repo köküne görelidir ve sıralıdır (deterministik analiz için).
 *
 * @param javaSources      üretim Java kaynakları (test kaynakları hariç)
 * @param appConfigs       {@code application*.yml/yaml/properties} ve {@code bootstrap*} dosyaları
 * @param apiCallFiles     adı {@code ApiCalls} içeren frontend dosyaları (PRD FR-07)
 * @param frontendSources  diğer TS/JS kaynakları: component'ler, route tanımları (PRD FR-08)
 */
public record ScannedRepo(
        RepoDefinition repo,
        List<Path> javaSources,
        List<Path> appConfigs,
        List<Path> apiCallFiles,
        List<Path> frontendSources) {

    public ScannedRepo {
        javaSources = List.copyOf(javaSources);
        appConfigs = List.copyOf(appConfigs);
        apiCallFiles = List.copyOf(apiCallFiles);
        frontendSources = List.copyOf(frontendSources);
    }

    public Path absolute(Path relative) {
        return repo.path().resolve(relative);
    }

    public int fileCount() {
        return javaSources.size() + appConfigs.size() + apiCallFiles.size() + frontendSources.size();
    }
}

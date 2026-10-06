package me.emreihtiyar.codegraph.extract.java;

import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.Fixtures;
import me.emreihtiyar.codegraph.config.ReposConfig;
import me.emreihtiyar.codegraph.config.ReposConfigLoader;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.scan.RepoScanner;

/** Mini sistemin Java repolarını analiz eden test yardımcısı. */
final class MiniSystemJava {

    private static final ReposConfig CONFIG = new ReposConfigLoader().load(Fixtures.miniSystemConfig());

    private MiniSystemJava() {}

    static RepoFacts analyze(String repoName) {
        var repo = CONFIG.repo(repoName).orElseThrow();
        var scanned = new RepoScanner().scan(repo);
        return new JavaRepoAnalyzer().analyze(scanned, List.of(), new TypeModel(), Map.of()).facts();
    }
}

package me.emreihtiyar.codegraph;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import me.emreihtiyar.codegraph.config.CodeGraphProperties;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.config.RepoDefinition;
import me.emreihtiyar.codegraph.config.ReposConfig;
import me.emreihtiyar.codegraph.config.ReposConfigLoader;
import me.emreihtiyar.codegraph.config.ReposConfigProvider;
import me.emreihtiyar.codegraph.extract.java.JavaRepoAnalyzer;
import me.emreihtiyar.codegraph.extract.java.TypeModel;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.ts.FrontendRepoAnalyzer;
import me.emreihtiyar.codegraph.scan.RepoScanner;

/** Mini sistemin tüm repolarını analiz eden test yardımcısı (Link/Load testleri için). */
public final class MiniSystem {

    public static final ReposConfig CONFIG = new ReposConfigLoader().load(Fixtures.miniSystemConfig());

    private static List<RepoFacts> facts;

    private MiniSystem() {}

    /** Tüm repoların olguları; analiz bir kez yapılır. */
    public static synchronized List<RepoFacts> facts() {
        if (facts == null) {
            facts = CONFIG.repos().stream().map(MiniSystem::analyze).toList();
        }
        return facts;
    }

    public static Map<String, List<String>> aliases() {
        return CONFIG.repos().stream().collect(Collectors.toMap(RepoDefinition::name, RepoDefinition::aliases));
    }

    public static ReposConfigProvider configProvider() {
        return new ReposConfigProvider(
                new CodeGraphProperties(Fixtures.miniSystemConfig(), null, null, null), new ReposConfigLoader());
    }

    private static RepoFacts analyze(RepoDefinition repo) {
        var scanned = new RepoScanner().scan(repo);
        if (repo.layer() == Layer.FRONTEND) {
            return new FrontendRepoAnalyzer().analyze(scanned);
        }
        return new JavaRepoAnalyzer().analyze(scanned, List.of(), new TypeModel(), CONFIG.placeholders()).facts();
    }
}

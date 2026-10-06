package me.emreihtiyar.codegraph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.emreihtiyar.codegraph.analysis.AnalysisService;
import me.emreihtiyar.codegraph.extract.java.JavaRepoAnalyzer;
import me.emreihtiyar.codegraph.extract.ts.FrontendRepoAnalyzer;
import me.emreihtiyar.codegraph.graph.GraphStore;
import me.emreihtiyar.codegraph.graph.neo4j.EmbeddedNeo4jConfig;
import me.emreihtiyar.codegraph.graph.neo4j.EmbeddedNeo4jGraphStore;
import me.emreihtiyar.codegraph.link.Linker;
import me.emreihtiyar.codegraph.load.GraphLoader;
import me.emreihtiyar.codegraph.query.GraphQueryService;
import me.emreihtiyar.codegraph.scan.RepoScanner;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;

/**
 * Mini sistemin analiz edilmiş grafı; JVM başına bir kez kurulur ve salt-okunur testler arasında paylaşılır.
 * Grafı değiştiren testler kendi veritabanını açmalıdır.
 */
public final class MiniSystemGraph {

    private static GraphStore store;
    private static AnalysisService analysis;

    private MiniSystemGraph() {}

    public static synchronized GraphStore store() {
        if (store == null) {
            try {
                Path home = Files.createTempDirectory("codegraph-mini");
                DatabaseManagementService dbms = EmbeddedNeo4jConfig.create(home);
                Runtime.getRuntime().addShutdownHook(new Thread(dbms::shutdown));
                store = new EmbeddedNeo4jGraphStore(dbms.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            analysis = new AnalysisService(MiniSystem.configProvider(), new RepoScanner(), new JavaRepoAnalyzer(),
                    new FrontendRepoAnalyzer(), new Linker(), new GraphLoader(), store);
            analysis.run();
        }
        return store;
    }

    public static synchronized AnalysisService analysis() {
        store();
        return analysis;
    }

    public static GraphQueryService queryService() {
        return new GraphQueryService(store(), analysis());
    }
}

package me.emreihtiyar.codegraph.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.MiniSystem;
import me.emreihtiyar.codegraph.extract.java.JavaRepoAnalyzer;
import me.emreihtiyar.codegraph.extract.ts.FrontendRepoAnalyzer;
import me.emreihtiyar.codegraph.graph.neo4j.EmbeddedNeo4jConfig;
import me.emreihtiyar.codegraph.graph.neo4j.EmbeddedNeo4jGraphStore;
import me.emreihtiyar.codegraph.link.Linker;
import me.emreihtiyar.codegraph.load.GraphLoader;
import me.emreihtiyar.codegraph.scan.RepoScanner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;

/**
 * Mini sistemin uçtan uca analizi: Scan → Extract → Link → Load → Report. Beklenen değerler
 * {@code fixtures/mini-system/EXPECTED.md}, özellikle "Uçtan uca referans izler".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalysisServiceTest {

    @TempDir
    static Path home;

    private DatabaseManagementService dbms;
    private EmbeddedNeo4jGraphStore store;
    private AnalysisReport report;

    @BeforeAll
    void analyze() {
        dbms = EmbeddedNeo4jConfig.create(home);
        store = new EmbeddedNeo4jGraphStore(dbms.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME));
        report = newService().run();
    }

    @AfterAll
    void stop() {
        dbms.shutdown();
    }

    private AnalysisService newService() {
        return new AnalysisService(MiniSystem.configProvider(), new RepoScanner(), new JavaRepoAnalyzer(),
                new FrontendRepoAnalyzer(), new Linker(), new GraphLoader(), store);
    }

    @Test
    void reportListsUnresolvedAndAmbiguous() {
        assertThat(report.parseIssues()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(report.unresolvedLinks())
                .extracting(AnalysisReport.LinkIssue::kind, i -> i.source().replaceAll(".*[.#/]", ""),
                        AnalysisReport.LinkIssue::targetService)
                .containsExactlyInAnyOrder(
                        tuple("API_CALL", "getApplicationDocument", null),
                        tuple("API_CALL", "getScoreHistory", "scoring-bff"),
                        tuple("FEIGN_METHOD", "getLegacyScore(Long)", "legacy-score-service"));
        assertThat(report.ambiguousLinks()).isEmpty();
        assertThat(report.ambiguousCalls())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.caller()).contains("ScoreService#score");
                    assertThat(c.candidates()).hasSize(2)
                            .anyMatch(s -> s.contains("DefaultRiskCalculator#calculate"))
                            .anyMatch(s -> s.contains("ConservativeRiskCalculator#calculate"));
                });
        assertThat(report.repos()).hasSize(6).allMatch(r -> r.issues() == 0);
        assertThat(report.nodeCounts()).containsEntry("Service", 7).containsEntry("Screen", 2);
    }

    @Test
    void serviceDependencies() {
        List<Map<String, Object>> rows = store.query("""
                MATCH (a:Service)-[d:DEPENDS_ON]->(b:Service)
                RETURN a.name AS from, b.name AS to, d.callCount AS calls, b.analyzed AS analyzed""", Map.of());
        assertThat(rows)
                .extracting(r -> r.get("from"), r -> r.get("to"), r -> r.get("analyzed"))
                .containsExactlyInAnyOrder(
                        tuple("leasing-web", "leasing-credit-bff", true),
                        tuple("leasing-web", "scoring-bff", true),
                        tuple("leasing-credit-bff", "credit-api", true),
                        tuple("leasing-credit-bff", "sales-api", true),
                        tuple("scoring-bff", "credit-api", true),
                        tuple("scoring-bff", "sales-api", true),
                        tuple("scoring-bff", "legacy-score-service", false),
                        tuple("credit-api", "core-adapter", true),
                        tuple("sales-api", "core-adapter", true));
        assertThat(rows).allMatch(r -> ((Number) r.get("calls")).longValue() >= 1);
    }

    @Test
    void forwardTraceFromScreenToTables() {
        assertThat(tablesReachableFrom("/credit-application")).containsExactlyInAnyOrder(
                "LEASING.CREDIT", "offers", "CORE.CUSTOMER_SNAPSHOT", "customer_limit", "dealer");
    }

    @Test
    void backwardTraceFromTablesToScreens() {
        assertThat(screensReaching("offers")).containsExactlyInAnyOrder("/credit-application", "/customers/:customerId");
        assertThat(screensReaching("CORE.CUSTOMER_SNAPSHOT")).containsExactly("/credit-application");
    }

    @Test
    void unresolvedLinksStayInGraph() {
        List<Map<String, Object>> rows = store.query("""
                MATCH (a:ApiCall) WHERE a.resolution = 'UNRESOLVED'
                OPTIONAL MATCH (a)-[r:REQUESTS]->()
                RETURN a.name AS name, a.unresolvedReason AS reason, count(r) AS edges""", Map.of());
        assertThat(rows).extracting(r -> r.get("name"), r -> r.get("edges"))
                .containsExactlyInAnyOrder(tuple("getApplicationDocument", 0L), tuple("getScoreHistory", 0L));
        assertThat(rows).allMatch(r -> r.get("reason") != null);
    }

    @Test
    void codeBlocksAndCallEdgeProperties() {
        List<Map<String, Object>> rows = store.query("""
                MATCH (m:Method)-[c:INVOKES]->(f:FeignMethod)
                WHERE m.fqn CONTAINS 'ApplicationServiceImpl#createApplication'
                RETURN f.name AS target, c.order AS order, c.conditional AS conditional, m.code AS code,
                       m.file AS file, m.startLine AS start
                ORDER BY c.order""", Map.of());
        // EXPECTED.md'deki sıra accessor'lar hariç görelidir; kenardaki order mutlak çalışma sırasıdır.
        assertThat(rows).extracting(r -> r.get("target"), r -> r.get("conditional"))
                .containsExactly(tuple("checkLimit", false), tuple("createOffer", true));
        assertThat((Long) rows.get(0).get("order")).isLessThan((Long) rows.get(1).get("order"));
        assertThat((String) rows.get(0).get("code")).contains("createApplication");
        assertThat((String) rows.get(0).get("file")).endsWith("ApplicationServiceImpl.java");
    }

    @Test
    void rerunIsIdempotentAndKeepsRunHistory() {
        long before = count("MATCH (n:Node) WHERE NOT n:AnalysisRun RETURN count(n) AS c");
        long relsBefore = count("MATCH ()-[r]->() RETURN count(r) AS c");
        AnalysisService second = newService();
        AnalysisReport again = second.run();

        assertThat(count("MATCH (n:Node) WHERE NOT n:AnalysisRun RETURN count(n) AS c")).isEqualTo(before);
        assertThat(count("MATCH ()-[r]->() RETURN count(r) AS c")).isEqualTo(relsBefore);
        assertThat(count("MATCH (r:AnalysisRun {status: 'SUCCEEDED'}) RETURN count(r) AS c")).isGreaterThanOrEqualTo(2);
        assertThat(second.status().state()).isEqualTo(AnalysisStatus.State.SUCCEEDED);

        AnalysisReport fromGraph = newService().lastReport().orElseThrow();
        assertThat(fromGraph.runId()).isEqualTo(again.runId());
        assertThat(fromGraph.unresolvedLinks()).isEqualTo(again.unresolvedLinks());
        assertThat(fromGraph.nodeCounts()).isEqualTo(again.nodeCounts());
    }

    private List<String> tablesReachableFrom(String route) {
        return store.query("""
                MATCH (s:Screen {routePath: $route})-[:RENDERS*0..5]->(:Component)-[:USES_API_CALL]->(:ApiCall)
                      -[:REQUESTS]->(:Endpoint)-[:HANDLED_BY]->(:Method)
                      -[:CALLS|INVOKES|RESOLVES_TO|HANDLED_BY|USES_REPOSITORY*1..15]->(:RepositoryMethod)
                      -[:READS|WRITES]->(:Entity)-[:MAPPED_TO]->(t:Table)
                RETURN DISTINCT t.qualifiedName AS table""", Map.of("route", route))
                .stream().map(r -> (String) r.get("table")).toList();
    }

    private List<String> screensReaching(String table) {
        return store.query("""
                MATCH (t:Table {qualifiedName: $table})<-[:MAPPED_TO]-(:Entity)<-[:READS|WRITES]-(:RepositoryMethod)
                      <-[:CALLS|INVOKES|RESOLVES_TO|HANDLED_BY|USES_REPOSITORY*1..15]-(:Method)
                      <-[:HANDLED_BY]-(:Endpoint)<-[:REQUESTS]-(:ApiCall)<-[:USES_API_CALL]-(:Component)
                      <-[:RENDERS*0..5]-(s:Screen)
                RETURN DISTINCT s.routePath AS route""", Map.of("table", table))
                .stream().map(r -> (String) r.get("route")).toList();
    }

    private long count(String cypher) {
        return ((Number) store.query(cypher, Map.of()).get(0).get("c")).longValue();
    }
}

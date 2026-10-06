package me.emreihtiyar.codegraph.graph.neo4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.emreihtiyar.codegraph.extract.model.Resolution;
import me.emreihtiyar.codegraph.graph.GraphBatch;
import me.emreihtiyar.codegraph.graph.GraphNode;
import me.emreihtiyar.codegraph.graph.GraphPath;
import me.emreihtiyar.codegraph.graph.GraphRelationship;
import me.emreihtiyar.codegraph.graph.NodeIds;
import me.emreihtiyar.codegraph.graph.NodeLabel;
import me.emreihtiyar.codegraph.graph.NodeRecord;
import me.emreihtiyar.codegraph.graph.RelRecord;
import me.emreihtiyar.codegraph.graph.RelType;
import me.emreihtiyar.codegraph.graph.WriteResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddedNeo4jGraphStoreTest {

    @TempDir
    static Path home;

    private DatabaseManagementService dbms;
    private EmbeddedNeo4jGraphStore store;

    @BeforeAll
    void start() {
        dbms = EmbeddedNeo4jConfig.create(home);
        store = new EmbeddedNeo4jGraphStore(dbms.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME));
        store.ensureSchema();
    }

    @AfterAll
    void stop() {
        dbms.shutdown();
    }

    @BeforeEach
    void clean() {
        store.clearExcept(Set.of());
    }

    @Test
    void ensureSchemaIsIdempotent() {
        store.ensureSchema();
        List<Map<String, Object>> rows = store.query("SHOW CONSTRAINTS YIELD name RETURN name", Map.of());
        assertThat(rows).extracting(r -> r.get("name")).contains("node_id");
    }

    @Test
    void writesNodesAndRelationshipsAndReadsPaths() {
        NodeRecord caller = method("svc|a.B#run()", "run");
        NodeRecord callee = method("svc|a.B#helper(int)", "helper");
        RelRecord call = RelRecord.of(RelType.CALLS, caller.id(), callee.id(), Map.of(
                "order", 1, "line", 12, "conditional", true, "resolution", Resolution.RESOLVED));

        WriteResult result = store.write(new GraphBatch(List.of(caller, callee), List.of(call)));

        assertThat(result.nodesWritten()).isEqualTo(2);
        assertThat(result.relationshipsWritten()).isEqualTo(1);
        assertThat(result.danglingRelationships()).isEmpty();

        List<Map<String, Object>> rows = store.query(
                "MATCH p = (:Method {id: $id})-[:CALLS]->(:Method) RETURN p", Map.of("id", caller.id()));
        GraphPath path = (GraphPath) rows.get(0).get("p");
        assertThat(path.nodes()).extracting(GraphNode::id).containsExactly(caller.id(), callee.id());
        GraphNode first = path.nodes().get(0);
        assertThat(first.labels()).containsExactly("Method");
        assertThat(first.properties()).containsEntry("name", "run").containsEntry("key", "svc|a.B#run()");
        GraphRelationship rel = path.relationships().get(0);
        assertThat(rel.type()).isEqualTo("CALLS");
        assertThat(rel.startId()).isEqualTo(caller.id());
        assertThat(rel.properties())
                .containsEntry("order", 1L)
                .containsEntry("conditional", true)
                .containsEntry("resolution", "RESOLVED");
    }

    @Test
    void mergesNodesById() {
        NodeRecord first = NodeRecord.of(NodeLabel.TABLE, "leasing.credit", Map.of("name", "CREDIT"));
        NodeRecord second = NodeRecord.of(NodeLabel.TABLE, "leasing.credit", Map.of("schema", "LEASING"));

        store.write(new GraphBatch(List.of(first, second), List.of()));

        List<Map<String, Object>> rows = store.query("MATCH (t:Table) RETURN t", Map.of());
        assertThat(rows).hasSize(1);
        assertThat(((GraphNode) rows.get(0).get("t")).properties())
                .containsEntry("name", "CREDIT")
                .containsEntry("schema", "LEASING");
    }

    @Test
    void reportsDanglingRelationships() {
        NodeRecord a = method("svc|A#a()", "a");
        RelRecord dangling = RelRecord.of(RelType.CALLS, a.id(), "method_missing");

        WriteResult result = store.write(new GraphBatch(List.of(a), List.of(dangling)));

        assertThat(result.relationshipsWritten()).isZero();
        assertThat(result.danglingRelationships()).containsExactly(dangling);
    }

    @Test
    void writesInBatchesLargerThanChunkSize() {
        int count = EmbeddedNeo4jGraphStore.BATCH_SIZE + 10;
        List<NodeRecord> nodes = new ArrayList<>();
        List<RelRecord> rels = new ArrayList<>();
        NodeRecord root = method("svc|Root#r()", "r");
        nodes.add(root);
        for (int i = 0; i < count; i++) {
            NodeRecord m = method("svc|M#m" + i + "()", "m" + i);
            nodes.add(m);
            rels.add(RelRecord.of(RelType.CALLS, root.id(), m.id()));
        }

        WriteResult result = store.write(new GraphBatch(nodes, rels));

        assertThat(result.nodesWritten()).isEqualTo(count + 1);
        assertThat(result.relationshipsWritten()).isEqualTo(count);
        assertThat(result.danglingRelationships()).isEmpty();
        assertThat(store.clearExcept(Set.of())).isEqualTo(count + 1);
    }

    @Test
    void clearPreservesGivenLabels() {
        NodeRecord run = NodeRecord.of(NodeLabel.ANALYSIS_RUN, "run-1", Map.of("status", "DONE"));
        NodeRecord m = method("svc|A#a()", "a");
        store.write(new GraphBatch(List.of(run, m), List.of()));

        long deleted = store.clearExcept(Set.of(NodeLabel.ANALYSIS_RUN));

        assertThat(deleted).isEqualTo(1);
        assertThat(store.query("MATCH (n:Node) RETURN n.id AS id", Map.of()))
                .extracting(r -> r.get("id"))
                .containsExactly(run.id());
    }

    @Test
    void rejectsWriteQueriesWithoutSideEffects() {
        assertThatThrownBy(() -> store.query("CREATE (:Node {id: 'x'})", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salt-okunur");
        assertThatThrownBy(() -> store.query("MATCH (n) DETACH DELETE n", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.query("MATCH (n:Node {id: 'x'}) RETURN n", Map.of())).isEmpty();
    }

    @Test
    void nodeIdsAreDeterministicAndLabelScoped() {
        assertThat(NodeIds.of(NodeLabel.METHOD, "svc|A#a()")).isEqualTo(NodeIds.of(NodeLabel.METHOD, "svc|A#a()"));
        assertThat(NodeIds.of(NodeLabel.METHOD, "x")).isNotEqualTo(NodeIds.of(NodeLabel.CLASS, "x"));
        assertThat(NodeIds.of(NodeLabel.METHOD, "x")).matches("method_[0-9a-f]{16}");
    }

    private static NodeRecord method(String key, String name) {
        return NodeRecord.of(NodeLabel.METHOD, key, Map.of("name", name));
    }
}

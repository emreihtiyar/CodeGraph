package me.emreihtiyar.codegraph.graph.neo4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Array;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import me.emreihtiyar.codegraph.graph.GraphBatch;
import me.emreihtiyar.codegraph.graph.GraphNode;
import me.emreihtiyar.codegraph.graph.GraphPath;
import me.emreihtiyar.codegraph.graph.GraphRelationship;
import me.emreihtiyar.codegraph.graph.GraphSchema;
import me.emreihtiyar.codegraph.graph.GraphStore;
import me.emreihtiyar.codegraph.graph.NodeLabel;
import me.emreihtiyar.codegraph.graph.NodeRecord;
import me.emreihtiyar.codegraph.graph.RelRecord;
import me.emreihtiyar.codegraph.graph.RelType;
import me.emreihtiyar.codegraph.graph.WriteResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.QueryExecutionType;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

/** {@link GraphStore}'un Embedded Neo4j implementasyonu. Neo4j API'sine yalnızca bu paket bağımlıdır. */
public class EmbeddedNeo4jGraphStore implements GraphStore {

    static final int BATCH_SIZE = 5_000;

    private final GraphDatabaseService db;

    public EmbeddedNeo4jGraphStore(GraphDatabaseService db) {
        this.db = db;
    }

    @Override
    public void ensureSchema() {
        for (String statement : GraphSchema.statements()) {
            db.executeTransactionally(statement);
        }
        try (Transaction tx = db.beginTx()) {
            tx.schema().awaitIndexesOnline(5, TimeUnit.MINUTES);
        }
    }

    @Override
    public long clearExcept(Set<NodeLabel> preserved) {
        String exclusion = preserved.stream()
                .map(l -> "NOT n:`" + l.label() + "`")
                .collect(Collectors.joining(" AND "));
        String cypher = "MATCH (n:" + NodeLabel.BASE + ")" + (exclusion.isEmpty() ? "" : " WHERE " + exclusion)
                + " WITH n LIMIT $limit DETACH DELETE n RETURN count(*) AS deleted";
        long total = 0;
        while (true) {
            long deleted = db.executeTransactionally(
                    cypher, Map.of("limit", BATCH_SIZE), r -> ((Number) r.next().get("deleted")).longValue());
            total += deleted;
            if (deleted < BATCH_SIZE) {
                return total;
            }
        }
    }

    @Override
    public WriteResult write(GraphBatch batch) {
        int nodes = 0;
        Map<NodeLabel, List<NodeRecord>> byLabel = new LinkedHashMap<>();
        batch.nodes().forEach(n -> byLabel.computeIfAbsent(n.label(), k -> new ArrayList<>()).add(n));
        for (var entry : byLabel.entrySet()) {
            String cypher = "UNWIND $rows AS row MERGE (n:" + NodeLabel.BASE + " {id: row.id}) SET n:`"
                    + entry.getKey().label() + "`, n += row.props, n.key = row.key";
            for (List<NodeRecord> chunk : chunks(entry.getValue())) {
                List<Map<String, Object>> rows = chunk.stream()
                        .map(n -> Map.<String, Object>of("id", n.id(), "key", n.key(), "props", n.properties()))
                        .toList();
                db.executeTransactionally(cypher, Map.of("rows", rows));
                nodes += chunk.size();
            }
        }

        int relationships = 0;
        List<RelRecord> dangling = new ArrayList<>();
        Map<RelType, List<RelRecord>> byType = new LinkedHashMap<>();
        batch.relationships().forEach(r -> byType.computeIfAbsent(r.type(), k -> new ArrayList<>()).add(r));
        for (var entry : byType.entrySet()) {
            String cypher = "UNWIND $rows AS row MATCH (a:" + NodeLabel.BASE + " {id: row.from}) MATCH (b:"
                    + NodeLabel.BASE + " {id: row.to}) CREATE (a)-[r:`" + entry.getKey().name()
                    + "`]->(b) SET r = row.props RETURN collect(row.i) AS written";
            for (List<RelRecord> chunk : chunks(entry.getValue())) {
                List<Map<String, Object>> rows = new ArrayList<>(chunk.size());
                for (int i = 0; i < chunk.size(); i++) {
                    RelRecord r = chunk.get(i);
                    rows.add(Map.of("i", i, "from", r.fromId(), "to", r.toId(), "props", r.properties()));
                }
                Set<Long> written = db.executeTransactionally(cypher, Map.of("rows", rows), r -> {
                    Set<Long> ids = new HashSet<>();
                    ((List<?>) r.next().get("written")).forEach(v -> ids.add(((Number) v).longValue()));
                    return ids;
                });
                relationships += written.size();
                for (int i = 0; i < chunk.size(); i++) {
                    if (!written.contains((long) i)) {
                        dangling.add(chunk.get(i));
                    }
                }
            }
        }
        return new WriteResult(nodes, relationships, dangling);
    }

    @Override
    public List<Map<String, Object>> query(String cypher, Map<String, Object> parameters) {
        // Transaction hiçbir zaman commit edilmez: kontrol bir şekilde aşılsa bile değişiklik kalıcı olmaz.
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(cypher, parameters == null ? Map.of() : parameters);
            if (result.getQueryExecutionType().queryType() != QueryExecutionType.QueryType.READ_ONLY) {
                throw new IllegalArgumentException("Yalnızca salt-okunur sorgular çalıştırılabilir");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                Map<String, Object> row = new LinkedHashMap<>();
                result.next().forEach((k, v) -> row.put(k, convert(v)));
                rows.add(row);
            }
            tx.rollback();
            return rows;
        }
    }

    private static Object convert(Object value) {
        if (value instanceof Node node) {
            return toNode(node);
        }
        if (value instanceof Relationship rel) {
            return toRelationship(rel);
        }
        if (value instanceof Path path) {
            List<GraphNode> nodes = new ArrayList<>();
            path.nodes().forEach(n -> nodes.add(toNode(n)));
            List<GraphRelationship> rels = new ArrayList<>();
            path.relationships().forEach(r -> rels.add(toRelationship(r)));
            return new GraphPath(nodes, rels);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(EmbeddedNeo4jGraphStore::convert).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((k, v) -> converted.put(String.valueOf(k), convert(v)));
            return converted;
        }
        return scalar(value);
    }

    /**
     * Sürücüden bağımsız tipler: Neo4j sunucu sürücüsü tamsayıları her zaman {@code Long}, ondalıkları {@code Double}
     * döndürür; Embedded API ise yazıldığı tipi korur. Diziler listeye çevrilir.
     */
    private static Object scalar(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(scalar(Array.get(value, i)));
            }
            return list;
        }
        return value;
    }

    private static Map<String, Object> properties(Map<String, Object> raw) {
        Map<String, Object> props = new HashMap<>(raw.size());
        raw.forEach((k, v) -> props.put(k, scalar(v)));
        return props;
    }

    private static GraphNode toNode(Node node) {
        Set<String> labels = new LinkedHashSet<>();
        for (Label label : node.getLabels()) {
            if (!label.name().equals(NodeLabel.BASE)) {
                labels.add(label.name());
            }
        }
        return new GraphNode(
                (String) node.getProperty("id", node.getElementId()), labels, properties(node.getAllProperties()));
    }

    private static GraphRelationship toRelationship(Relationship rel) {
        return new GraphRelationship(
                rel.getType().name(),
                (String) rel.getStartNode().getProperty("id", rel.getStartNode().getElementId()),
                (String) rel.getEndNode().getProperty("id", rel.getEndNode().getElementId()),
                properties(rel.getAllProperties()));
    }

    private static <T> List<List<T>> chunks(List<T> items) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += BATCH_SIZE) {
            chunks.add(items.subList(i, Math.min(items.size(), i + BATCH_SIZE)));
        }
        return chunks;
    }
}

package me.emreihtiyar.codegraph.query;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.emreihtiyar.codegraph.graph.GraphNode;
import me.emreihtiyar.codegraph.graph.GraphRelationship;
import me.emreihtiyar.codegraph.graph.GraphStore;

/**
 * Seviye seviye genişleyen graf gezintisi: her seviye tek bir Cypher sorgusudur. Değişken uzunluklu Cypher
 * kalıplarının aksine yol sayısı patlamaz ve düğüm sınırı her seviyede uygulanabilir.
 */
final class Traversal {

    /** İleri iz: ekran → component → ApiCall → endpoint → metotlar → Feign → … → tablo. */
    static final List<String> TRACE_TYPES = List.of("HAS_SCREEN", "EXPOSES", "RENDERS", "USES_API_CALL", "REQUESTS",
            "HANDLED_BY", "CALLS", "INVOKES", "RESOLVES_TO", "USES_REPOSITORY", "READS", "WRITES", "MAPPED_TO");

    /**
     * @param distance başlangıçtan kenar sayısı olarak uzaklık
     */
    record Result(Map<String, GraphNode> nodes, List<GraphRelationship> relationships, Map<String, Integer> distance,
            boolean truncated) {}

    private final GraphStore store;

    Traversal(GraphStore store) {
        this.store = store;
    }

    /**
     * @param forward          kenar yönünde mi (ileri iz) yoksa tersine mi (geri iz, etki analizi) gidilecek
     * @param includeAccessors false ise {@code isAccessor} metotlara girilmez
     */
    Result expand(GraphNode start, boolean forward, Collection<String> types, int maxDepth, int maxNodes,
            boolean includeAccessors) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Map<String, Integer> distance = new HashMap<>();
        List<GraphRelationship> rels = new ArrayList<>();
        nodes.put(start.id(), start);
        distance.put(start.id(), 0);
        Set<String> frontier = Set.of(start.id());
        boolean truncated = false;
        String pattern = String.join("|", types);
        String cypher = forward
                ? "MATCH (n:Node)-[r:" + pattern + "]->(m:Node) WHERE n.id IN $ids RETURN r, m"
                : "MATCH (m:Node)-[r:" + pattern + "]->(n:Node) WHERE n.id IN $ids RETURN r, m";
        for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
            Set<String> next = new HashSet<>();
            for (Map<String, Object> row : store.query(cypher, Map.of("ids", List.copyOf(frontier)))) {
                GraphRelationship r = (GraphRelationship) row.get("r");
                GraphNode m = (GraphNode) row.get("m");
                if (!includeAccessors && isAccessor(m)) {
                    continue;
                }
                if (!nodes.containsKey(m.id())) {
                    if (nodes.size() >= maxNodes) {
                        truncated = true;
                        continue;
                    }
                    nodes.put(m.id(), m);
                    distance.put(m.id(), depth);
                    next.add(m.id());
                }
                rels.add(r);
            }
            frontier = next;
            if (depth == maxDepth && !frontier.isEmpty() && hasMore(cypher, frontier)) {
                truncated = true;
            }
        }
        return new Result(nodes, rels, distance, truncated);
    }

    private boolean hasMore(String cypher, Set<String> frontier) {
        return !store.query(cypher + " LIMIT 1", Map.of("ids", List.copyOf(frontier))).isEmpty();
    }

    /**
     * Yalnızca başlangıçtan bir "anlamlı" düğüme giden yollar üzerindeki düğümleri tutar (ileri izde ara metot,
     * API çağırmayan component gibi dalları atar).
     */
    static Result prune(Result result, String startId, Set<String> relevantLabels) {
        Map<String, List<String>> incoming = new HashMap<>();
        for (GraphRelationship r : result.relationships()) {
            incoming.computeIfAbsent(r.endId(), k -> new ArrayList<>()).add(r.startId());
        }
        Set<String> keep = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (GraphNode n : result.nodes().values()) {
            if (n.labels().stream().anyMatch(relevantLabels::contains)) {
                keep.add(n.id());
                queue.add(n.id());
            }
        }
        while (!queue.isEmpty()) {
            for (String from : incoming.getOrDefault(queue.poll(), List.of())) {
                if (keep.add(from)) {
                    queue.add(from);
                }
            }
        }
        keep.add(startId);
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        result.nodes().forEach((id, n) -> {
            if (keep.contains(id)) {
                nodes.put(id, n);
            }
        });
        List<GraphRelationship> rels = result.relationships().stream()
                .filter(r -> keep.contains(r.startId()) && keep.contains(r.endId()))
                .toList();
        return new Result(nodes, rels, result.distance(), result.truncated());
    }

    /**
     * Ekranların component ağacını gizler: ekrandan RENDERS ile ulaşılan component'ler çıkarılır, kullandıkları
     * ApiCall'lar ekrana doğrudan {@code USES_API_CALL} kenarıyla bağlanır. Kenar özellikleri isteğin çıktığı yeri
     * taşır: {@code component}, {@code componentId}, {@code line}, {@code via}, {@code hook}. Hiçbir ekrandan
     * ulaşılamayan component'ler (ör. geri izde route'u olmayan) olduğu gibi kalır.
     */
    static Result collapseScreens(Result result) {
        Map<String, List<GraphRelationship>> renders = new HashMap<>();
        Map<String, List<GraphRelationship>> uses = new HashMap<>();
        for (GraphRelationship r : result.relationships()) {
            if (r.type().equals("RENDERS")) {
                renders.computeIfAbsent(r.startId(), k -> new ArrayList<>()).add(r);
            } else if (r.type().equals("USES_API_CALL")) {
                uses.computeIfAbsent(r.startId(), k -> new ArrayList<>()).add(r);
            }
        }
        Set<String> hidden = new HashSet<>();
        List<GraphRelationship> direct = new ArrayList<>();
        for (GraphNode screen : result.nodes().values()) {
            if (!screen.labels().contains("Screen")) {
                continue;
            }
            Set<String> visited = new HashSet<>(Set.of(screen.id()));
            Set<String> apiCalls = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(List.of(screen.id()));
            while (!queue.isEmpty()) {
                String id = queue.poll();
                for (GraphRelationship u : uses.getOrDefault(id, List.of())) {
                    if (apiCalls.add(u.endId())) {
                        Map<String, Object> props = new LinkedHashMap<>(u.properties());
                        props.put("order", apiCalls.size());
                        props.put("component", NodeSummary.of(result.nodes().get(id)).name());
                        props.put("componentId", id);
                        direct.add(new GraphRelationship("USES_API_CALL", screen.id(), u.endId(), props));
                    }
                }
                for (GraphRelationship r : renders.getOrDefault(id, List.of())) {
                    if (visited.add(r.endId())) {
                        hidden.add(r.endId());
                        queue.add(r.endId());
                    }
                }
            }
        }
        if (hidden.isEmpty()) {
            return result;
        }
        Map<String, GraphNode> nodes = new LinkedHashMap<>(result.nodes());
        nodes.keySet().removeAll(hidden);
        List<GraphRelationship> rels = new ArrayList<>(result.relationships().stream()
                .filter(r -> !hidden.contains(r.startId()) && !hidden.contains(r.endId()))
                .toList());
        rels.addAll(direct);
        return new Result(nodes, rels, result.distance(), result.truncated());
    }

    static boolean isAccessor(GraphNode n) {
        return n.labels().contains("Method") && Boolean.TRUE.equals(n.properties().get("isAccessor"));
    }
}

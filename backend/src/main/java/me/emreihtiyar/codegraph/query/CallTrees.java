package me.emreihtiyar.codegraph.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.emreihtiyar.codegraph.graph.GraphNode;
import me.emreihtiyar.codegraph.graph.GraphRelationship;
import me.emreihtiyar.codegraph.graph.GraphStore;

/** Metot çağrı ağacı (FR-22, FR-23) ve endpoint orkestrasyonu (Hikaye 2). */
final class CallTrees {

    static final int MAX_TREE_NODES = 500;
    static final int MAX_STEPS = 1000;

    private static final String CHILDREN = """
            MATCH (n:Node)-[r:CALLS|INVOKES|USES_REPOSITORY|RESOLVES_TO|HANDLED_BY]->(m:Node)
            WHERE n.id IN $ids AND (n:Method OR n:FeignMethod OR n:Endpoint)
            RETURN n.id AS parent, r, m""";
    private static final String TABLES = """
            MATCH (r:RepositoryMethod)-[a:READS|WRITES]->(:Entity)-[:MAPPED_TO]->(t:Table)
            WHERE r.id IN $ids
            RETURN r.id AS id, t.qualifiedName AS table""";
    private static final String FEIGN_TARGETS = """
            MATCH (f:FeignMethod)-[:RESOLVES_TO]->(e:Endpoint)
            WHERE f.id IN $ids
            RETURN f.id AS id, e""";

    private record Child(GraphRelationship rel, GraphNode node) {}

    private final GraphStore store;

    CallTrees(GraphStore store) {
        this.store = store;
    }

    // ---- çağrı ağacı ----

    CallTreeNode tree(GraphNode root, CallTreeOptions options) {
        Map<String, List<Child>> children = new HashMap<>();
        Map<String, List<String>> tables = new HashMap<>();
        Set<String> loaded = new HashSet<>();
        // Sınırdaki düğümlerin "açılabilir mi" bilgisi için bir seviye fazla yüklenir.
        Set<String> frontier = Set.of(root.id());
        int budget = MAX_TREE_NODES;
        for (int level = 0; level <= options.depth() && !frontier.isEmpty(); level++) {
            Map<String, List<Child>> batch = children(frontier, options);
            loaded.addAll(frontier);
            children.putAll(batch);
            Set<String> repositoryIds = new HashSet<>();
            Set<String> next = new HashSet<>();
            for (List<Child> list : batch.values()) {
                for (Child c : list) {
                    if (c.node().labels().contains("RepositoryMethod")) {
                        repositoryIds.add(c.node().id());
                    } else if (!loaded.contains(c.node().id())) {
                        next.add(c.node().id());
                    }
                }
            }
            if (!repositoryIds.isEmpty()) {
                tables.putAll(tables(repositoryIds));
            }
            budget -= next.size();
            frontier = budget > 0 ? next : Set.of();
        }
        if (root.labels().contains("RepositoryMethod")) {
            tables.putAll(tables(Set.of(root.id())));
        }
        List<String> ancestors = new ArrayList<>(options.ancestors());
        return build(root, null, ancestors, 0, options.depth(), children, tables, new int[] {MAX_TREE_NODES});
    }

    private CallTreeNode build(GraphNode node, GraphRelationship via, List<String> ancestors, int level, int depth,
            Map<String, List<Child>> children, Map<String, List<String>> tables, int[] budget) {
        String kind = kind(node);
        boolean recursive = ancestors.contains(node.id());
        List<Child> kids = children.getOrDefault(node.id(), List.of());
        boolean hasChildren = !recursive && !kids.isEmpty();
        List<CallTreeNode> built = null;
        if (hasChildren && level < depth && budget[0] > 0 && children.containsKey(node.id())) {
            built = new ArrayList<>();
            ancestors.add(node.id());
            for (Child c : kids) {
                budget[0]--;
                built.add(build(c.node(), c.rel(), ancestors, level + 1, depth, children, tables, budget));
            }
            ancestors.remove(ancestors.size() - 1);
        }
        Map<String, Object> p = node.properties();
        return new CallTreeNode(node.id(), kind, NodeSummary.displayName(NodeSummary.label(node), p),
                (String) p.get("service"), (String) p.get("visibility"), Boolean.TRUE.equals(p.get("isStatic")),
                (String) p.get("file"), intOrNull(p.get("startLine")), intOrNull(p.get("endLine")),
                via == null ? null : callProps(via), recursive, hasChildren, built, details(node, kind, tables));
    }

    private Map<String, List<Child>> children(Collection<String> ids, CallTreeOptions options) {
        Map<String, List<Child>> result = new HashMap<>();
        for (Map<String, Object> row : store.query(CHILDREN, Map.of("ids", List.copyOf(ids)))) {
            GraphRelationship r = (GraphRelationship) row.get("r");
            GraphNode m = (GraphNode) row.get("m");
            if (r.type().equals("RESOLVES_TO") && !options.crossService()) {
                continue;
            }
            if (m.labels().contains("Method") && !visible(m, options)) {
                continue;
            }
            result.computeIfAbsent((String) row.get("parent"), k -> new ArrayList<>()).add(new Child(r, m));
        }
        result.values().forEach(list -> list.sort(CHILD_ORDER));
        return result;
    }

    private static boolean visible(GraphNode method, CallTreeOptions options) {
        Map<String, Object> p = method.properties();
        if (!options.includeAccessors() && Boolean.TRUE.equals(p.get("isAccessor"))) {
            return false;
        }
        String className = (String) p.getOrDefault("className", "");
        for (String prefix : options.hidden()) {
            if (className.equals(prefix) || className.startsWith(prefix + ".") || className.endsWith("." + prefix)) {
                return false;
            }
        }
        return !options.onlyExternal()
                || Boolean.TRUE.equals(p.get("reachesExternal")) || Boolean.TRUE.equals(p.get("reachesDb"));
    }

    private static final Comparator<Child> CHILD_ORDER = Comparator
            .comparingLong((Child c) -> longOr(c.rel().properties().get("order"), Long.MAX_VALUE))
            .thenComparingLong(c -> longOr(c.rel().properties().get("line"), Long.MAX_VALUE))
            .thenComparing(c -> NodeSummary.displayName(NodeSummary.label(c.node()), c.node().properties()));

    private Map<String, List<String>> tables(Set<String> repositoryIds) {
        Map<String, List<String>> result = new HashMap<>();
        for (Map<String, Object> row : store.query(TABLES, Map.of("ids", List.copyOf(repositoryIds)))) {
            List<String> list = result.computeIfAbsent((String) row.get("id"), k -> new ArrayList<>());
            if (!list.contains((String) row.get("table"))) {
                list.add((String) row.get("table"));
            }
        }
        return result;
    }

    private static Map<String, Object> details(GraphNode node, String kind, Map<String, List<String>> tables) {
        Map<String, Object> p = node.properties();
        Map<String, Object> d = new LinkedHashMap<>();
        switch (kind) {
            case "FEIGN_METHOD" -> {
                putIfPresent(d, "targetService", p.get("targetService"));
                putIfPresent(d, "httpMethod", p.get("httpMethod"));
                putIfPresent(d, "path", p.get("path"));
                putIfPresent(d, "resolution", p.get("resolution"));
                putIfPresent(d, "unresolvedReason", p.get("unresolvedReason"));
            }
            case "REPOSITORY_METHOD" -> {
                putIfPresent(d, "accessType", p.get("accessType"));
                putIfPresent(d, "query", p.get("query"));
                d.put("tables", tables.getOrDefault(node.id(), List.of()));
            }
            case "ENDPOINT" -> {
                putIfPresent(d, "httpMethod", p.get("httpMethod"));
                putIfPresent(d, "path", p.get("path"));
            }
            default -> {
                putIfPresent(d, "signature", p.get("signature"));
                putIfPresent(d, "isAbstract", p.get("isAbstract"));
                putIfPresent(d, "unresolvedCalls", p.get("unresolvedCalls"));
            }
        }
        return d;
    }

    // ---- orkestrasyon ----

    /** @param maxDepth handler'dan itibaren en fazla kaç metot derinliğine inilir */
    Orchestration orchestration(GraphNode endpoint, GraphNode handler, int maxDepth, boolean includeAccessors) {
        CallTreeOptions options = new CallTreeOptions(1, includeAccessors, false, false, List.of(), List.of());
        Map<String, List<Child>> children = new HashMap<>();
        Set<String> frontier = Set.of(handler.id());
        Set<String> loaded = new HashSet<>();
        for (int level = 0; level < maxDepth && !frontier.isEmpty(); level++) {
            Map<String, List<Child>> batch = children(frontier, options);
            loaded.addAll(frontier);
            children.putAll(batch);
            Set<String> next = new HashSet<>();
            batch.values().forEach(list -> list.stream()
                    .filter(c -> c.node().labels().contains("Method") && !loaded.contains(c.node().id()))
                    .forEach(c -> next.add(c.node().id())));
            frontier = next;
        }
        boolean truncated = !frontier.isEmpty();
        Set<String> feignIds = new HashSet<>();
        Set<String> repositoryIds = new HashSet<>();
        children.values().forEach(list -> list.forEach(c -> {
            if (c.node().labels().contains("FeignMethod")) {
                feignIds.add(c.node().id());
            } else if (c.node().labels().contains("RepositoryMethod")) {
                repositoryIds.add(c.node().id());
            }
        }));
        Map<String, List<String>> tables = repositoryIds.isEmpty() ? Map.of() : tables(repositoryIds);
        Map<String, List<String>> endpoints = new HashMap<>();
        if (!feignIds.isEmpty()) {
            for (Map<String, Object> row : store.query(FEIGN_TARGETS, Map.of("ids", List.copyOf(feignIds)))) {
                GraphNode e = (GraphNode) row.get("e");
                endpoints.computeIfAbsent((String) row.get("id"), k -> new ArrayList<>())
                        .add(e.properties().get("service") + " " + e.properties().get("httpMethod") + " "
                                + e.properties().get("path"));
            }
        }
        List<Orchestration.Step> steps = new ArrayList<>();
        walk(handler, new ArrayList<>(), new ArrayList<>(List.of(handler.id())), false, false, false, children,
                tables, endpoints, steps);
        if (steps.size() > MAX_STEPS) {
            return new Orchestration(NodeSummary.of(endpoint), NodeSummary.of(handler), steps.subList(0, MAX_STEPS),
                    true);
        }
        return new Orchestration(NodeSummary.of(endpoint), NodeSummary.of(handler), steps, truncated);
    }

    private void walk(GraphNode method, List<String> via, List<String> stack, boolean conditional,
            boolean inLoop, boolean async, Map<String, List<Child>> children, Map<String, List<String>> tables,
            Map<String, List<String>> endpoints, List<Orchestration.Step> steps) {
        List<Child> kids = children.getOrDefault(method.id(), List.of());
        for (int i = 0; i < kids.size() && steps.size() <= MAX_STEPS; i++) {
            Child c = kids.get(i);
            Map<String, Object> r = c.rel().properties();
            boolean cond = conditional || Boolean.TRUE.equals(r.get("conditional"));
            boolean loop = inLoop || Boolean.TRUE.equals(r.get("inLoop"));
            boolean asy = async || Boolean.TRUE.equals(r.get("async"));
            GraphNode n = c.node();
            Map<String, Object> p = n.properties();
            String name = NodeSummary.displayName(NodeSummary.label(n), p);
            int line = (int) longOr(r.get("line"), 0);
            if (n.labels().contains("FeignMethod")) {
                steps.add(new Orchestration.Step(steps.size() + 1, "FEIGN", n.id(), name, method.id(), line, List.copyOf(via),
                        cond, loop, asy, (String) p.get("resolution"), (String) p.get("targetService"),
                        endpoints.getOrDefault(n.id(), List.of()), null));
            } else if (n.labels().contains("RepositoryMethod")) {
                steps.add(new Orchestration.Step(steps.size() + 1, "REPOSITORY", n.id(), name, method.id(), line,
                        List.copyOf(via), cond, loop, asy, null, (String) p.get("service"),
                        tables.getOrDefault(n.id(), List.of()), (String) p.get("accessType")));
            } else if (n.labels().contains("Method") && !stack.contains(n.id())) {
                via.add(name);
                stack.add(n.id());
                walk(n, via, stack, cond, loop, asy, children, tables, endpoints, steps);
                stack.remove(stack.size() - 1);
                via.remove(via.size() - 1);
            }
        }
    }

    // ---- yardımcılar ----

    static String kind(GraphNode node) {
        if (node.labels().contains("FeignMethod")) {
            return "FEIGN_METHOD";
        }
        if (node.labels().contains("RepositoryMethod")) {
            return "REPOSITORY_METHOD";
        }
        if (node.labels().contains("Endpoint")) {
            return "ENDPOINT";
        }
        return "METHOD";
    }

    private static Map<String, Object> callProps(GraphRelationship r) {
        Map<String, Object> p = new LinkedHashMap<>(r.properties());
        p.put("type", r.type());
        return p;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static long longOr(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}

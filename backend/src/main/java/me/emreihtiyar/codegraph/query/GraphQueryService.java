package me.emreihtiyar.codegraph.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.emreihtiyar.codegraph.analysis.AnalysisReport;
import me.emreihtiyar.codegraph.analysis.AnalysisService;
import me.emreihtiyar.codegraph.analysis.AnalysisStatus;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.extract.PathNormalizer;
import me.emreihtiyar.codegraph.graph.GraphNode;
import me.emreihtiyar.codegraph.graph.GraphRelationship;
import me.emreihtiyar.codegraph.graph.GraphStore;
import org.springframework.stereotype.Service;

/**
 * Tek sorgu katmanı (PRD §8.1): REST controller'ları ve MCP araçları yalnızca bu sınıfı çağırır, iş mantığı bir kez
 * yazılır. Tüm sorgular salt-okunurdur.
 *
 * <p>Düğüm referansları ({@code ref}) id ya da doğal ad olabilir: ekran route'u ({@code /credit-application}), tablo
 * adı ({@code offers}, {@code LEASING.CREDIT}), endpoint ({@code GET /applications/{id}}, başında servis adıyla da),
 * servis adı, component/ApiCall/entity/sınıf adı ya da {@code Sinif.metot}. Ad birden çok düğüme uyuyorsa adaylar
 * hata mesajında listelenir.
 */
@Service
public class GraphQueryService {

    public static final int NEIGHBOR_LIMIT = 200;
    public static final int SEARCH_LIMIT = 200;

    private static final Pattern NODE_ID = Pattern.compile("[a-z]+_[0-9a-f]{16}");
    private static final Pattern ENDPOINT_REF =
            Pattern.compile("(?:(\\S+)\\s+)?(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|ANY)\\s+(/\\S*)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_REF = Pattern.compile("([A-Za-z_$][\\w$]*)[.#]([A-Za-z_$][\\w$]*)");
    private static final List<String> LAYER_ORDER =
            List.of("FRONTEND", "BFF", "API", "ADAPTER", "LIBRARY");
    private static final Set<String> FORWARD_RELEVANT =
            Set.of("Table", "Entity", "RepositoryMethod", "FeignMethod", "Endpoint", "ApiCall");
    private static final List<String> CALL_TREE_ROOTS =
            List.of("Method", "Endpoint", "FeignMethod", "RepositoryMethod", "Screen", "ApiCall");
    private static final List<String> SEARCH_FIELDS =
            List.of("name", "fqn", "path", "normalizedPath", "routePath", "qualifiedName", "urlTemplate");

    private final GraphStore store;
    private final AnalysisService analysis;
    private final Traversal traversal;
    private final CallTrees callTrees;

    public GraphQueryService(GraphStore store, AnalysisService analysis) {
        this.store = store;
        this.analysis = analysis;
        this.traversal = new Traversal(store);
        this.callTrees = new CallTrees(store);
    }

    // ---- servisler ve ekranlar ----

    /** @param layer null ise tüm servisler; analizde olmayan (Feign hedefi) servislerin katmanı yoktur */
    public ServiceList listServices(Layer layer) {
        List<ServiceList.Service> services = new ArrayList<>();
        for (Map<String, Object> row : store.query("""
                MATCH (s:Service) WHERE $layer IS NULL OR s.layer = $layer
                OPTIONAL MATCH (s)-[:EXPOSES]->(e:Endpoint)
                WITH s, count(e) AS endpoints
                OPTIONAL MATCH (s)-[:HAS_SCREEN]->(x:Screen)
                WITH s, endpoints, count(x) AS screens
                OPTIONAL MATCH (:RepositoryMethod {service: s.name})-[:READS|WRITES]->(:Entity)-[:MAPPED_TO]->(t:Table)
                RETURN s, endpoints, screens, count(DISTINCT t) AS tables""",
                params("layer", layer == null ? null : layer.name()))) {
            services.add(service((GraphNode) row.get("s"), (Long) row.get("endpoints"), (Long) row.get("screens"),
                    (Long) row.get("tables")));
        }
        services.sort(Comparator.comparingInt((ServiceList.Service s) -> layerRank(s.layer()))
                .thenComparing(ServiceList.Service::name));
        List<String> names = services.stream().map(ServiceList.Service::name).toList();
        List<ServiceList.Dependency> deps = new ArrayList<>();
        for (Map<String, Object> row : store.query("""
                MATCH (a:Service)-[d:DEPENDS_ON]->(b:Service) WHERE a.name IN $names AND b.name IN $names
                RETURN a.name AS from, b.name AS to, d.callCount AS calls ORDER BY from, to""", Map.of("names", names))) {
            deps.add(new ServiceList.Dependency((String) row.get("from"), (String) row.get("to"), (Long) row.get("calls")));
        }
        return new ServiceList(services, deps);
    }

    public ServiceDependencies serviceDependencies(String name) {
        GraphNode node = store.query("MATCH (s:Service {name: $name}) RETURN s", Map.of("name", name)).stream()
                .findFirst().map(r -> (GraphNode) r.get("s"))
                .orElseThrow(() -> new NotFoundException("Servis bulunamadı: " + name));
        ServiceList.Service service = service(node, 0, 0, 0);
        List<ServiceDependencies.Dependency> outgoing = new ArrayList<>();
        for (Map<String, Object> row : store.query("""
                MATCH (:Service {name: $name})-[d:DEPENDS_ON]->(t:Service)
                RETURN t, d.callCount AS calls ORDER BY t.name""", Map.of("name", name))) {
            GraphNode t = (GraphNode) row.get("t");
            outgoing.add(dependency(t, (Long) row.get("calls"), links(name, (String) t.properties().get("name"))));
        }
        List<ServiceDependencies.Dependency> incoming = new ArrayList<>();
        for (Map<String, Object> row : store.query("""
                MATCH (t:Service)-[d:DEPENDS_ON]->(:Service {name: $name})
                RETURN t, d.callCount AS calls ORDER BY t.name""", Map.of("name", name))) {
            GraphNode t = (GraphNode) row.get("t");
            incoming.add(dependency(t, (Long) row.get("calls"), links((String) t.properties().get("name"), name)));
        }
        return new ServiceDependencies(service, outgoing, incoming);
    }

    private List<ServiceDependencies.Link> links(String from, String to) {
        List<ServiceDependencies.Link> links = new ArrayList<>();
        for (Map<String, Object> row : store.query("""
                MATCH (x:Node {targetService: $to})
                WHERE (x:ApiCall AND x.service = $from)
                   OR (x:FeignMethod AND (x.service = $from OR EXISTS { MATCH (:Method {service: $from})-[:INVOKES]->(x) }))
                OPTIONAL MATCH (x)-[:REQUESTS|RESOLVES_TO]->(e:Endpoint)
                RETURN x, e ORDER BY x.name""", Map.of("from", from, "to", to))) {
            GraphNode x = (GraphNode) row.get("x");
            GraphNode e = (GraphNode) row.get("e");
            Map<String, Object> p = x.properties();
            links.add(new ServiceDependencies.Link(x.id(), NodeSummary.displayName(NodeSummary.label(x), p),
                    NodeSummary.label(x), (String) p.get("service"), e == null ? null : e.id(),
                    e == null ? null : NodeSummary.displayName("Endpoint", e.properties()),
                    (String) p.get("resolution"), (String) p.get("unresolvedReason")));
        }
        return links;
    }

    private static ServiceDependencies.Dependency dependency(GraphNode t, long calls, List<ServiceDependencies.Link> links) {
        Map<String, Object> p = t.properties();
        return new ServiceDependencies.Dependency((String) p.get("name"), (String) p.get("layer"),
                Boolean.TRUE.equals(p.get("analyzed")), calls, links);
    }

    private static ServiceList.Service service(GraphNode s, long endpoints, long screens, long tables) {
        Map<String, Object> p = s.properties();
        return new ServiceList.Service(s.id(), (String) p.get("name"), (String) p.get("layer"), (String) p.get("repo"),
                (String) p.get("contextPath"), (String) p.get("gatewayPrefix"), Boolean.TRUE.equals(p.get("analyzed")),
                endpoints, screens, tables);
    }

    /** @param service null ise tüm frontend'lerin ekranları */
    public List<ScreenView> listScreens(String service) {
        List<ScreenView> screens = new ArrayList<>();
        for (Map<String, Object> row : store.query("""
                MATCH (sv:Service)-[:HAS_SCREEN]->(s:Screen) WHERE $service IS NULL OR sv.name = $service
                OPTIONAL MATCH (s)-[:RENDERS]->(c:Component)
                RETURN s, sv.name AS service, c.id AS component ORDER BY s.routePath""", params("service", service))) {
            GraphNode s = (GraphNode) row.get("s");
            screens.add(new ScreenView(s.id(), (String) s.properties().get("routePath"),
                    (String) s.properties().get("name"), (String) row.get("service"), (String) row.get("component")));
        }
        return screens;
    }

    /** @param service null ise tüm servislerin endpoint'leri */
    public List<EndpointView> listEndpoints(String service) {
        List<EndpointView> endpoints = new ArrayList<>();
        for (Map<String, Object> row : store.query("""
                MATCH (e:Endpoint) WHERE $service IS NULL OR e.service = $service
                OPTIONAL MATCH (e)-[:HANDLED_BY]->(m:Method)
                RETURN e, m ORDER BY e.service, e.path, e.httpMethod""", params("service", service))) {
            GraphNode e = (GraphNode) row.get("e");
            GraphNode m = (GraphNode) row.get("m");
            Map<String, Object> p = e.properties();
            endpoints.add(new EndpointView(e.id(), (String) p.get("service"), (String) p.get("httpMethod"),
                    (String) p.get("path"), m == null ? null : m.id(),
                    m == null ? null : NodeSummary.displayName("Method", m.properties())));
        }
        return endpoints;
    }

    // ---- izler ----

    /** Ekran, component, ApiCall, endpoint, metot ya da servisten tablolara ileri iz (FR-16). */
    public TraceResult traceForward(String ref, TraceOptions options) {
        GraphNode start = resolveNode(ref);
        Traversal.Result result = traversal.expand(start, true, Traversal.TRACE_TYPES, options.maxDepth(),
                options.maxNodes(), options.includeAccessors());
        if (options.prune()) {
            result = Traversal.prune(result, start.id(), FORWARD_RELEVANT);
        }
        return trace("FORWARD", start, result);
    }

    /** Tablo, entity, repository metodu, endpoint ya da metottan onu kullanan ekranlara geri iz (FR-17). */
    public TraceResult traceBackward(String ref, TraceOptions options) {
        GraphNode start = resolveNode(ref);
        Traversal.Result result = traversal.expand(start, false, Traversal.TRACE_TYPES, options.maxDepth(),
                options.maxNodes(), options.includeAccessors());
        return trace("BACKWARD", start, result);
    }

    /** Component'ten başlanmadıysa ekranların component ağacı gizlenir (bkz. {@link Traversal#collapseScreens}). */
    private TraceResult trace(String direction, GraphNode start, Traversal.Result result) {
        if (!start.labels().contains("Component")) {
            result = Traversal.collapseScreens(result);
        }
        List<NodeSummary> nodes = result.nodes().values().stream().map(NodeSummary::of).toList();
        List<EdgeView> edges = result.relationships().stream().map(EdgeView::of).toList();
        String note = result.truncated()
                ? "Sonuç sınıra ulaştı ve kesildi; maxDepth/maxNodes'u artırın ya da daha özel bir başlangıç seçin."
                : null;
        return new TraceResult(direction, NodeSummary.of(start), new Subgraph(nodes, edges, result.truncated(), note),
                summary(result));
    }

    private TraceResult.Summary summary(Traversal.Result result) {
        List<NodeSummary> screens = new ArrayList<>();
        List<NodeSummary> apiCalls = new ArrayList<>();
        List<NodeSummary> endpoints = new ArrayList<>();
        List<NodeSummary> unresolved = new ArrayList<>();
        Set<String> serviceNames = new LinkedHashSet<>();
        Map<String, GraphNode> tables = new LinkedHashMap<>();
        for (GraphNode n : result.nodes().values()) {
            String label = NodeSummary.label(n);
            Object service = n.properties().get("service");
            if (service != null) {
                serviceNames.add(service.toString());
            }
            switch (label) {
                case "Screen" -> screens.add(NodeSummary.of(n));
                case "Endpoint" -> endpoints.add(NodeSummary.of(n));
                case "Table" -> tables.put(n.id(), n);
                case "Service" -> serviceNames.add((String) n.properties().get("name"));
                case "ApiCall", "FeignMethod" -> {
                    if (label.equals("ApiCall")) {
                        apiCalls.add(NodeSummary.of(n));
                    }
                    if ("UNRESOLVED".equals(n.properties().get("resolution"))) {
                        unresolved.add(NodeSummary.of(n));
                    }
                }
                default -> { }
            }
        }
        // Tablo erişim tipi: alt graftaki RepositoryMethod -[READS|WRITES]-> Entity -[MAPPED_TO]-> Table
        Map<String, Set<String>> entityAccess = new HashMap<>();
        Map<String, Set<String>> tableEntities = new HashMap<>();
        for (GraphRelationship r : result.relationships()) {
            if (r.type().equals("READS") || r.type().equals("WRITES")) {
                entityAccess.computeIfAbsent(r.endId(), k -> new TreeSet<>()).add(r.type().equals("READS") ? "READ" : "WRITE");
            } else if (r.type().equals("MAPPED_TO")) {
                tableEntities.computeIfAbsent(r.endId(), k -> new HashSet<>()).add(r.startId());
            }
        }
        List<TraceResult.TableAccess> tableAccess = new ArrayList<>();
        tables.forEach((id, t) -> {
            Set<String> access = new TreeSet<>();
            tableEntities.getOrDefault(id, Set.of()).forEach(e -> access.addAll(entityAccess.getOrDefault(e, Set.of())));
            tableAccess.add(new TraceResult.TableAccess(id, (String) t.properties().get("qualifiedName"), List.copyOf(access)));
        });
        tableAccess.sort(Comparator.comparing(TraceResult.TableAccess::name));
        screens.sort(Comparator.comparing(NodeSummary::name));
        apiCalls.sort(Comparator.comparing((NodeSummary a) -> String.valueOf(a.properties().get("urlTemplate")))
                .thenComparing(NodeSummary::name));
        endpoints.sort(Comparator.comparing((NodeSummary e) -> String.valueOf(e.service())).thenComparing(NodeSummary::name));
        return new TraceResult.Summary(screens, apiCalls, servicesByLayer(serviceNames), endpoints, tableAccess,
                unresolved);
    }

    private List<TraceResult.LayerServices> servicesByLayer(Set<String> names) {
        Map<String, List<String>> byLayer = new TreeMap<>(Comparator.comparingInt(GraphQueryService::layerRank));
        for (Map<String, Object> row : store.query(
                "MATCH (s:Service) WHERE s.name IN $names RETURN s.name AS name, coalesce(s.layer, 'EXTERNAL') AS layer "
                        + "ORDER BY name", Map.of("names", List.copyOf(names)))) {
            byLayer.computeIfAbsent((String) row.get("layer"), k -> new ArrayList<>()).add((String) row.get("name"));
        }
        return byLayer.entrySet().stream().map(e -> new TraceResult.LayerServices(e.getKey(), e.getValue())).toList();
    }

    // ---- orkestrasyon, tablo kullanımı, çağrı ağacı ----

    /** Bir endpoint'in sıralı Feign çağrıları ve DB erişimleri (Hikaye 2). */
    public Orchestration endpointOrchestration(String ref, int maxDepth, boolean includeAccessors) {
        if (maxDepth < 1 || maxDepth > 50) {
            throw new IllegalArgumentException("maxDepth 1 ile 50 arasında olmalı");
        }
        GraphNode endpoint = resolveNode(ref);
        if (!endpoint.labels().contains("Endpoint")) {
            throw new IllegalArgumentException("Bir endpoint bekleniyordu, bulunan: " + NodeSummary.label(endpoint) + " "
                    + NodeSummary.of(endpoint).name());
        }
        GraphNode handler = store.query("MATCH (:Endpoint {id: $id})-[:HANDLED_BY]->(m:Method) RETURN m",
                        Map.of("id", endpoint.id())).stream()
                .findFirst().map(r -> (GraphNode) r.get("m"))
                .orElseThrow(() -> new NotFoundException("Endpoint'in handler metodu yok: " + NodeSummary.of(endpoint).name()));
        return callTrees.orchestration(endpoint, handler, maxDepth, includeAccessors);
    }

    /** Tabloya erişen repository metotları ve onları çağıran metotlar (READ/WRITE). */
    public TableUsages tableUsages(String table) {
        List<GraphNode> tables = store.query("""
                MATCH (t:Table) WHERE toLower(t.name) = $name OR toLower(t.qualifiedName) = $name OR t.id = $raw
                RETURN t ORDER BY t.qualifiedName""", Map.of("name", table.toLowerCase(Locale.ROOT), "raw", table))
                .stream().map(r -> (GraphNode) r.get("t")).toList();
        if (tables.isEmpty()) {
            throw new NotFoundException("Tablo bulunamadı: " + table);
        }
        List<String> ids = tables.stream().map(GraphNode::id).toList();
        List<NodeSummary> entities = store.query("""
                MATCH (t:Table)<-[:MAPPED_TO]-(e:Entity) WHERE t.id IN $ids RETURN e ORDER BY e.fqn""", Map.of("ids", ids))
                .stream().map(r -> NodeSummary.of((GraphNode) r.get("e"))).toList();
        List<TableUsages.Usage> usages = new ArrayList<>();
        for (Map<String, Object> row : store.query("""
                MATCH (t:Table)<-[:MAPPED_TO]-(e:Entity)<-[a:READS|WRITES]-(r:RepositoryMethod) WHERE t.id IN $ids
                OPTIONAL MATCH (m:Method)-[u:USES_REPOSITORY]->(r)
                RETURN t.qualifiedName AS table, e.name AS entity, type(a) AS access, r, m, u.line AS line
                ORDER BY table, access, r.fqn, m.fqn""", Map.of("ids", ids))) {
            GraphNode r = (GraphNode) row.get("r");
            GraphNode m = (GraphNode) row.get("m");
            usages.add(new TableUsages.Usage((String) row.get("table"), (String) row.get("entity"), r.id(),
                    NodeSummary.displayName("RepositoryMethod", r.properties()),
                    "READS".equals(row.get("access")) ? "READ" : "WRITE", (String) r.properties().get("query"),
                    m == null ? null : m.id(), m == null ? null : NodeSummary.displayName("Method", m.properties()),
                    m == null ? (String) r.properties().get("service") : (String) m.properties().get("service"),
                    row.get("line") == null ? null : ((Number) row.get("line")).intValue()));
        }
        return new TableUsages(tables.stream().map(NodeSummary::of).toList(), entities, usages);
    }

    /** Metot, endpoint, Feign/repository metodu, ekran ya da ApiCall'dan başlayan çağrı ağacı (FR-22). */
    public CallTreeNode callTree(String ref, CallTreeOptions options) {
        GraphNode root = resolveNode(ref);
        if (CALL_TREE_ROOTS.stream().noneMatch(root.labels()::contains)) {
            throw new IllegalArgumentException("Çağrı ağacı yalnızca metot, endpoint, Feign/repository metodu, ekran ya da "
                    + "ApiCall için: " + NodeSummary.label(root) + " " + NodeSummary.of(root).name());
        }
        return callTrees.tree(root, options);
    }

    // ---- düğüm, kod, arama, etki ----

    public NodeDetail getNode(String ref) {
        GraphNode node = resolveNode(ref);
        List<NodeDetail.Neighbor> outgoing = neighbors(
                "MATCH (n:Node {id: $id})-[r]->(m:Node) RETURN r, m ORDER BY type(r), m.id LIMIT " + NEIGHBOR_LIMIT,
                node.id());
        List<NodeDetail.Neighbor> incoming = neighbors(
                "MATCH (n:Node {id: $id})<-[r]-(m:Node) RETURN r, m ORDER BY type(r), m.id LIMIT " + NEIGHBOR_LIMIT,
                node.id());
        long outTotal = count("MATCH (:Node {id: $id})-[r]->() RETURN count(r) AS c", node.id());
        long inTotal = count("MATCH (:Node {id: $id})<-[r]-() RETURN count(r) AS c", node.id());
        return new NodeDetail(NodeSummary.of(node), codeSource(node).isPresent(), outgoing, incoming, outTotal, inTotal);
    }

    private List<NodeDetail.Neighbor> neighbors(String cypher, String id) {
        return store.query(cypher, Map.of("id", id)).stream()
                .map(r -> new NodeDetail.Neighbor(EdgeView.of((GraphRelationship) r.get("r")),
                        NodeSummary.of((GraphNode) r.get("m"))))
                .toList();
    }

    /** Kod bloğu (FR-18). Endpoint → handler metodu, ekran → component, tablo → entity kodu. */
    public CodeBlock getCode(String ref) {
        GraphNode node = resolveNode(ref);
        GraphNode source = codeSource(node).orElseThrow(() -> new NotFoundException(
                NodeSummary.label(node) + " düğümünün kod bloğu yok: " + NodeSummary.of(node).name()));
        Map<String, Object> p = source.properties();
        String file = (String) p.get("file");
        return new CodeBlock(node.id(), source.id(), NodeSummary.displayName(NodeSummary.label(source), p),
                (String) p.get("repo"), (String) p.get("service"), file, intOrNull(p.get("startLine")),
                intOrNull(p.get("endLine")), language(file), (String) p.get("code"));
    }

    private Optional<GraphNode> codeSource(GraphNode node) {
        if (node.properties().get("code") != null) {
            return Optional.of(node);
        }
        String cypher = switch (NodeSummary.label(node)) {
            case "Endpoint" -> "MATCH (:Node {id: $id})-[:HANDLED_BY]->(m) RETURN m";
            case "Screen" -> "MATCH (:Node {id: $id})-[:RENDERS]->(m) RETURN m";
            case "Table" -> "MATCH (:Node {id: $id})<-[:MAPPED_TO]-(m) RETURN m ORDER BY m.fqn";
            default -> null;
        };
        if (cypher == null) {
            return Optional.empty();
        }
        return store.query(cypher, Map.of("id", node.id())).stream()
                .map(r -> (GraphNode) r.get("m"))
                .filter(m -> m.properties().get("code") != null)
                .findFirst();
    }

    /**
     * Ad, FQN, path, route, tablo adı ve URL şablonunda büyük-küçük harf duyarsız arama (FR-19). Tam ad eşleşmesi
     * önce, sonra ad başlangıcı, ad içinde geçen ve diğer alanlar gelir.
     *
     * @param labels null ya da boşsa tüm etiketler (ör. {@code Endpoint}, {@code Method}, {@code Table})
     */
    public List<SearchHit> search(String query, List<String> labels, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Arama metni boş olamaz");
        }
        if (limit < 1 || limit > SEARCH_LIMIT) {
            throw new IllegalArgumentException("limit 1 ile " + SEARCH_LIMIT + " arasında olmalı");
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        Matcher endpoint = ENDPOINT_REF.matcher(query.trim());
        String pathQuery = endpoint.matches() ? endpoint.group(3) : query.trim();
        String normalized = pathQuery.startsWith("/") ? PathNormalizer.normalize(pathQuery).toLowerCase(Locale.ROOT) : q;
        String method = endpoint.matches() ? endpoint.group(2).toUpperCase(Locale.ROOT) : null;
        List<SearchHit> hits = new ArrayList<>();
        for (Map<String, Object> row : store.query("""
                MATCH (n:Node) WHERE NOT n:AnalysisRun
                  AND ($labels IS NULL OR any(l IN labels(n) WHERE l IN $labels))
                  AND ($method IS NULL OR n.httpMethod = $method)
                WITH n, [f IN $fields WHERE n[f] IS NOT NULL
                          AND (toLower(toString(n[f])) CONTAINS $q OR toLower(toString(n[f])) CONTAINS $normalized)] AS matched
                WHERE size(matched) > 0
                RETURN n, matched LIMIT 2000""", params("labels", labels == null || labels.isEmpty() ? null : labels,
                "method", method, "fields", SEARCH_FIELDS, "q", endpoint.matches() ? normalized : q,
                "normalized", normalized))) {
            GraphNode n = (GraphNode) row.get("n");
            @SuppressWarnings("unchecked")
            List<String> matched = (List<String>) row.get("matched");
            NodeSummary summary = NodeSummary.of(n);
            hits.add(new SearchHit(summary, matched.get(0),
                    score(summary, n, matched, q, pathQuery.startsWith("/") ? normalized : null)));
        }
        hits.sort(Comparator.comparingInt(SearchHit::score).reversed()
                .thenComparingInt(h -> h.node().name().length())
                .thenComparing(h -> h.node().name()));
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : hits;
    }

    /** @param normalizedPath sorgu bir path ise normalize hâli; endpoint ve ekranlarda tam path eşleşmesi önce gelir */
    private static int score(NodeSummary node, GraphNode n, List<String> matched, String q, String normalizedPath) {
        if (normalizedPath != null) {
            for (String field : List.of("normalizedPath", "routePath")) {
                Object value = n.properties().get(field);
                if (value != null && value.toString().toLowerCase(Locale.ROOT).equals(normalizedPath)) {
                    return n.labels().contains("ApiCall") ? 90 : 100;
                }
            }
        }
        String name = node.name().toLowerCase(Locale.ROOT);
        String simple = name.substring(name.lastIndexOf('.') + 1);
        if (name.equals(q) || simple.equals(q)) {
            return 100;
        }
        if (name.startsWith(q) || simple.startsWith(q)) {
            return 80;
        }
        if (name.contains(q)) {
            return 60;
        }
        return matched.contains("name") ? 50 : 40;
    }

    /** Düğümü doğrudan ya da dolaylı kullanan tüm yukarı akış düğümleri (FR-21). */
    public ImpactResult impact(String ref, int maxDepth, int maxNodes) {
        if (maxDepth < 1 || maxDepth > 100) {
            throw new IllegalArgumentException("maxDepth 1 ile 100 arasında olmalı");
        }
        GraphNode target = resolveNode(ref);
        Traversal.Result result = traversal.expand(target, false, Traversal.TRACE_TYPES, maxDepth, maxNodes, true);
        List<ImpactResult.Affected> affected = result.nodes().values().stream()
                .filter(n -> !n.id().equals(target.id()))
                .map(n -> new ImpactResult.Affected(NodeSummary.of(n), result.distance().get(n.id())))
                .sorted(Comparator.comparingInt(ImpactResult.Affected::distance)
                        .thenComparing(a -> a.node().label())
                        .thenComparing(a -> a.node().name()))
                .toList();
        Map<String, Long> counts = affected.stream()
                .collect(Collectors.groupingBy(a -> a.node().label(), TreeMap::new, Collectors.counting()));
        TraceResult.Summary summary = summary(result);
        return new ImpactResult(NodeSummary.of(target), affected, counts, summary.services(),
                summary.screens().stream().map(NodeSummary::name).toList(), result.truncated(),
                result.truncated() ? "Sonuç sınıra ulaştı; maxDepth/maxNodes'u artırın." : null);
    }

    // ---- analiz ----

    /** Analiz çalıştırmaları, en yeni önce. Çalışan bir analiz varsa en başta durumuyla yer alır. */
    public List<AnalysisRunView> listAnalysisRuns(int limit) {
        List<AnalysisRunView> runs = new ArrayList<>();
        AnalysisStatus status = analysis.status();
        if (status.state() == AnalysisStatus.State.RUNNING) {
            runs.add(fromStatus(status));
        }
        for (Map<String, Object> row : store.query(
                "MATCH (r:AnalysisRun) RETURN r ORDER BY r.startedAt DESC LIMIT $limit", Map.of("limit", limit))) {
            runs.add(fromNode((GraphNode) row.get("r"), false));
        }
        return runs;
    }

    /** @param runId analiz id'si ya da {@code latest} */
    public AnalysisRunView analysisRun(String runId) {
        AnalysisStatus status = analysis.status();
        boolean latest = "latest".equalsIgnoreCase(runId);
        if (status.state() == AnalysisStatus.State.RUNNING && (latest || runId.equals(status.runId()))) {
            return fromStatus(status);
        }
        List<Map<String, Object>> rows = latest
                ? store.query("MATCH (r:AnalysisRun) RETURN r ORDER BY r.startedAt DESC LIMIT 1", Map.of())
                : store.query("MATCH (r:AnalysisRun {runId: $id}) RETURN r", Map.of("id", runId));
        if (rows.isEmpty()) {
            throw new NotFoundException(latest ? "Henüz analiz yapılmadı" : "Analiz bulunamadı: " + runId);
        }
        return fromNode((GraphNode) rows.get(0).get("r"), true);
    }

    private static AnalysisRunView fromStatus(AnalysisStatus s) {
        return new AnalysisRunView(s.runId(), s.state().name(), String.valueOf(s.startedAt()), null, null, null, null,
                s, null);
    }

    private static AnalysisRunView fromNode(GraphNode node, boolean withReport) {
        Map<String, Object> p = node.properties();
        String json = (String) p.get("report");
        return new AnalysisRunView((String) p.get("runId"), (String) p.get("status"), (String) p.get("startedAt"),
                (String) p.get("finishedAt"), p.get("durationMs") == null ? null : ((Number) p.get("durationMs")).longValue(),
                intOrNull(p.get("issueCount")), (String) p.get("error"), null,
                withReport && json != null ? AnalysisReport.fromJson(json) : null);
    }

    // ---- referans çözümü ----

    /** Referansı tek bir düğüme çözer; bulunamazsa {@link NotFoundException}, birden çok adayda açıklayıcı hata. */
    public NodeSummary resolve(String ref) {
        return NodeSummary.of(resolveNode(ref));
    }

    GraphNode resolveNode(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Düğüm referansı boş olamaz");
        }
        String r = ref.trim();
        if (NODE_ID.matcher(r).matches()) {
            return store.query("MATCH (n:Node {id: $id}) RETURN n", Map.of("id", r)).stream()
                    .findFirst().map(row -> (GraphNode) row.get("n"))
                    .orElseThrow(() -> new NotFoundException("Düğüm bulunamadı: " + r));
        }
        List<GraphNode> candidates = new ArrayList<>();
        Matcher endpoint = ENDPOINT_REF.matcher(r);
        Matcher method = METHOD_REF.matcher(r);
        if (endpoint.matches()) {
            candidates.addAll(nodes("""
                    MATCH (n:Endpoint) WHERE n.httpMethod = $method AND (n.path = $path OR n.normalizedPath = $normalized)
                      AND ($service IS NULL OR n.service = $service)
                    RETURN n""", params("method", endpoint.group(2).toUpperCase(Locale.ROOT), "path", endpoint.group(3),
                    "normalized", PathNormalizer.normalize(endpoint.group(3)), "service", endpoint.group(1))));
        } else {
            candidates.addAll(nodes("""
                    MATCH (n:Node) WHERE (n:Screen AND n.routePath = $ref)
                       OR (n:Service AND n.name = $ref)
                       OR (n:Table AND (toLower(n.name) = $lower OR toLower(n.qualifiedName) = $lower))
                       OR ((n:Component OR n:ApiCall OR n:Entity OR n:Class OR n:FeignClient OR n:JpaRepository)
                           AND n.name = $ref)
                       OR ((n:Class OR n:Entity) AND n.fqn = $ref)
                    RETURN n LIMIT 20""", Map.of("ref", r, "lower", r.toLowerCase(Locale.ROOT))));
            if (candidates.isEmpty() && method.matches()) {
                candidates.addAll(nodes("""
                        MATCH (n:Node) WHERE (n:Method OR n:FeignMethod OR n:RepositoryMethod) AND n.name = $method
                          AND n.fqn =~ $pattern
                        RETURN n LIMIT 20""", Map.of("method", method.group(2),
                        "pattern", "(.*\\.)?" + Pattern.quote(method.group(1)) + "#" + Pattern.quote(method.group(2)) + "\\(.*")));
            }
        }
        if (candidates.isEmpty()) {
            throw new NotFoundException("'" + r + "' bulunamadı. Düğüm id'si, route, tablo, 'GET /path', servis ya da "
                    + "Sinif.metot verin; emin değilseniz search ile arayın.");
        }
        // Aynı ada sahip bir ekran ve component varsa ekran, bir servis ve diğerleri varsa servis tercih edilir.
        if (candidates.size() > 1) {
            for (String preferred : List.of("Screen", "Service", "Table")) {
                List<GraphNode> narrowed = candidates.stream().filter(n -> n.labels().contains(preferred)).toList();
                if (narrowed.size() == 1) {
                    return narrowed.get(0);
                }
            }
            throw new IllegalArgumentException("'" + r + "' birden çok düğüme uyuyor; id ile belirtin: " + candidates.stream()
                    .map(n -> n.id() + " (" + NodeSummary.label(n) + " " + NodeSummary.of(n).name()
                            + (n.properties().get("service") == null ? "" : ", " + n.properties().get("service")) + ")")
                    .collect(Collectors.joining("; ")));
        }
        return candidates.get(0);
    }

    // ---- yardımcılar ----

    private List<GraphNode> nodes(String cypher, Map<String, Object> params) {
        return store.query(cypher, params).stream().map(r -> (GraphNode) r.get("n")).toList();
    }

    private long count(String cypher, String id) {
        return ((Number) store.query(cypher, Map.of("id", id)).get(0).get("c")).longValue();
    }

    /** Null değer kabul eden parametre map'i ({@link Map#of} null kabul etmez). */
    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static int layerRank(String layer) {
        int i = layer == null ? -1 : LAYER_ORDER.indexOf(layer);
        return i < 0 ? LAYER_ORDER.size() : i;
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String language(String file) {
        if (file == null) {
            return null;
        }
        String ext = file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "java" -> "java";
            case "ts" -> "typescript";
            case "tsx" -> "tsx";
            case "jsx" -> "jsx";
            case "js", "mjs", "cjs" -> "javascript";
            default -> ext;
        };
    }
}

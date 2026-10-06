package me.emreihtiyar.codegraph.api;

import java.util.List;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.query.CallTreeNode;
import me.emreihtiyar.codegraph.query.CallTreeOptions;
import me.emreihtiyar.codegraph.query.CodeBlock;
import me.emreihtiyar.codegraph.query.EndpointView;
import me.emreihtiyar.codegraph.query.GraphQueryService;
import me.emreihtiyar.codegraph.query.ImpactResult;
import me.emreihtiyar.codegraph.query.NodeDetail;
import me.emreihtiyar.codegraph.query.Orchestration;
import me.emreihtiyar.codegraph.query.ScreenView;
import me.emreihtiyar.codegraph.query.SearchHit;
import me.emreihtiyar.codegraph.query.ServiceDependencies;
import me.emreihtiyar.codegraph.query.ServiceList;
import me.emreihtiyar.codegraph.query.TableUsages;
import me.emreihtiyar.codegraph.query.TraceOptions;
import me.emreihtiyar.codegraph.query.TraceResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Graf sorguları (PRD §8.7). {@code {id}} parametreleri düğüm id'si ya da doğal ad kabul eder (bkz.
 * {@link GraphQueryService}); içinde {@code /} geçen referanslar (route, endpoint) için {@code from}/{@code q} gibi
 * sorgu parametreleri ya da düğüm id'si kullanılır.
 */
@RestController
@RequestMapping("/api")
public class GraphController {

    private final GraphQueryService queries;

    public GraphController(GraphQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/services")
    @McpEquivalent(ToolNames.LIST_SERVICES)
    public ServiceList services(@RequestParam(required = false) String layer) {
        return queries.listServices(layer == null || layer.isBlank() ? null : Layer.parse(layer));
    }

    @GetMapping("/services/{name}/dependencies")
    @McpEquivalent(ToolNames.GET_SERVICE_DEPENDENCIES)
    public ServiceDependencies serviceDependencies(@PathVariable String name) {
        return queries.serviceDependencies(name);
    }

    @GetMapping("/screens")
    @McpEquivalent(ToolNames.LIST_SCREENS)
    public List<ScreenView> screens(@RequestParam(required = false) String service) {
        return queries.listScreens(blankToNull(service));
    }

    @GetMapping("/endpoints")
    @McpEquivalent(ToolNames.LIST_ENDPOINTS)
    public List<EndpointView> endpoints(@RequestParam(required = false) String service) {
        return queries.listEndpoints(blankToNull(service));
    }

    @GetMapping("/trace/forward")
    @McpEquivalent(ToolNames.TRACE_FORWARD)
    public TraceResult traceForward(
            @RequestParam String from,
            @RequestParam(defaultValue = "" + TraceOptions.DEFAULT_DEPTH) int maxDepth,
            @RequestParam(defaultValue = "false") boolean includeAccessors,
            @RequestParam(defaultValue = "true") boolean prune,
            @RequestParam(defaultValue = "" + TraceOptions.DEFAULT_MAX_NODES) int maxNodes) {
        return queries.traceForward(from, new TraceOptions(maxDepth, includeAccessors, prune, maxNodes));
    }

    @GetMapping("/trace/backward")
    @McpEquivalent(ToolNames.TRACE_BACKWARD)
    public TraceResult traceBackward(
            @RequestParam String from,
            @RequestParam(defaultValue = "" + TraceOptions.DEFAULT_DEPTH) int maxDepth,
            @RequestParam(defaultValue = "false") boolean includeAccessors,
            @RequestParam(defaultValue = "" + TraceOptions.DEFAULT_MAX_NODES) int maxNodes) {
        return queries.traceBackward(from, new TraceOptions(maxDepth, includeAccessors, false, maxNodes));
    }

    @GetMapping("/endpoints/{id}/orchestration")
    @McpEquivalent(ToolNames.GET_ENDPOINT_ORCHESTRATION)
    public Orchestration orchestration(
            @PathVariable String id,
            @RequestParam(defaultValue = "20") int maxDepth,
            @RequestParam(defaultValue = "false") boolean includeAccessors) {
        return queries.endpointOrchestration(id, maxDepth, includeAccessors);
    }

    @GetMapping("/tables/{name}/usages")
    @McpEquivalent(ToolNames.FIND_TABLE_USAGES)
    public TableUsages tableUsages(@PathVariable String name) {
        return queries.tableUsages(name);
    }

    @GetMapping("/nodes/{id}")
    @McpEquivalent(ToolNames.GET_NODE)
    public NodeDetail node(@PathVariable String id) {
        return queries.getNode(id);
    }

    @GetMapping("/nodes/{id}/code")
    @McpEquivalent(ToolNames.GET_CODE)
    public CodeBlock code(@PathVariable String id) {
        return queries.getCode(id);
    }

    @GetMapping("/search")
    @McpEquivalent(ToolNames.SEARCH)
    public List<SearchHit> search(
            @RequestParam String q,
            @RequestParam(required = false) List<String> labels,
            @RequestParam(defaultValue = "50") int limit) {
        return queries.search(q, labels, limit);
    }

    @GetMapping("/impact/{id}")
    @McpEquivalent(ToolNames.IMPACT_ANALYSIS)
    public ImpactResult impact(
            @PathVariable String id,
            @RequestParam(defaultValue = "" + TraceOptions.DEFAULT_DEPTH) int maxDepth,
            @RequestParam(defaultValue = "" + TraceOptions.DEFAULT_MAX_NODES) int maxNodes) {
        return queries.impact(id, maxDepth, maxNodes);
    }

    /**
     * @param hidden    gizlenecek sınıf/paket önekleri (FR-23 filtresi)
     * @param ancestors kökün üstündeki metot id'leri; alt ağaç tembel açılırken özyineleme tespiti için
     */
    @GetMapping("/methods/{id}/call-tree")
    @McpEquivalent(ToolNames.GET_CALL_TREE)
    public CallTreeNode callTree(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int depth,
            @RequestParam(defaultValue = "false") boolean includeAccessors,
            @RequestParam(defaultValue = "false") boolean onlyExternal,
            @RequestParam(defaultValue = "true") boolean crossService,
            @RequestParam(required = false) List<String> hidden,
            @RequestParam(required = false) List<String> ancestors) {
        return queries.callTree(id,
                new CallTreeOptions(depth, includeAccessors, onlyExternal, crossService, hidden, ancestors));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

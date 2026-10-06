package me.emreihtiyar.codegraph.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import me.emreihtiyar.codegraph.LiveServerTest;
import me.emreihtiyar.codegraph.api.McpEquivalent;
import me.emreihtiyar.codegraph.api.RestOnly;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * REST ↔ MCP paritesi (PRD §8.8): MCP aracı olmayan bir REST sorgusu ya da REST karşılığı olmayan bir MCP aracı
 * eklenirse build kırılır.
 */
class McpParityTest extends LiveServerTest {

    /** PRD §8.7 tablosundaki MCP karşılıkları; ek araçlar serbest, bunlar eksik olamaz. */
    private static final Set<String> PRD_TOOLS = Set.of("get_analysis_report", "list_services",
            "get_service_dependencies", "list_screens", "trace_forward", "trace_backward", "get_endpoint_orchestration",
            "find_table_usages", "get_node", "get_code", "search", "impact_analysis", "get_call_tree");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private CodeGraphTools tools;

    @Test
    void everyRestQueryHasAnMcpToolAndViceVersa() {
        Map<String, String> toolToEndpoint = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            Set<String> patterns = e.getKey().getPatternValues();
            if (patterns.stream().noneMatch(p -> p.startsWith("/api/"))) {
                continue;
            }
            String endpoint = e.getKey().getMethodsCondition().getMethods() + " " + patterns;
            McpEquivalent mcp = e.getValue().getMethodAnnotation(McpEquivalent.class);
            RestOnly restOnly = e.getValue().getMethodAnnotation(RestOnly.class);
            if ((mcp == null) == (restOnly == null)) {
                problems.add(endpoint + ": @McpEquivalent ya da @RestOnly'den tam olarak biri olmalı");
            } else if (mcp != null) {
                String previous = toolToEndpoint.put(mcp.value(), endpoint);
                if (previous != null) {
                    problems.add(mcp.value() + " iki uç noktaya bağlı: " + previous + ", " + endpoint);
                }
            } else if (restOnly.value().isBlank()) {
                problems.add(endpoint + ": @RestOnly gerekçesi boş");
            }
        }
        Set<String> rest = new TreeSet<>(toolToEndpoint.keySet());
        Set<String> mcpTools = new TreeSet<>(tools.names());
        Set<String> missingTools = new TreeSet<>(rest);
        missingTools.removeAll(mcpTools);
        Set<String> missingEndpoints = new TreeSet<>(mcpTools);
        missingEndpoints.removeAll(rest);
        if (!missingTools.isEmpty()) {
            problems.add("REST'te olup MCP aracı olmayanlar: " + missingTools);
        }
        if (!missingEndpoints.isEmpty()) {
            problems.add("MCP'de olup REST uç noktası olmayanlar: " + missingEndpoints);
        }
        assertThat(problems).isEmpty();
        assertThat(mcpTools).containsAll(PRD_TOOLS);
    }
}

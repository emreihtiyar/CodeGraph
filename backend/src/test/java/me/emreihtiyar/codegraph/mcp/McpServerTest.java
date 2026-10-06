package me.emreihtiyar.codegraph.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.LiveServerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** MCP sunucusu, resmî SDK istemcisiyle Streamable HTTP üzerinden (PRD §8.8). */
class McpServerTest extends LiveServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private CodeGraphTools tools;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl()).endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        McpSchema.InitializeResult init = client.initialize();
        assertThat(init.serverInfo().name()).isEqualTo("codegraph");
        assertThat(init.instructions()).contains("trace_forward");
    }

    @AfterEach
    void close() {
        client.closeGracefully();
    }

    @Test
    void listsReadOnlyToolsWithSchemas() {
        List<McpSchema.Tool> listed = client.listTools().tools();
        assertThat(listed).extracting(McpSchema.Tool::name).containsExactlyInAnyOrderElementsOf(tools.names());
        assertThat(listed).allSatisfy(t -> {
            assertThat(t.annotations().readOnlyHint()).isTrue();
            assertThat(t.annotations().destructiveHint()).isFalse();
            assertThat(t.description()).hasSizeGreaterThan(40);
            assertThat(t.inputSchema()).containsEntry("type", "object");
        });
        McpSchema.Tool trace = listed.stream().filter(t -> t.name().equals("trace_forward")).findFirst().orElseThrow();
        JsonNode schema = JSON.valueToTree(trace.inputSchema());
        assertThat(schema.path("required")).extracting(JsonNode::asString).containsExactly("from");
        assertThat(schema.path("properties").propertyNames())
                .contains("maxDepth", "maxNodes", "prune", "includeGraph");
    }

    @Test
    void traceForwardReturnsTextAndStructuredContent() {
        McpSchema.CallToolResult result = call("trace_forward", Map.of("from", "/credit-application"));
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("İleri iz: Screen /credit-application")
                .contains("LEASING.CREDIT [READ, WRITE]").contains("ADAPTER: core-adapter");
        JsonNode structured = JSON.valueToTree(result.structuredContent());
        assertThat(structured.path("summary").path("tables")).hasSize(5);
        assertThat(structured.path("graph").path("nodeCount").asInt()).isGreaterThan(10);
        assertThat(structured.path("graph").has("nodes")).isFalse();

        JsonNode withGraph = JSON.valueToTree(call("trace_forward",
                Map.of("from", "/credit-application", "includeGraph", true)).structuredContent());
        assertThat(withGraph.path("graph").path("nodes").size()).isEqualTo(withGraph.path("graph").path("nodeCount").asInt());
    }

    @Test
    void sameResultsAsRest() throws Exception {
        HttpResponse<String> rest = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/services")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode mcp = JSON.valueToTree(call("list_services", Map.of()).structuredContent());
        assertThat(mcp).isEqualTo(JSON.readTree(rest.body()));
    }

    @Test
    void orchestrationCallTreeAndCode() {
        String orchestration = text(call("get_endpoint_orchestration", Map.of("endpoint", "POST /applications")));
        assertThat(orchestration).contains("1. FEIGN CreditApiClient.checkLimit")
                .contains("2. FEIGN SalesApiClient.createOffer").contains("{koşullu}");

        String tree = text(call("get_call_tree", Map.of("ref", "CreditServiceImpl.updateStatus", "depth", 1)));
        assertThat(tree).startsWith("METHOD CreditServiceImpl.updateStatus (credit-api)")
                .contains("REPOSITORY_METHOD CreditRepository.findById").contains("inLoop");

        String code = text(call("get_code", Map.of("ref", "GET /applications/{id}")));
        assertThat(code).contains("```java").contains("getApplication");

        String report = text(call("get_analysis_report", Map.of()));
        assertThat(report).contains("SUCCEEDED").contains("Çözülemeyen bağlantılar (3)");
    }

    @Test
    void errorsAreToolErrorsNotProtocolErrors() {
        McpSchema.CallToolResult missing = call("get_node", Map.of("ref", "Nope"));
        assertThat(missing.isError()).isTrue();
        assertThat(text(missing)).contains("bulunamadı").contains("search");

        McpSchema.CallToolResult ambiguous = call("get_code", Map.of("ref", "LimitCheckRequest"));
        assertThat(ambiguous.isError()).isTrue();
        assertThat(text(ambiguous)).contains("birden çok");

        McpSchema.CallToolResult invalid = call("trace_forward", Map.of("from", "/credit-application", "maxDepth", 0));
        assertThat(invalid.isError()).isTrue();

        McpSchema.CallToolResult required = call("find_table_usages", Map.of());
        assertThat(required.isError()).isTrue();
        assertThat(text(required)).contains("'table'");
    }

    @Test
    void rejectsForeignBrowserOrigin() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .header("Origin", "http://evil.example")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
                        "capabilities":{},"clientInfo":{"name":"x","version":"1"}}}"""))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(403);
    }

    private McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
        return client.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
}

package me.emreihtiyar.codegraph.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import me.emreihtiyar.codegraph.LiveServerTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** stdio köprüsü, çalışan sunucuya karşı: stdin'deki JSON-RPC satırları stdout'a yanıt satırları olarak döner. */
class StdioBridgeTest extends LiveServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void relaysSessionRequestsAndResponses() {
        String input = String.join("\n",
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
                """
                {"jsonrpc":"2.0","method":"notifications/initialized"}""",
                """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
                """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_table_usages","arguments":{"table":"offers"}}}""",
                "not json",
                "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        new StdioBridge(URI.create(baseUrl() + "/mcp"), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));

        Map<String, JsonNode> byId = new HashMap<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            JsonNode node = JSON.readTree(line);
            byId.put(node.path("id").asString("null"), node);
        }
        assertThat(byId).containsOnlyKeys("1", "2", "3", "null");
        assertThat(byId.get("1").path("result").path("serverInfo").path("name").asString()).isEqualTo("codegraph");
        assertThat(byId.get("2").path("result").path("tools").size()).isEqualTo(15);
        JsonNode call = byId.get("3").path("result");
        assertThat(call.path("isError").asBoolean()).isFalse();
        assertThat(call.path("structuredContent").path("tables").get(0).path("name").asString()).isEqualTo("offers");
        assertThat(byId.get("null").path("error").path("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void unreachableServerAnswersRequestsWithErrors() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StdioBridge(URI.create("http://localhost:1/mcp"), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .run(new ByteArrayInputStream("""
                        {"jsonrpc":"2.0","id":7,"method":"initialize","params":{}}
                        """.getBytes(StandardCharsets.UTF_8)));
        JsonNode response = JSON.readTree(out.toString(StandardCharsets.UTF_8).trim());
        assertThat(response.path("id").asInt()).isEqualTo(7);
        assertThat(response.path("error").path("message").asString()).contains("bağlanılamadı");
    }
}

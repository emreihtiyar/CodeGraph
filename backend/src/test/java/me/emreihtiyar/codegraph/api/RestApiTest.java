package me.emreihtiyar.codegraph.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import me.emreihtiyar.codegraph.LiveServerTest;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** REST API (PRD §8.7), mini sistem üzerinde gerçek HTTP ile. */
class RestApiTest extends LiveServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    record Response(int status, JsonNode body) {}

    private Response get(String pathAndQuery) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl() + pathAndQuery).build().encode().toUri();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), JSON.readTree(r.body()));
    }

    @Test
    void services() throws Exception {
        Response r = get("/api/services");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("services")).hasSize(7);
        assertThat(r.body().path("dependencies")).hasSize(9);

        Response bff = get("/api/services?layer=bff");
        assertThat(bff.body().path("services")).extracting(s -> s.path("name").asString())
                .containsExactly("leasing-credit-bff", "scoring-bff");
        Response invalid = get("/api/services?layer=nope");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body().path("detail").asString()).contains("Geçersiz katman");

        Response deps = get("/api/services/scoring-bff/dependencies");
        assertThat(deps.body().path("outgoing")).hasSize(3);
    }

    @Test
    void traceForwardAndBackward() throws Exception {
        Response forward = get("/api/trace/forward?from=/credit-application");
        assertThat(forward.status()).isEqualTo(200);
        assertThat(forward.body().path("summary").path("tables")).extracting(t -> t.path("name").asString())
                .containsExactly("CORE.CUSTOMER_SNAPSHOT", "LEASING.CREDIT", "customer_limit", "dealer", "offers");
        assertThat(forward.body().path("graph").path("nodes").size()).isGreaterThan(10);

        Response backward = get("/api/trace/backward?from=offers");
        assertThat(backward.body().path("summary").path("screens")).hasSize(2);

        Response tooDeep = get("/api/trace/forward?from=/credit-application&maxDepth=500");
        assertThat(tooDeep.status()).isEqualTo(400);
    }

    @Test
    void endpointDetailsByIdAndName() throws Exception {
        Response endpoints = get("/api/endpoints?service=leasing-credit-bff");
        JsonNode post = null;
        for (JsonNode e : endpoints.body()) {
            if ("POST".equals(e.path("httpMethod").asString())) {
                post = e;
            }
        }
        assertThat(post).isNotNull();
        String id = post.path("id").asString();

        Response orchestration = get("/api/endpoints/" + id + "/orchestration");
        assertThat(orchestration.body().path("steps")).hasSize(2);

        Response node = get("/api/nodes/" + id);
        assertThat(node.body().path("node").path("label").asString()).isEqualTo("Endpoint");
        assertThat(node.body().path("hasCode").asBoolean()).isTrue();

        Response code = get("/api/nodes/" + id + "/code");
        assertThat(code.body().path("language").asString()).isEqualTo("java");
        assertThat(code.body().path("code").asString()).contains("createApplication");

        Response impact = get("/api/impact/" + id);
        assertThat(impact.body().path("screens")).extracting(JsonNode::asString).containsExactly("/credit-application");
    }

    @Test
    void tablesSearchAndCallTree() throws Exception {
        Response usages = get("/api/tables/LEASING.CREDIT/usages");
        assertThat(usages.status()).isEqualTo(200);
        assertThat(usages.body().path("entities")).hasSize(1);

        Response search = get("/api/search?q=GET /applications/{id}&limit=5");
        assertThat(search.body().get(0).path("node").path("label").asString()).isEqualTo("Endpoint");
        Response labeled = get("/api/search?q=credit&labels=Table&labels=Entity");
        assertThat(labeled.body()).allSatisfy(h -> assertThat(h.path("node").path("label").asString())
                .isIn("Table", "Entity"));

        Response tree = get("/api/methods/CreditServiceImpl.updateStatus/call-tree?depth=2");
        assertThat(tree.body().path("children")).hasSize(3);
        Response hidden = get("/api/methods/ApplicationServiceImpl.createApplication/call-tree?hidden=ApplicationServiceImpl");
        assertThat(hidden.body().path("children")).noneSatisfy(c -> assertThat(c.path("name").asString())
                .startsWith("ApplicationServiceImpl."));
    }

    @Test
    void analysisAndErrors() throws Exception {
        Response latest = get("/api/analysis/latest");
        assertThat(latest.body().path("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(latest.body().path("report").path("unresolvedLinks")).hasSize(3);
        assertThat(get("/api/analysis").body().size()).isGreaterThanOrEqualTo(1);

        Response missing = get("/api/nodes/method_0000000000000000");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body().path("detail").asString()).contains("bulunamadı");

        Response ambiguous = get("/api/nodes/LimitCheckRequest");
        assertThat(ambiguous.status()).isEqualTo(400);
        assertThat(ambiguous.body().path("detail").asString()).contains("birden çok");

        assertThat(get("/api/trace/forward").status()).isEqualTo(400);
    }
}

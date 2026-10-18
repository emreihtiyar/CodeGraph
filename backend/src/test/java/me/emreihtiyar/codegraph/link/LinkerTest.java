package me.emreihtiyar.codegraph.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.MiniSystem;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.extract.model.ApiCallFact;
import me.emreihtiyar.codegraph.extract.model.EndpointFact;
import me.emreihtiyar.codegraph.extract.model.FeignClientFact;
import me.emreihtiyar.codegraph.extract.model.FeignMethodFact;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.model.Resolution;
import me.emreihtiyar.codegraph.extract.model.ServiceFact;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Beklenen değerler {@code fixtures/mini-system/EXPECTED.md} "Feign → Endpoint" ve "ApiCall → BFF Endpoint". */
class LinkerTest {

    private static LinkResult result;

    @BeforeAll
    static void link() {
        result = new Linker().link(MiniSystem.facts(), MiniSystem.aliases());
    }

    @Test
    void apiCallsToBffEndpoints() {
        assertThat(result.apiCalls())
                .extracting(l -> l.sourceKey().substring(l.sourceKey().indexOf('#') + 1), LinkerTest::target,
                        LinkResult.Link::resolution)
                .containsExactlyInAnyOrder(
                        tuple("getCreditApplication", "leasing-credit-bff GET /applications/{*}", Resolution.RESOLVED),
                        tuple("createCreditApplication", "leasing-credit-bff POST /applications", Resolution.RESOLVED),
                        tuple("getApplicationDocument", null, Resolution.UNRESOLVED),
                        tuple("getScore", "scoring-bff GET /scores/{*}", Resolution.RESOLVED),
                        tuple("getCustomerSummary", "scoring-bff GET /customers/{*}/summary", Resolution.RESOLVED),
                        tuple("getScoreHistory", null, Resolution.UNRESOLVED));
        LinkResult.Link history = find(result.apiCalls(), "getScoreHistory");
        assertThat(history.targetService()).isEqualTo("scoring-bff");
        assertThat(history.reason()).contains("/scores/{*}/history");
        assertThat(find(result.apiCalls(), "getApplicationDocument").targetService()).isNull();
    }

    @Test
    void feignMethodsToServiceEndpoints() {
        assertThat(result.feignMethods())
                .extracting(l -> shortName(l.sourceKey()), LinkerTest::target, LinkResult.Link::resolution)
                .containsExactlyInAnyOrder(
                        tuple("CreditApiClient#checkLimit", "credit-api POST /credit-api/v1/credits/limit-checks",
                                Resolution.RESOLVED),
                        tuple("CreditApiClient#getCredit", "credit-api GET /credit-api/v1/credits/{*}", Resolution.RESOLVED),
                        tuple("SalesApiClient#createOffer", "sales-api POST /api/sales/offers", Resolution.RESOLVED),
                        tuple("SalesApiClient#listOffers", "sales-api GET /api/sales/customers/{*}/offers",
                                Resolution.RESOLVED),
                        tuple("ExposureClient#getExposure", "credit-api GET /credit-api/v1/customers/{*}/exposure",
                                Resolution.RESOLVED),
                        tuple("SalesClient#listOffers", "sales-api GET /api/sales/customers/{*}/offers",
                                Resolution.RESOLVED),
                        tuple("LegacyScoreClient#getLegacyScore", null, Resolution.UNRESOLVED),
                        tuple("CoreBankingClient#getCustomer", "core-adapter GET /core/customers/{*}", Resolution.RESOLVED),
                        tuple("CoreClient#getCustomer", "core-adapter GET /core/customers/{*}", Resolution.RESOLVED));
    }

    @Test
    void feignClientTargets() {
        assertThat(result.feignClients())
                .extracting(c -> c.clientFqn().substring(c.clientFqn().lastIndexOf('.') + 1),
                        LinkResult.FeignLink::targetService, LinkResult.FeignLink::targetRepo)
                .contains(
                        tuple("CreditApiClient", "credit-api", "credit-api"),
                        tuple("LegacyScoreClient", "legacy-score-service", null));
    }

    @Test
    void aliasesContextPathAndAmbiguity() {
        var bff = RepoFacts.empty("web-bff", Layer.BFF, new ServiceFact("web-bff", Layer.BFF, "", "web"));
        var api = RepoFacts.empty("orders", Layer.API, new ServiceFact("order-service", Layer.API, "/orders-api", null));
        api.endpoints().add(new EndpointFact(HttpMethod.GET, "/orders-api/orders/{id}", "/orders-api/orders/{*}", "x"));
        api.endpoints().add(new EndpointFact(HttpMethod.GET, "/orders-api/orders/me", "/orders-api/orders/me", "y"));
        api.endpoints().add(new EndpointFact(HttpMethod.ANY, "/orders-api/items/{a}", "/orders-api/items/{*}", "z"));
        api.endpoints().add(new EndpointFact(HttpMethod.GET, "/orders-api/items/{b}", "/orders-api/items/{*}", "w"));
        api.endpoints().add(new EndpointFact(HttpMethod.POST, "/orders-api/items/{b}", "/orders-api/items/{*}", "v"));
        bff.feignClients().add(new FeignClientFact(
                "c.OrderClient", "ORDERS-LEGACY", "ORDERS-LEGACY", null, null));
        bff.feignMethods().add(feign("c.OrderClient#me()", HttpMethod.GET, "/orders/me"));
        bff.feignMethods().add(feign("c.OrderClient#byId(long)", HttpMethod.GET, "/orders/{*}"));
        bff.feignMethods().add(feign("c.OrderClient#item(long)", HttpMethod.GET, "/items/{*}"));
        bff.feignMethods().add(feign("c.OrderClient#anyItem(long)", HttpMethod.ANY, "/items/{*}"));

        LinkResult r = new Linker().link(List.of(bff, api), Map.of("orders", List.of("orders-legacy")));

        assertThat(r.feignClients().get(0).targetService()).isEqualTo("order-service");
        assertThat(r.feignMethods())
                .extracting(l -> shortName(l.sourceKey()), LinkerTest::target, LinkResult.Link::resolution)
                .containsExactly(
                        tuple("OrderClient#me", "order-service GET /orders-api/orders/me", Resolution.RESOLVED),
                        tuple("OrderClient#byId", "order-service GET /orders-api/orders/{*}", Resolution.RESOLVED),
                        tuple("OrderClient#item", "order-service GET /orders-api/items/{*}", Resolution.RESOLVED),
                        tuple("OrderClient#anyItem", "order-service ANY /orders-api/items/{*}", Resolution.RESOLVED));

        bff.feignMethods().clear();
        bff.feignMethods().add(feign("c.OrderClient#put(long)", HttpMethod.PUT, "/items/{*}"));
        api.endpoints().removeIf(e -> e.httpMethod() == HttpMethod.ANY);
        bff.feignMethods().add(feign("c.OrderClient#anyItem(long)", HttpMethod.ANY, "/items/{*}"));
        r = new Linker().link(List.of(bff, api), Map.of("orders", List.of("orders-legacy")));
        assertThat(r.feignMethods())
                .extracting(l -> shortName(l.sourceKey()), l -> l.targets().size(), LinkResult.Link::resolution)
                .containsExactly(
                        tuple("OrderClient#put", 0, Resolution.UNRESOLVED),
                        tuple("OrderClient#anyItem", 2, Resolution.AMBIGUOUS));
    }

    /** {@code @FeignClient(path)} ön eki hedefte görünmüyorsa (dışarıda tanımlı context-path, gateway) atılarak denenir. */
    @Test
    void feignClientPathPrefixMissingOnTarget() {
        var bff = RepoFacts.empty("contract-bff", Layer.BFF, new ServiceFact("contract-bff", Layer.BFF, "", null));
        var api = RepoFacts.empty("leasing-contract", Layer.API, new ServiceFact("leasing-contract", Layer.API, "", null));
        api.endpoints().add(new EndpointFact(HttpMethod.GET, "/sub-contract-view/{id}/terminations",
                "/sub-contract-view/{*}/terminations", "x"));
        bff.feignClients().add(new FeignClientFact("c.OrderClient", "leasing-contract", "leasing-contract",
                "/leasing-contract", null));
        bff.feignMethods().add(feign("c.OrderClient#terminations(long)", HttpMethod.GET,
                "/leasing-contract/sub-contract-view/{*}/terminations"));
        bff.feignMethods().add(feign("c.OrderClient#missing()", HttpMethod.GET, "/leasing-contract/missing"));

        LinkResult r = new Linker().link(List.of(bff, api), Map.of());

        assertThat(r.feignMethods()).extracting(LinkerTest::target, LinkResult.Link::resolution).containsExactly(
                tuple("leasing-contract GET /sub-contract-view/{*}/terminations", Resolution.RESOLVED),
                tuple(null, Resolution.UNRESOLVED));
        assertThat(r.feignMethods().get(1).reason()).contains("/leasing-contract/missing (ya da /missing)");
    }

    @Test
    void bffWithNamespacedServiceNameMatchesByLastSegmentAndRepoName() {
        // spring.application.name 'LEASING.NGLA.leasing-contract-bff', URL'de yalnızca 'leasing-contract-bff'
        String name = "LEASING.NGLA.leasing-contract-bff";
        var bff = RepoFacts.empty("contract-bff-repo", Layer.BFF, new ServiceFact(name, Layer.BFF, "", name));
        bff.endpoints().add(new EndpointFact(HttpMethod.GET, "/api/contracts/{id}", "/api/contracts/{*}", "x"));
        var web = RepoFacts.empty("contract-ui", Layer.FRONTEND, new ServiceFact("contract-ui", Layer.FRONTEND, "", null));
        web.apiCalls().add(apiCall("a", "leasing-contract-bff"));
        web.apiCalls().add(apiCall("b", "contract-bff-repo"));

        LinkResult r = new Linker().link(List.of(web, bff), Map.of());

        assertThat(r.apiCalls()).extracting(LinkerTest::target)
                .containsOnly(name + " GET /api/contracts/{*}");
    }

    private static ApiCallFact apiCall(String key, String prefix) {
        return new ApiCallFact(key, key, HttpMethod.GET, prefix + "/api/contracts/{*}",
                "/api/contracts/{*}", prefix, null, "");
    }

    private static FeignMethodFact feign(String fqn, HttpMethod m, String path) {
        return new FeignMethodFact(fqn, "c.OrderClient", m, path, path, null, null);
    }

    private static LinkResult.Link find(List<LinkResult.Link> links, String name) {
        return links.stream().filter(l -> l.sourceKey().endsWith("#" + name)).findFirst().orElseThrow();
    }

    private static String shortName(String fqn) {
        String type = fqn.substring(0, fqn.indexOf('#'));
        String method = fqn.substring(fqn.indexOf('#') + 1).replaceAll("\\(.*", "");
        return type.substring(type.lastIndexOf('.') + 1) + "#" + method;
    }

    /** Tek hedef varsa {@code servis METOT path}; yoksa ya da birden fazlaysa null. */
    private static String target(LinkResult.Link link) {
        if (link.targets().size() != 1) {
            return null;
        }
        var t = link.targets().get(0);
        return t.service() + " " + t.httpMethod() + " " + t.normalizedPath();
    }
}

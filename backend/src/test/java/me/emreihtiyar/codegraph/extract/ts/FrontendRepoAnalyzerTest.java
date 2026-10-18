package me.emreihtiyar.codegraph.extract.ts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import me.emreihtiyar.codegraph.Fixtures;
import me.emreihtiyar.codegraph.config.ReposConfigLoader;
import me.emreihtiyar.codegraph.extract.model.ApiCallFact;
import me.emreihtiyar.codegraph.extract.model.ApiCallUsageFact;
import me.emreihtiyar.codegraph.extract.model.ApiCallVia;
import me.emreihtiyar.codegraph.extract.model.ComponentFact;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;
import me.emreihtiyar.codegraph.extract.model.RenderFact;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.model.ScreenFact;
import me.emreihtiyar.codegraph.scan.RepoScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Mini sistemin frontend reposu; beklenen değerler {@code fixtures/mini-system/EXPECTED.md}. */
class FrontendRepoAnalyzerTest {

    private static final String CREDIT_API = "src/api/creditApplicationApiCalls.ts#";
    private static final String SCORING_API = "src/api/scoringApiCalls.js#";
    private static final String CREDIT_SCREEN = "src/screens/CreditApplicationScreen.tsx#CreditApplicationScreen";
    private static final String CUSTOMER_SCREEN = "src/screens/CustomerDetailScreen.tsx#CustomerDetailScreen";
    private static final String FORM = "src/components/ApplicationForm.tsx#ApplicationForm";
    private static final String BADGE = "src/components/ScoreBadge.tsx#ScoreBadge";

    private static RepoFacts facts;

    @BeforeAll
    static void analyze() {
        var repo = new ReposConfigLoader().load(Fixtures.miniSystemConfig()).repo("leasing-web").orElseThrow();
        facts = new FrontendRepoAnalyzer().analyze(new RepoScanner().scan(repo));
    }

    @Test
    void parsesWithoutIssues() {
        assertThat(facts.issues()).isEmpty();
        assertThat(facts.service().name()).isEqualTo("leasing-web");
    }

    /** {@code getBaseUrl} gibi callApi çağırmayan export'lar ApiCall değildir. */
    @Test
    void apiCalls() {
        assertThat(facts.apiCalls())
                .extracting(ApiCallFact::key, ApiCallFact::httpMethod, ApiCallFact::targetPrefix, ApiCallFact::normalizedPath)
                .containsExactlyInAnyOrder(
                        tuple(CREDIT_API + "getCreditApplication", HttpMethod.GET, "leasing-credit-bff", "/applications/{*}"),
                        tuple(CREDIT_API + "createCreditApplication", HttpMethod.POST, "leasing-credit-bff", "/applications"),
                        tuple(CREDIT_API + "getApplicationDocument", HttpMethod.GET, null, null),
                        tuple(SCORING_API + "getScore", HttpMethod.GET, "scoring", "/scores/{*}"),
                        tuple(SCORING_API + "getCustomerSummary", HttpMethod.GET, "scoring", "/customers/{*}/summary"),
                        tuple(SCORING_API + "getScoreHistory", HttpMethod.GET, "scoring", "/scores/{*}/history"));
    }

    @Test
    void apiCallTemplatesAndCode() {
        ApiCallFact get = facts.apiCalls().stream()
                .filter(a -> a.name().equals("getCreditApplication")).findFirst().orElseThrow();
        assertThat(get.urlTemplate()).isEqualTo("leasing-credit-bff/applications/{*}");
        assertThat(get.source().startLine()).isEqualTo(13);
        assertThat(get.source().endLine()).isEqualTo(19);
        assertThat(get.code()).startsWith("export function getCreditApplication").contains("callApi(");
        ApiCallFact create = facts.apiCalls().stream()
                .filter(a -> a.name().equals("createCreditApplication")).findFirst().orElseThrow();
        assertThat(create.source().startLine()).isEqualTo(21);
        assertThat(create.code()).startsWith("export const createCreditApplication");
        ApiCallFact document = facts.apiCalls().stream()
                .filter(a -> a.name().equals("getApplicationDocument")).findFirst().orElseThrow();
        assertThat(document.urlTemplate()).isEqualTo("{*}");
    }

    @Test
    void components() {
        assertThat(facts.components())
                .extracting(ComponentFact::key)
                .containsExactlyInAnyOrder(CREDIT_SCREEN, CUSTOMER_SCREEN, FORM, BADGE);
    }

    @Test
    void screensFromRoutes() {
        assertThat(facts.screens())
                .extracting(ScreenFact::routePath, ScreenFact::name, ScreenFact::componentKey)
                .containsExactlyInAnyOrder(
                        tuple("/credit-application", "CreditApplicationScreen", CREDIT_SCREEN),
                        tuple("/customers/:customerId", "CustomerDetailScreen", CUSTOMER_SCREEN));
    }

    @Test
    void renders() {
        assertThat(facts.renders())
                .extracting(RenderFact::parentComponentKey, RenderFact::childComponentKey)
                .containsExactlyInAnyOrder(
                        tuple(CREDIT_SCREEN, BADGE),
                        tuple(CREDIT_SCREEN, FORM),
                        tuple(CUSTOMER_SCREEN, BADGE));
    }

    @Test
    void apiCallUsagesInSourceOrder() {
        assertThat(facts.apiCallUsages())
                .extracting(ApiCallUsageFact::componentKey, ApiCallUsageFact::apiCallKey, ApiCallUsageFact::order,
                        ApiCallUsageFact::via, ApiCallUsageFact::hook)
                .containsExactlyInAnyOrder(
                        tuple(CREDIT_SCREEN, CREDIT_API + "getCreditApplication", 1, ApiCallVia.REACT_QUERY, "useQuery"),
                        tuple(CUSTOMER_SCREEN, SCORING_API + "getScore", 1, ApiCallVia.REACT_QUERY, "useQueries"),
                        tuple(CUSTOMER_SCREEN, SCORING_API + "getCustomerSummary", 2, ApiCallVia.REACT_QUERY, "useQueries"),
                        tuple(CUSTOMER_SCREEN, SCORING_API + "getScoreHistory", 3, ApiCallVia.DIRECT, null),
                        tuple(FORM, CREDIT_API + "createCreditApplication", 1, ApiCallVia.REACT_QUERY, "useMutation"),
                        tuple(BADGE, SCORING_API + "getScore", 1, ApiCallVia.REACT_QUERY, "useScore > useQuery"));
    }
}

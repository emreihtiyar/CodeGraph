package me.emreihtiyar.codegraph.extract.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.emreihtiyar.codegraph.extract.model.AccessType;
import me.emreihtiyar.codegraph.extract.model.CallFact;
import me.emreihtiyar.codegraph.extract.model.CallTargetKind;
import me.emreihtiyar.codegraph.extract.model.EndpointFact;
import me.emreihtiyar.codegraph.extract.model.EntityRelationType;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;
import me.emreihtiyar.codegraph.extract.model.MethodFact;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.model.Resolution;
import me.emreihtiyar.codegraph.extract.model.Visibility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Mini sistemin Java repoları; beklenen değerler {@code fixtures/mini-system/EXPECTED.md}. */
class JavaRepoAnalyzerTest {

    private static final Map<String, RepoFacts> FACTS = new HashMap<>();

    @BeforeAll
    static void analyze() {
        for (String repo : List.of("leasing-credit-bff", "scoring-bff", "credit-api", "sales-api", "core-adapter")) {
            FACTS.put(repo, MiniSystemJava.analyze(repo));
        }
    }

    @Test
    void parsesWithoutIssues() {
        FACTS.forEach((repo, facts) -> assertThat(facts.issues()).as(repo).isEmpty());
    }

    @Test
    void servicesUseApplicationNameContextPathAndGatewayPrefix() {
        assertThat(FACTS.get("credit-api").service().contextPath()).isEqualTo("/credit-api");
        assertThat(FACTS.get("credit-api").service().gatewayPrefix()).isNull();
        assertThat(FACTS.get("leasing-credit-bff").service().gatewayPrefix()).isEqualTo("leasing-credit-bff");
        assertThat(FACTS.get("scoring-bff").service().gatewayPrefix()).isEqualTo("scoring");
        assertThat(FACTS.get("sales-api").service().name()).isEqualTo("sales-api");
    }

    @Test
    void endpoints() {
        assertThat(FACTS.values().stream().flatMap(f -> f.endpoints().stream()).toList())
                .extracting(EndpointFact::httpMethod, EndpointFact::normalizedPath, e -> shortName(e.handlerMethodFqn()))
                .containsExactlyInAnyOrder(
                        tuple(HttpMethod.GET, "/applications/{*}", "CreditApplicationController#getApplication"),
                        tuple(HttpMethod.POST, "/applications", "CreditApplicationController#createApplication"),
                        tuple(HttpMethod.GET, "/scores/{*}", "ScoreController#getScore"),
                        tuple(HttpMethod.GET, "/customers/{*}/summary", "ScoreController#summary"),
                        tuple(HttpMethod.POST, "/credit-api/v1/credits/limit-checks", "CreditController#checkLimit"),
                        tuple(HttpMethod.GET, "/credit-api/v1/credits/{*}", "CreditController#getCredit"),
                        tuple(HttpMethod.PUT, "/credit-api/v1/credits/{*}/status", "CreditController#updateStatus"),
                        tuple(HttpMethod.GET, "/credit-api/v1/customers/{*}/exposure",
                                "CustomerExposureController#getExposure"),
                        tuple(HttpMethod.POST, "/api/sales/offers", "SalesController#createOffer"),
                        tuple(HttpMethod.GET, "/api/sales/customers/{*}/offers", "SalesController#listOffers"),
                        tuple(HttpMethod.GET, "/core/customers/{*}", "CoreCustomerController#getCustomer"));
    }

    @Test
    void feignClientsAndMethods() {
        var all = FACTS.values().stream().flatMap(f -> f.feignMethods().stream()).toList();
        assertThat(all)
                .extracting(m -> shortName(m.fqn()), m -> m.httpMethod(), m -> m.normalizedPath())
                .containsExactlyInAnyOrder(
                        tuple("CreditApiClient#checkLimit", HttpMethod.POST, "/credit-api/v1/credits/limit-checks"),
                        tuple("CreditApiClient#getCredit", HttpMethod.GET, "/credit-api/v1/credits/{*}"),
                        tuple("SalesApiClient#createOffer", HttpMethod.POST, "/api/sales/offers"),
                        tuple("SalesApiClient#listOffers", HttpMethod.GET, "/api/sales/customers/{*}/offers"),
                        tuple("ExposureClient#getExposure", HttpMethod.GET, "/credit-api/v1/customers/{*}/exposure"),
                        tuple("SalesClient#listOffers", HttpMethod.GET, "/api/sales/customers/{*}/offers"),
                        tuple("LegacyScoreClient#getLegacyScore", HttpMethod.GET, "/legacy/scores/{*}"),
                        tuple("CoreBankingClient#getCustomer", HttpMethod.GET, "/core/customers/{*}"),
                        tuple("CoreClient#getCustomer", HttpMethod.GET, "/core/customers/{*}"));
        assertThat(FACTS.get("leasing-credit-bff").feignClients())
                .extracting(c -> c.rawName(), c -> c.targetServiceName())
                .contains(tuple("${clients.credit.name}", "credit-api"));
    }

    @Test
    void entitiesTablesAndRelations() {
        assertThat(FACTS.values().stream().flatMap(f -> f.entities().stream()).toList())
                .extracting(e -> simple(e.fqn()), e -> e.schema() == null ? e.tableName() : e.schema() + "." + e.tableName())
                .containsExactlyInAnyOrder(
                        tuple("Credit", "LEASING.CREDIT"),
                        tuple("CreditInstallment", "credit_installment"),
                        tuple("CustomerLimit", "customer_limit"),
                        tuple("Offer", "offers"),
                        tuple("Dealer", "dealer"),
                        tuple("Region", "region"),
                        tuple("CustomerSnapshot", "CORE.CUSTOMER_SNAPSHOT"));
        assertThat(FACTS.values().stream().flatMap(f -> f.entityRelations().stream()).toList())
                .extracting(r -> simple(r.fromEntityFqn()), r -> r.type(), r -> simple(r.toEntityFqn()), r -> r.field())
                .containsExactlyInAnyOrder(
                        tuple("Credit", EntityRelationType.ONE_TO_MANY, "CreditInstallment", "installments"),
                        tuple("CreditInstallment", EntityRelationType.MANY_TO_ONE, "Credit", "credit"),
                        tuple("Offer", EntityRelationType.MANY_TO_ONE, "Dealer", "dealer"),
                        tuple("Dealer", EntityRelationType.MANY_TO_MANY, "Region", "regions"));
    }

    @Test
    void repositoryMethodsAreClassifiedAsReadOrWrite() {
        Map<String, AccessType> access = FACTS.values().stream()
                .flatMap(f -> f.repositoryMethods().stream())
                .collect(Collectors.toMap(m -> shortName(m.fqn()), m -> m.accessType(), (a, b) -> a));
        assertThat(access).containsEntry("CreditRepository#findByCustomerId", AccessType.READ)
                .containsEntry("CreditRepository#findAllWithStatus", AccessType.READ)
                .containsEntry("CreditRepository#markRejected", AccessType.WRITE)
                .containsEntry("CreditRepository#findById", AccessType.READ)
                .containsEntry("CreditRepository#save", AccessType.WRITE)
                .containsEntry("CustomerLimitRepository#existsByCustomerId", AccessType.READ)
                .containsEntry("OfferRepository#findAllByCustomerId", AccessType.READ)
                .containsEntry("OfferRepository#save", AccessType.WRITE)
                .containsEntry("DealerRepository#findById", AccessType.READ)
                .containsEntry("CustomerSnapshotRepository#findByCustomerNo", AccessType.READ);
        // CrudRepository'de JpaRepository'ye özgü metotlar yoktur
        assertThat(access).doesNotContainKey("OfferRepository#flush");
    }

    @Test
    void methodsIncludePrivateStaticAndLombokAccessors() {
        Map<String, MethodFact> methods = methodsByShortName("credit-api");
        assertThat(methods.get("CreditServiceImpl#validate").visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(methods.get("CreditServiceImpl#toResult").isStatic()).isTrue();
        assertThat(methods.get("CreditService#checkLimit").isAbstract()).isTrue();
        MethodFact lombokGetter = methods.get("CustomerLimit#getAvailable");
        assertThat(lombokGetter.synthetic()).isTrue();
        assertThat(lombokGetter.isAccessor()).isTrue();
        assertThat(methods.get("LimitCheckResult#rejected").synthetic()).isFalse(); // record'daki statik fabrika
        assertThat(methods.get("ExposureService#sum").fqn()).endsWith("#sum(List,int)");
    }

    @Test
    void overridesLinkImplementationsToInterfaces() {
        assertThat(FACTS.get("scoring-bff").overrides())
                .extracting(o -> shortName(o.methodFqn()), o -> shortName(o.overriddenFqn()))
                .contains(
                        tuple("DefaultRiskCalculator#calculate", "RiskCalculator#calculate"),
                        tuple("ConservativeRiskCalculator#calculate", "RiskCalculator#calculate"));
    }

    @Test
    void createApplicationCallTree() {
        assertThat(tree("leasing-credit-bff", "ApplicationServiceImpl#createApplication")).containsExactly(
                "ApplicationServiceImpl#validateRequest",
                "ApplicationServiceImpl#toLimitRequest",
                "CreditApiClient#checkLimit",
                "SalesApiClient#createOffer",
                "ApplicationServiceImpl#audit",
                "ApplicationServiceImpl#merge");
        CallFact createOffer = call("leasing-credit-bff", "ApplicationServiceImpl#createApplication",
                "SalesApiClient#createOffer");
        assertThat(createOffer.targetKind()).isEqualTo(CallTargetKind.FEIGN_METHOD);
        assertThat(createOffer.conditional()).isTrue();
        assertThat(createOffer.conditionText()).contains("limit.approved()");
        CallFact audit = call("leasing-credit-bff", "ApplicationServiceImpl#createApplication",
                "ApplicationServiceImpl#audit");
        assertThat(audit.async()).isTrue();
        assertThat(audit.viaLambda()).isTrue();
        assertThat(tree("leasing-credit-bff", "ApplicationServiceImpl#validateRequest"))
                .containsExactly("ValidationUtils#requireText");
    }

    @Test
    void getApplicationCallTreeFollowsMethodReference() {
        assertThat(tree("leasing-credit-bff", "ApplicationServiceImpl#getApplication")).containsExactly(
                "CreditApiClient#getCredit", "SalesApiClient#listOffers", "ApplicationServiceImpl#toOfferView");
        CallFact ref = call("leasing-credit-bff", "ApplicationServiceImpl#getApplication",
                "ApplicationServiceImpl#toOfferView");
        assertThat(ref.viaLambda()).isTrue();
        assertThat(ref.inLoop()).isTrue();
    }

    @Test
    void singleImplementationInterfaceResolvesDirectly() {
        CallFact call = call("leasing-credit-bff", "CreditApplicationController#createApplication",
                "ApplicationServiceImpl#createApplication");
        assertThat(call.resolution()).isEqualTo(Resolution.RESOLVED);
        assertThat(call("credit-api", "CreditController#checkLimit", "CreditServiceImpl#checkLimit").resolution())
                .isEqualTo(Resolution.RESOLVED);
    }

    @Test
    void checkLimitCallTree() {
        assertThat(tree("credit-api", "CreditServiceImpl#checkLimit")).containsExactly(
                "CreditServiceImpl#validate",
                "CoreBankingClient#getCustomer",
                "CustomerLimitRepository#findByCustomerId",
                "CreditRepository#markRejected",
                "LimitCheckResult#rejected",
                "CreditServiceImpl#toResult");
        CallFact markRejected = call("credit-api", "CreditServiceImpl#checkLimit", "CreditRepository#markRejected");
        assertThat(markRejected.targetKind()).isEqualTo(CallTargetKind.REPOSITORY_METHOD);
        assertThat(markRejected.conditional()).isTrue();
        assertThat(call("credit-api", "CreditServiceImpl#checkLimit", "CreditServiceImpl#toResult").conditional())
                .isFalse();
    }

    @Test
    void updateStatusCallTreeUsesInheritedRepositoryMethods() {
        assertThat(tree("credit-api", "CreditServiceImpl#updateStatus")).containsExactly(
                "CreditRepository#findById", "CreditServiceImpl#recalculate", "CreditRepository#save");
        assertThat(call("credit-api", "CreditServiceImpl#updateStatus", "CreditServiceImpl#recalculate").inLoop())
                .isTrue();
    }

    @Test
    void recursionIsMarked() {
        CallFact call = call("credit-api", "ExposureService#sum", "ExposureService#sum");
        assertThat(call.recursive()).isTrue();
        assertThat(FACTS.get("credit-api").calls()).noneMatch(c -> c.resolution() == Resolution.UNRESOLVED);
    }

    @Test
    void polymorphicCallIsAmbiguousWithAllCandidates() {
        List<CallFact> calculate = calls("scoring-bff", "ScoreService#score").stream()
                .filter(c -> c.targetFqn().contains("RiskCalculator#calculate"))
                .toList();
        assertThat(calculate).extracting(c -> shortName(c.targetFqn()))
                .containsExactlyInAnyOrder("DefaultRiskCalculator#calculate", "ConservativeRiskCalculator#calculate");
        assertThat(calculate).allMatch(c -> c.resolution() == Resolution.AMBIGUOUS);
        assertThat(calculate).extracting(CallFact::order).containsOnly(calculate.get(0).order());
        // Miras alınan korumalı metoda hem niteliksiz hem this. ile yapılan çağrı
        assertThat(tree("scoring-bff", "DefaultRiskCalculator#calculate")).containsExactly("BaseRiskCalculator#normalize");
        assertThat(tree("scoring-bff", "ConservativeRiskCalculator#calculate"))
                .containsExactly("BaseRiskCalculator#normalize");
    }

    @Test
    void summaryCallTree() {
        assertThat(tree("scoring-bff", "ScoreService#summary")).containsExactly(
                "ScoreService#score", "SalesClient#listOffers", "LegacyScoreClient#getLegacyScore");
    }

    @Test
    void createOfferCallTree() {
        assertThat(tree("sales-api", "OfferService#createOffer")).containsExactly(
                "DealerRepository#findById",
                "CoreClient#getCustomer",
                "OfferRepository#save",
                "OfferService#notifyDealer",
                "OfferService#toDto");
        CallFact notify = call("sales-api", "OfferService#createOffer", "OfferService#notifyDealer");
        assertThat(notify.async()).isTrue();
        assertThat(notify.viaLambda()).isTrue();
        assertThat(call("sales-api", "OfferService#listOffers", "OfferService#toDto").inLoop()).isTrue();
    }

    // ---------------------------------------------------------------- yardımcılar

    /** Çağıranın, accessor olmayan hedefleri, çalışma sırasıyla (AMBIGUOUS adaylar tek tek). */
    private static List<String> tree(String repo, String caller) {
        Set<String> accessors = FACTS.values().stream()
                .flatMap(f -> f.methods().stream())
                .filter(MethodFact::isAccessor)
                .map(MethodFact::fqn)
                .collect(Collectors.toSet());
        return calls(repo, caller).stream()
                .filter(c -> !accessors.contains(c.targetFqn()))
                .map(c -> shortName(c.targetFqn()))
                .toList();
    }

    private static List<CallFact> calls(String repo, String caller) {
        return FACTS.get(repo).calls().stream()
                .filter(c -> shortName(c.callerFqn()).equals(caller))
                .sorted(java.util.Comparator.comparingInt(CallFact::order))
                .toList();
    }

    private static CallFact call(String repo, String caller, String target) {
        return calls(repo, caller).stream()
                .filter(c -> c.targetFqn() != null && shortName(c.targetFqn()).equals(target))
                .findFirst()
                .orElseThrow(() -> new AssertionError(caller + " → " + target + " bulunamadı"));
    }

    private static Map<String, MethodFact> methodsByShortName(String repo) {
        return FACTS.get(repo).methods().stream()
                .collect(Collectors.toMap(m -> shortName(m.fqn()), m -> m, (a, b) -> a));
    }

    /** {@code com.x.Sinif#metot(A,B)} → {@code Sinif#metot}. */
    static String shortName(String fqn) {
        int hash = fqn.indexOf('#');
        String type = hash < 0 ? fqn : fqn.substring(0, hash);
        String member = hash < 0 ? "" : fqn.substring(hash);
        int paren = member.indexOf('(');
        return simple(type) + (paren < 0 ? member : member.substring(0, paren));
    }

    private static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}

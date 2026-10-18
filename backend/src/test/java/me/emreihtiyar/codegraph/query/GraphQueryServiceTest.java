package me.emreihtiyar.codegraph.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import me.emreihtiyar.codegraph.MiniSystemGraph;
import me.emreihtiyar.codegraph.config.Layer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Sorgu katmanı, mini sistem üzerinde; beklenen değerler {@code fixtures/mini-system/EXPECTED.md}. */
class GraphQueryServiceTest {

    private static GraphQueryService queries;

    @BeforeAll
    static void setUp() {
        queries = MiniSystemGraph.queryService();
    }

    @Test
    void listServicesInLayerOrderWithDependencies() {
        ServiceList list = queries.listServices(null);
        assertThat(list.services()).extracting(ServiceList.Service::name, ServiceList.Service::layer)
                .containsExactly(
                        tuple("leasing-web", "FRONTEND"),
                        tuple("leasing-credit-bff", "BFF"),
                        tuple("scoring-bff", "BFF"),
                        tuple("credit-api", "API"),
                        tuple("sales-api", "API"),
                        tuple("core-adapter", "ADAPTER"),
                        tuple("legacy-score-service", null));
        assertThat(list.dependencies()).hasSize(9);
        assertThat(list.services().get(0).screenCount()).isEqualTo(2);
        assertThat(list.services().get(3).endpointCount()).isEqualTo(4);
        assertThat(list.services()).extracting(ServiceList.Service::name, ServiceList.Service::tableCount)
                .contains(tuple("credit-api", 2L), tuple("sales-api", 2L), tuple("core-adapter", 1L),
                        tuple("leasing-credit-bff", 0L));

        assertThat(queries.listServices(Layer.BFF).services()).extracting(ServiceList.Service::name)
                .containsExactly("leasing-credit-bff", "scoring-bff");
        assertThat(queries.listServices(Layer.BFF).dependencies()).isEmpty();
    }

    @Test
    void serviceDependenciesWithConcreteLinks() {
        ServiceDependencies deps = queries.serviceDependencies("scoring-bff");
        assertThat(deps.outgoing()).extracting(ServiceDependencies.Dependency::service,
                        ServiceDependencies.Dependency::analyzed)
                .containsExactly(tuple("credit-api", true), tuple("legacy-score-service", false), tuple("sales-api", true));
        assertThat(deps.outgoing().get(1).links()).singleElement()
                .satisfies(l -> {
                    assertThat(l.source()).isEqualTo("LegacyScoreClient.getLegacyScore");
                    assertThat(l.resolution()).isEqualTo("UNRESOLVED");
                    assertThat(l.target()).isNull();
                });
        assertThat(deps.incoming()).singleElement().satisfies(d -> {
            assertThat(d.service()).isEqualTo("leasing-web");
            assertThat(d.links()).extracting(ServiceDependencies.Link::source, ServiceDependencies.Link::target)
                    .containsExactlyInAnyOrder(
                            tuple("getCustomerSummary", "GET /customers/{customerId}/summary"),
                            tuple("getScore", "GET /scores/{customerId}"),
                            tuple("getScoreHistory", null));
        });
        assertThatThrownBy(() -> queries.serviceDependencies("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void screensAndEndpoints() {
        assertThat(queries.listScreens(null)).extracting(ScreenView::routePath, ScreenView::service)
                .containsExactly(tuple("/credit-application", "leasing-web"), tuple("/customers/:customerId", "leasing-web"));
        assertThat(queries.listEndpoints("core-adapter")).singleElement()
                .satisfies(e -> assertThat(e.handler()).isEqualTo("CoreCustomerController.getCustomer"));
    }

    @Test
    void forwardTraceFromScreen() {
        TraceResult trace = queries.traceForward("/credit-application", TraceOptions.defaults());

        assertThat(trace.start().label()).isEqualTo("Screen");
        assertThat(trace.summary().tables()).extracting(TraceResult.TableAccess::name, TraceResult.TableAccess::access)
                .containsExactly(
                        tuple("CORE.CUSTOMER_SNAPSHOT", List.of("READ")),
                        tuple("LEASING.CREDIT", List.of("READ", "WRITE")),
                        tuple("customer_limit", List.of("READ")),
                        tuple("dealer", List.of("READ")),
                        tuple("offers", List.of("READ", "WRITE")));
        assertThat(trace.summary().services()).extracting(TraceResult.LayerServices::layer, TraceResult.LayerServices::services)
                .containsExactly(
                        tuple("FRONTEND", List.of("leasing-web")),
                        tuple("BFF", List.of("leasing-credit-bff", "scoring-bff")),
                        tuple("API", List.of("credit-api", "sales-api")),
                        tuple("ADAPTER", List.of("core-adapter")));
        assertThat(trace.summary().unresolved()).isEmpty();
        // Budama: dış çağrıya ya da DB'ye gitmeyen ara metotlar izde yok
        assertThat(trace.graph().nodes()).extracting(NodeSummary::name)
                .contains("ApplicationServiceImpl.createApplication", "CreditApiClient.checkLimit")
                .doesNotContain("ApplicationServiceImpl.merge", "ApplicationServiceImpl.validateRequest");
        assertThat(trace.graph().edges()).allSatisfy(e -> assertThat(trace.graph().nodes())
                .extracting(NodeSummary::id).contains(e.source(), e.target()));
        // Ekranın component ağacı gizli: ekran isteklerine doğrudan bağlı, kenarda isteğin çıktığı component
        assertThat(trace.graph().nodes()).extracting(NodeSummary::label).doesNotContain("Component");
        assertThat(trace.summary().apiCalls()).extracting(NodeSummary::name)
                .containsExactlyInAnyOrder("getCreditApplication", "createCreditApplication", "getScore");
        assertThat(trace.graph().edges()).filteredOn(e -> e.type().equals("USES_API_CALL"))
                .allSatisfy(e -> assertThat(e.source()).isEqualTo(trace.start().id()))
                .extracting(e -> e.properties().get("component"))
                .containsExactlyInAnyOrder("CreditApplicationScreen", "ApplicationForm", "ScoreBadge");

        TraceResult full = queries.traceForward("/credit-application",
                new TraceOptions(TraceOptions.DEFAULT_DEPTH, false, false, TraceOptions.DEFAULT_MAX_NODES));
        assertThat(full.graph().nodes()).extracting(NodeSummary::name).contains("ApplicationServiceImpl.merge");
    }

    @Test
    void forwardTraceReportsUnresolvedAndTruncation() {
        TraceResult trace = queries.traceForward("/customers/:customerId", TraceOptions.defaults());
        assertThat(trace.summary().unresolved()).extracting(NodeSummary::name)
                .containsExactlyInAnyOrder("getScoreHistory", "LegacyScoreClient.getLegacyScore");

        TraceResult small = queries.traceForward("/credit-application", new TraceOptions(3, false, true, 2000));
        assertThat(small.graph().truncated()).isTrue();
        assertThat(small.graph().note()).isNotBlank();
        assertThat(small.summary().tables()).isEmpty();
    }

    @Test
    void backwardTraceAndImpact() {
        TraceResult trace = queries.traceBackward("offers", TraceOptions.defaults());
        assertThat(trace.summary().screens()).extracting(NodeSummary::name)
                .containsExactly("/credit-application", "/customers/:customerId");
        assertThat(trace.summary().tables()).singleElement()
                .satisfies(t -> assertThat(t.access()).containsExactly("READ", "WRITE"));

        ImpactResult impact = queries.impact("CORE.CUSTOMER_SNAPSHOT", 40, 5000);
        assertThat(impact.screens()).containsExactly("/credit-application");
        assertThat(impact.services()).flatExtracting(TraceResult.LayerServices::services)
                .containsExactly("leasing-web", "leasing-credit-bff", "credit-api", "sales-api", "core-adapter");
        assertThat(impact.affected().get(0).distance()).isEqualTo(1);
        assertThat(impact.countsByLabel()).containsKeys("Endpoint", "Method", "FeignMethod", "ApiCall", "Screen");
    }

    @Test
    void bffOrchestrationInOrder() {
        Orchestration o = queries.endpointOrchestration("POST /applications", 20, false);
        assertThat(o.handler().name()).isEqualTo("CreditApplicationController.createApplication");
        assertThat(o.steps()).extracting(Orchestration.Step::kind, Orchestration.Step::name,
                        Orchestration.Step::conditional, Orchestration.Step::targets)
                .containsExactly(
                        tuple("FEIGN", "CreditApiClient.checkLimit", false,
                                List.of("credit-api POST /credit-api/v1/credits/limit-checks")),
                        tuple("FEIGN", "SalesApiClient.createOffer", true, List.of("sales-api POST /api/sales/offers")));
        assertThat(o.steps().get(0).via()).contains("ApplicationServiceImpl.createApplication");

        Orchestration credit = queries.endpointOrchestration("credit-api POST /credit-api/v1/credits/limit-checks", 20,
                false);
        assertThat(credit.steps()).extracting(Orchestration.Step::name, Orchestration.Step::access,
                        Orchestration.Step::targets, Orchestration.Step::conditional)
                .containsExactly(
                        tuple("CoreBankingClient.getCustomer", null, List.of("core-adapter GET /core/customers/{customerNo}"),
                                false),
                        tuple("CustomerLimitRepository.findByCustomerId", "READ", List.of("customer_limit"), false),
                        tuple("CreditRepository.markRejected", "WRITE", List.of("LEASING.CREDIT"), true));
        assertThatThrownBy(() -> queries.endpointOrchestration("offers", 20, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tableUsagesWithReadWrite() {
        TableUsages usages = queries.tableUsages("leasing.credit");
        assertThat(usages.tables()).extracting(NodeSummary::name).containsExactly("LEASING.CREDIT");
        assertThat(usages.entities()).extracting(NodeSummary::name).containsExactly("Credit");
        assertThat(usages.usages())
                .filteredOn(u -> u.caller() != null)
                .extracting(TableUsages.Usage::caller, TableUsages.Usage::repositoryMethod, TableUsages.Usage::access)
                .contains(
                        tuple("CreditServiceImpl.updateStatus", "CreditRepository.findById", "READ"),
                        tuple("CreditServiceImpl.updateStatus", "CreditRepository.save", "WRITE"),
                        tuple("CreditServiceImpl.checkLimit", "CreditRepository.markRejected", "WRITE"));
        assertThatThrownBy(() -> queries.tableUsages("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void callTreeLazyWithBadgesAndRecursion() {
        CallTreeNode root = queries.callTree("CreditServiceImpl.updateStatus", CallTreeOptions.defaults());
        assertThat(root.kind()).isEqualTo("METHOD");
        assertThat(root.call()).isNull();
        assertThat(root.children()).extracting(CallTreeNode::name, CallTreeNode::kind, c -> c.call().get("inLoop"))
                .containsExactly(
                        tuple("CreditRepository.findById", "REPOSITORY_METHOD", false),
                        tuple("CreditServiceImpl.recalculate", "METHOD", true),
                        tuple("CreditRepository.save", "REPOSITORY_METHOD", false));
        assertThat(root.children().get(0).details()).containsEntry("accessType", "READ")
                .containsEntry("tables", List.of("LEASING.CREDIT"));
        assertThat(root.children().get(1).children()).isNull(); // derinlik 1: tembel

        CallTreeNode sum = queries.callTree("ExposureService.sum",
                new CallTreeOptions(3, false, false, true, List.of(), List.of()));
        assertThat(sum.children()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("ExposureService.sum");
            assertThat(c.recursive()).isTrue();
            assertThat(c.children()).isNull();
        });
    }

    /** Ekran → istekleri (component'ler atlanır) → BFF endpoint'i → handler. */
    @Test
    void callTreeFromScreen() {
        CallTreeNode screen = queries.callTree("/credit-application",
                new CallTreeOptions(3, false, false, true, List.of(), List.of()));
        assertThat(screen.kind()).isEqualTo("SCREEN");
        assertThat(screen.children())
                .extracting(CallTreeNode::kind, CallTreeNode::name, c -> c.call().get("component"), c -> c.call().get("hook"))
                .containsExactly(
                        tuple("API_CALL", "getCreditApplication", "CreditApplicationScreen", "useQuery"),
                        tuple("API_CALL", "createCreditApplication", "ApplicationForm", "useMutation"),
                        tuple("API_CALL", "getScore", "ScoreBadge", "useScore > useQuery"));
        CallTreeNode create = screen.children().get(1);
        assertThat(create.details()).containsEntry("httpMethod", "POST").containsEntry("targetService", "leasing-credit-bff");
        assertThat(create.children()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.kind()).isEqualTo("ENDPOINT");
            assertThat(endpoint.service()).isEqualTo("leasing-credit-bff");
            assertThat(endpoint.children()).singleElement().satisfies(h -> assertThat(h.kind()).isEqualTo("METHOD"));
        });

        CallTreeNode customer = queries.callTree("/customers/:customerId", CallTreeOptions.defaults());
        assertThat(customer.children()).filteredOn(c -> c.name().equals("getScore")).singleElement()
                .satisfies(c -> assertThat(c.call().get("components")).isEqualTo(List.of("CustomerDetailScreen", "ScoreBadge")));
    }

    @Test
    void callTreeCrossesServicesThroughFeign() {
        CallTreeNode feign = queries.callTree("CreditApiClient.checkLimit",
                new CallTreeOptions(3, false, false, true, List.of(), List.of()));
        assertThat(feign.kind()).isEqualTo("FEIGN_METHOD");
        assertThat(feign.details()).containsEntry("targetService", "credit-api");
        CallTreeNode endpoint = feign.children().get(0);
        assertThat(endpoint.kind()).isEqualTo("ENDPOINT");
        assertThat(endpoint.children()).singleElement()
                .satisfies(h -> assertThat(h.name()).isEqualTo("CreditController.checkLimit"));

        CallTreeNode noCross = queries.callTree("CreditApiClient.checkLimit",
                new CallTreeOptions(3, false, false, false, List.of(), List.of()));
        assertThat(noCross.hasChildren()).isFalse();
    }

    @Test
    void callTreeFilters() {
        CallTreeNode all = queries.callTree("ApplicationServiceImpl.createApplication", CallTreeOptions.defaults());
        CallTreeNode external = queries.callTree("ApplicationServiceImpl.createApplication",
                new CallTreeOptions(1, false, true, true, List.of(), List.of()));
        assertThat(all.children()).extracting(CallTreeNode::name).contains("ApplicationServiceImpl.merge");
        assertThat(external.children()).extracting(CallTreeNode::name)
                .containsExactly("CreditApiClient.checkLimit", "SalesApiClient.createOffer");
        assertThat(all.children()).filteredOn(c -> c.name().equals("ApplicationServiceImpl.validateRequest"))
                .singleElement().satisfies(c -> assertThat(c.visibility()).isEqualTo("PRIVATE"));

        CallTreeNode hidden = queries.callTree("ApplicationServiceImpl.createApplication",
                new CallTreeOptions(1, false, false, true, List.of("ApplicationServiceImpl"), List.of()));
        assertThat(hidden.children()).extracting(CallTreeNode::name)
                .noneMatch(n -> n.startsWith("ApplicationServiceImpl."));
    }

    @Test
    void nodeCodeAndSearch() {
        List<SearchHit> hits = queries.search("GET /applications/{id}", null, 10);
        assertThat(hits.get(0).node().label()).isEqualTo("Endpoint");
        assertThat(hits.get(0).node().name()).isEqualTo("GET /applications/{applicationId}");
        assertThat(hits.get(1).node().label()).isEqualTo("ApiCall");

        assertThat(queries.search("creditrepository", List.of("JpaRepository"), 10)).singleElement()
                .satisfies(h -> assertThat(h.score()).isEqualTo(100));
        assertThat(queries.search("credit", null, 200)).extracting(h -> h.node().label())
                .contains("Service", "Method", "Entity", "Table", "ApiCall");

        CodeBlock code = queries.getCode("GET /applications/{id}");
        assertThat(code.language()).isEqualTo("java");
        assertThat(code.name()).isEqualTo("CreditApplicationController.getApplication");
        assertThat(code.code()).contains("getApplication");
        assertThat(code.startLine()).isPositive();

        CodeBlock tsx = queries.getCode("/credit-application");
        assertThat(tsx.language()).isEqualTo("tsx");
        assertThat(tsx.file()).endsWith("CreditApplicationScreen.tsx");

        NodeDetail detail = queries.getNode(hits.get(0).node().id());
        assertThat(detail.hasCode()).isTrue();
        assertThat(detail.outgoing()).extracting(n -> n.edge().type()).contains("HANDLED_BY");
        assertThat(detail.incoming()).extracting(n -> n.edge().type()).contains("EXPOSES", "REQUESTS");
        assertThat(detail.node().properties()).doesNotContainKey("code");
    }

    @Test
    void referenceResolution() {
        assertThat(queries.resolve("credit-api").label()).isEqualTo("Service");
        assertThat(queries.resolve("customer_limit").label()).isEqualTo("Table");
        assertThat(queries.resolve("leasing-credit-bff GET /applications/{x}").name())
                .isEqualTo("GET /applications/{applicationId}");
        assertThat(queries.resolve("ScoreService#summary").name()).isEqualTo("ScoreService.summary");
        String id = queries.resolve("offers").id();
        assertThat(queries.resolve(id).name()).isEqualTo("offers");
        assertThatThrownBy(() -> queries.resolve("LimitCheckRequest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leasing-credit-bff").hasMessageContaining("credit-api");
        assertThatThrownBy(() -> queries.resolve("Nope")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> queries.resolve("table_0000000000000000")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void analysisRuns() {
        AnalysisRunView latest = queries.analysisRun("latest");
        assertThat(latest.state()).isEqualTo("SUCCEEDED");
        assertThat(latest.report()).isNotNull();
        assertThat(latest.report().unresolvedLinks()).hasSize(3);
        assertThat(queries.analysisRun(latest.runId()).runId()).isEqualTo(latest.runId());
        assertThat(queries.listAnalysisRuns(10)).extracting(AnalysisRunView::runId).contains(latest.runId());
        assertThat(queries.listAnalysisRuns(10)).allMatch(r -> r.report() == null);
        assertThatThrownBy(() -> queries.analysisRun("run-1")).isInstanceOf(NotFoundException.class);
    }
}

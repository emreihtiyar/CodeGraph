package me.emreihtiyar.codegraph.extract.ts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.extract.model.ApiCallFact;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import org.junit.jupiter.api.Test;

/** Fixture dışı ApiCalls kalıpları: sınıflar, bilinmeyen istemciler ve teşhis mesajları. */
class ApiCallExtractorTest {

    private static JsModule module(String path, String source) {
        return new JsModule(path, source, JsLowering.program(TsSourceParser.parse(source, true).program()));
    }

    private final List<ParseIssue> issues = new ArrayList<>();

    private Map<String, ApiCallFact> extract(JsModule apiCalls, JsModule... others) {
        List<JsModule> all = new ArrayList<>(List.of(others));
        all.add(apiCalls);
        return new ApiCallExtractor(new JsProject(all, PathAliases.none())).extract(List.of(apiCalls), issues);
    }

    @Test
    void classMethodsAndDefaultInstanceAreApiCalls() {
        JsModule api = module("src/api/contractApiCalls.ts", """
                import { http } from '@company/http';
                class ContractApiCalls {
                  private base = '/leasing-contract-bff';
                  getContract(id: string) { return http.get(`/leasing-contract-bff/contracts/${id}`); }
                  saveContract = async (body: any) => http.post('/leasing-contract-bff/contracts', body);
                }
                export default new ContractApiCalls();
                """);

        assertThat(extract(api).values())
                .extracting(ApiCallFact::key, ApiCallFact::httpMethod, ApiCallFact::targetPrefix, ApiCallFact::normalizedPath)
                .containsExactlyInAnyOrder(
                        tuple("src/api/contractApiCalls.ts#ContractApiCalls.getContract", HttpMethod.GET,
                                "leasing-contract-bff", "/contracts/{*}"),
                        tuple("src/api/contractApiCalls.ts#ContractApiCalls.saveContract", HttpMethod.POST,
                                "leasing-contract-bff", "/contracts"));
        assertThat(issues).isEmpty();
    }

    @Test
    void componentCallOnDefaultInstanceResolvesToClassMethod() {
        JsModule api = module("src/api/contractApiCalls.ts", """
                export class ContractApiCalls {
                  static list() { return fetch('/leasing-contract-bff/contracts'); }
                }
                """);
        JsModule screen = module("src/Screen.tsx", """
                import { ContractApiCalls } from './api/contractApiCalls';
                export const load = () => ContractApiCalls.list();
                """);
        JsProject project = new JsProject(List.of(api, screen), PathAliases.none());
        Js.Node callee = ((Js.Call) ((Js.Return) ((Js.Func) ((Js.VarDecl) screen.declarations().get("load")).init())
                .body().get(0)).value()).callee();

        assertThat(project.resolve(screen, callee)).map(JsProject.Symbol::key)
                .contains("src/api/contractApiCalls.ts#ContractApiCalls.list");
    }

    @Test
    void unknownClientWithUrlLikeArgumentIsUsedAsLastResort() {
        JsModule api = module("src/api/purchaseApiCalls.ts", """
                import { callService } from '@company/core';
                export const listOrders = (q: any) => callService({ url: '/leasing-purchasing-bff/orders', method: 'POST', data: q });
                export const getOrder = (id: string) => httpGetRequest(`/leasing-purchasing-bff/orders/${id}`);
                """);

        assertThat(extract(api).values())
                .extracting(ApiCallFact::name, ApiCallFact::httpMethod, ApiCallFact::targetPrefix, ApiCallFact::normalizedPath)
                .containsExactlyInAnyOrder(
                        tuple("listOrders", HttpMethod.POST, "leasing-purchasing-bff", "/orders"),
                        tuple("getOrder", HttpMethod.GET, "leasing-purchasing-bff", "/orders/{*}"));
    }

    @Test
    void missingHttpCallIssueListsTheCallsInTheBody() {
        JsModule api = module("src/api/miscApiCalls.ts", """
                import { ServiceCaller } from '@company/core';
                export const getLimits = (req: any) => ServiceCaller.call({ serviceName: 'LEASING.NGLA.x-bff', operation: 'getLimits', req });
                """);

        assertThat(extract(api)).isEmpty();
        assertThat(issues).singleElement().extracting(ParseIssue::message).asString()
                .contains("'getLimits' içinde HTTP çağrısı bulunamadı")
                .contains("ServiceCaller.call({serviceName, operation, req})");
    }

    @Test
    void shebangLineIsIgnored() {
        assertThat(TsSourceParser.parse("#!/usr/bin/env node\nconst a = 1;\n", false).errors()).isEmpty();
    }
}

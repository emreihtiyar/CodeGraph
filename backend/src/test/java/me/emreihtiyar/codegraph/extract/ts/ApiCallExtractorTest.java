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

/** {@code callApi({ endpoint, method })} kalıbının ayrıntıları; fixture'daki genel akış FrontendRepoAnalyzerTest'te. */
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
    void featureApiCallsFile() {
        JsModule api = module("src/features/subContractQuery/api/ApiCalls.ts", """
                import { callApi } from "@/shared/api/callApi";

                export function getBaseUrl(): string {
                  return "leasing-contract-bff";
                }

                export function getSubContractQueryInitialData(): Promise<any> {
                  return callApi({
                    endpoint: getBaseUrl() + "/api/sub-contract-query/page-load",
                    method: "GET",
                    notifyErrors: false,
                  });
                }

                export function getContractStatusesByMainStatuses(
                  mainContractStatuses?: string[],
                ): Promise<{ contractStatuses: SelectOption[] }> {
                  return callApi({
                    endpoint:
                      getBaseUrl() +
                      "/api/sub-contract-query/contract-statuses",
                    method: "POST",
                    notifyErrors: false,
                    body: {
                      mainContractStatuses: mainContractStatuses ?? [],
                    },
                  });
                }

                export function getSubContractViewPaymentPlan(subContractId: number): Promise<any> {
                  return callApi({
                    endpoint:
                      getBaseUrl() + `/api/sub-contract-view/${subContractId}/payment-plan`,
                    method: "GET",
                    notifyErrors: false,
                  });
                }

                export function approveSubContract(id: number, description: string) {
                  const text = encodeURIComponent(description);
                  return callApi({ endpoint: getBaseUrl() + `/api/sub-contract/${id}/approve?description=${text}`, method: "PUT" });
                }
                """);

        assertThat(extract(api).values())
                .extracting(ApiCallFact::name, ApiCallFact::httpMethod, ApiCallFact::targetPrefix, ApiCallFact::normalizedPath)
                .containsExactly(
                        tuple("getSubContractQueryInitialData", HttpMethod.GET, "leasing-contract-bff",
                                "/api/sub-contract-query/page-load"),
                        tuple("getContractStatusesByMainStatuses", HttpMethod.POST, "leasing-contract-bff",
                                "/api/sub-contract-query/contract-statuses"),
                        tuple("getSubContractViewPaymentPlan", HttpMethod.GET, "leasing-contract-bff",
                                "/api/sub-contract-view/{*}/payment-plan"),
                        tuple("approveSubContract", HttpMethod.PUT, "leasing-contract-bff", "/api/sub-contract/{*}/approve"));
        assertThat(issues).isEmpty();
    }

    @Test
    void baseUrlFromAnotherModuleAndMethodVariants() {
        JsModule constants = module("src/shared/constants.ts", """
                export const CONTRACT_BFF = "/leasing-contract-bff";
                export enum HttpMethod { GET = "GET", POST = "POST" }
                """);
        JsModule api = module("src/features/x/api/XApiCalls.ts", """
                import { CONTRACT_BFF, HttpMethod } from "../../../shared/constants";
                const LIST = CONTRACT_BFF + "/api/contracts";

                export const listContracts = () => callApi({ endpoint: LIST });
                export const saveContract = (body: any) => callApi({ endpoint: LIST, method: HttpMethod.POST, body });
                export const send = (method: string) => callApi({ endpoint: `${CONTRACT_BFF}/api/send`, method });
                """);

        assertThat(extract(api, constants).values())
                .extracting(ApiCallFact::name, ApiCallFact::httpMethod, ApiCallFact::targetPrefix, ApiCallFact::normalizedPath)
                .containsExactly(
                        tuple("listContracts", HttpMethod.GET, "leasing-contract-bff", "/api/contracts"),
                        tuple("saveContract", HttpMethod.POST, "leasing-contract-bff", "/api/contracts"),
                        tuple("send", HttpMethod.ANY, "leasing-contract-bff", "/api/send"));
    }

    @Test
    void onlyCallApiCountsAndMissingEndpointIsReported() {
        JsModule api = module("src/features/x/api/ApiCalls.ts", """
                export const viaFetch = () => fetch("/leasing-contract-bff/api/x");
                export const viaAxios = () => axios.get("/leasing-contract-bff/api/y");
                export const withOptions = (options: any) => callApi(options);
                """);

        assertThat(extract(api)).isEmpty();
        assertThat(issues).singleElement().extracting(ParseIssue::message).asString()
                .contains("'withOptions'").contains("'endpoint' bulunamadı");
    }
}

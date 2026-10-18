package me.emreihtiyar.codegraph.extract.ts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.emreihtiyar.codegraph.extract.model.RenderFact;
import me.emreihtiyar.codegraph.extract.ts.ComponentExtractor.Component;
import org.junit.jupiter.api.Test;

/** RENDERS ayrıntıları; fixture'daki genel akış FrontendRepoAnalyzerTest'te. */
class ComponentExtractorTest {

    private static JsModule module(String path, String source) {
        return new JsModule(path, source, JsLowering.program(TsSourceParser.parse(source, true).program()));
    }

    /** component anahtarı → render ettiği component adları */
    private static Map<String, List<String>> renders(JsModule... modules) {
        JsProject project = new JsProject(List.of(modules), PathAliases.none());
        ComponentExtractor extractor = new ComponentExtractor(project, Set.of("src/api/ApiCalls.ts#saveX"));
        extractor.findComponents();
        Map<String, String> names = extractor.components().stream()
                .collect(Collectors.toMap(Component::key, Component::name));
        return extractor.components().stream().collect(Collectors.toMap(Component::name, c -> extractor.renders(c)
                .stream().map(RenderFact::childComponentKey).map(names::get).toList()));
    }

    /** Sekmeler yapılandırma dizisinden {@code <item.component/>} ile render ediliyor; sekmedeki modal da bulunur. */
    @Test
    void tabsFromConfigArrayAndNestedModals() {
        JsModule page = module("src/features/view/ViewPage.tsx", """
                import DetailsTab from "./tabs/DetailsTab";
                import { PaymentPlanTab } from "./tabs/PaymentPlanTab";

                const LazyTab = lazy(() => import("./tabs/LazyTab"));

                const tabItems = [
                  { key: "DETAILS", title: "Detay", visible: true, component: DetailsTab },
                  { key: "PLAN", title: "Plan", visible: true, component: PaymentPlanTab },
                  { key: "LAZY", title: "Tembel", visible: false, component: LazyTab },
                ];

                const ViewPage = (props) => (
                  <Tabs type="card">
                    {tabItems
                      .filter((t) => t.visible)
                      .map((item) => (
                        <Tabs.TabPane tab={item.title} key={item.key}>
                          <item.component recordId={props.id} {...props} />
                        </Tabs.TabPane>
                      ))}
                  </Tabs>
                );

                export default ViewPage;
                """);
        JsModule details = module("src/features/view/tabs/DetailsTab.tsx", """
                import UpdateModal from "../modals/UpdateModal";

                const DetailsTab = ({ recordId }) => {
                  const [visible, setVisible] = useState(false);
                  return (
                    <>
                      <Form />
                      {visible && (
                        <UpdateModal subContractId={Number(recordId)} visible={visible} setVisible={setVisible} />
                      )}
                    </>
                  );
                };

                export default DetailsTab;
                """);
        JsModule plan = module("src/features/view/tabs/PaymentPlanTab.tsx", """
                import { getColumns } from "./columns";
                export function PaymentPlanTab() {
                  return <Table columns={getColumns()} />;
                }
                """);
        JsModule columns = module("src/features/view/tabs/columns.tsx", """
                import { StatusTag } from "../../../shared/StatusTag";
                export const getColumns = () => [
                  { title: "Durum", render: (v) => <StatusTag value={v} /> },
                ];
                """);
        JsModule lazy = module("src/features/view/tabs/LazyTab.tsx", "export default function LazyTab() { return <div/>; }");
        JsModule modal = module("src/features/view/modals/UpdateModal.tsx", """
                export default function UpdateModal() { return <Modal />; }
                """);
        JsModule tag = module("src/shared/StatusTag.tsx", "export const StatusTag = () => <span/>;");

        Map<String, List<String>> renders = renders(page, details, plan, columns, lazy, modal, tag);

        assertThat(renders.get("ViewPage")).containsExactly("DetailsTab", "PaymentPlanTab", "LazyTab");
        assertThat(renders.get("DetailsTab")).containsExactly("UpdateModal");
        assertThat(renders.get("PaymentPlanTab")).containsExactly("StatusTag");
    }

    /** Route listesinden menü kuran component sayfaları render etmiyor; ApiCall gövdesine de girilmiyor. */
    @Test
    void routeArraysAndApiCallsAreNotFollowed() {
        JsModule routes = module("src/routes.ts", """
                import OrdersPage from "./OrdersPage";
                export const routes = [{ path: "/orders", title: "Siparişler", component: OrdersPage }];
                """);
        JsModule menu = module("src/Menu.tsx", """
                import { routes } from "./routes";
                import { saveX } from "./api/ApiCalls";
                export function Menu() {
                  return <ul>{routes.map((r) => <li key={r.path} onClick={() => saveX()}>{r.title}</li>)}</ul>;
                }
                """);
        JsModule api = module("src/api/ApiCalls.ts", """
                import { ErrorToast } from "../ErrorToast";
                export const saveX = () => callApi({ endpoint: "x", method: "POST", onError: ErrorToast });
                """);
        JsModule orders = module("src/OrdersPage.tsx", "export default function OrdersPage() { return <div/>; }");
        JsModule toast = module("src/ErrorToast.tsx", "export const ErrorToast = () => <div/>;");

        assertThat(renders(routes, menu, api, orders, toast).get("Menu")).isEmpty();
    }
}

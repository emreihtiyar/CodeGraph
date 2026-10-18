package me.emreihtiyar.codegraph.extract.ts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import me.emreihtiyar.codegraph.extract.model.ScreenFact;
import org.junit.jupiter.api.Test;

/** Route dizilerinin ve JSX route'larının ayrıntıları; fixture'daki genel akış FrontendRepoAnalyzerTest'te. */
class RouteExtractorTest {

    private static JsModule module(String path, String source) {
        return new JsModule(path, source, JsLowering.program(TsSourceParser.parse(source, true).program()));
    }

    private final List<ParseIssue> issues = new ArrayList<>();

    private List<ScreenFact> extract(JsModule... modules) {
        JsProject project = new JsProject(List.of(modules), PathAliases.none());
        ComponentExtractor components = new ComponentExtractor(project, Set.of());
        components.findComponents();
        return new RouteExtractor(project, components).extract(issues);
    }

    /** Ekran adı import edilen modülün adıdır; varsayılan export sarmalanmışsa component içteki olur. */
    @Test
    void routesArrayWithAsyncComponent() {
        JsModule routes = module("src/routes.ts", """
                import { asyncComponent } from "./shared/asyncComponent";
                const SubContractView = asyncComponent(() => import("./features/subContractView/SubContractViewPage"));

                const routes = [
                  {
                    path: "/seller-contribution",
                    processId: 6013,
                    component: asyncComponent(() =>
                      import("./features/sellerContribution/SellerContributionPage")
                    ),
                    i18n: i18nSellerContribution,
                  },
                  { path: "/sub-contract-view", processId: 6015, component: SubContractView },
                  { path: "/missing", component: asyncComponent(() => import("./features/missing/MissingPage")) },
                ];

                export default routes;
                """);
        JsModule seller = module("src/features/sellerContribution/SellerContributionPage.tsx", """
                const SellerContributionContent = (_props: PageProps) => {
                  return <div>Satıcı katkısı</div>;
                };

                export default withPage(SellerContributionContent);
                """);
        JsModule view = module("src/features/subContractView/SubContractViewPage.tsx", """
                export default function SubContractViewPage() {
                  return <div/>;
                }
                """);

        assertThat(extract(routes, seller, view))
                .extracting(ScreenFact::routePath, ScreenFact::name, ScreenFact::componentKey)
                .containsExactly(
                        tuple("/seller-contribution", "SellerContributionPage",
                                "src/features/sellerContribution/SellerContributionPage.tsx#SellerContributionContent"),
                        tuple("/sub-contract-view", "SubContractViewPage",
                                "src/features/subContractView/SubContractViewPage.tsx#SubContractViewPage"));
        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.file()).isEqualTo("src/routes.ts");
            assertThat(i.message()).contains("'/missing'").contains("./features/missing/MissingPage");
        });
    }

    /** Path sabitten çözülür; çözülemiyorsa ekran yine çıkar, path ifadenin adıyla yazılır. */
    @Test
    void pathFromConstantsOrUnknown() {
        JsModule paths = module("src/paths.ts", """
                export const RoutePaths = { SELLER: "/seller-contribution" };
                """);
        JsModule routes = module("src/routes.ts", """
                import { RoutePaths } from "./paths";
                export default [
                  { path: RoutePaths.SELLER, component: asyncComponent(() => import("./Seller")) },
                  { path: config.routes.other, component: asyncComponent(() => import("./Other")) },
                  { path: buildPath("x"), component: asyncComponent(() => import("./Third")) },
                ];
                """);
        JsModule seller = module("src/Seller.tsx", "export default function Seller() { return <div/>; }");
        JsModule other = module("src/Other.tsx", "export default function Other() { return <div/>; }");
        JsModule third = module("src/Third.tsx", "export default function Third() { return <div/>; }");

        assertThat(extract(paths, routes, seller, other, third))
                .extracting(ScreenFact::routePath, ScreenFact::name)
                .containsExactly(tuple("/seller-contribution", "Seller"), tuple("/{config.routes.other}", "Other"),
                        tuple("/{?}", "Third"));
    }

    @Test
    void reactRouterObjectsWithChildrenAndLazy() {
        JsModule app = module("src/App.tsx", """
                import { createBrowserRouter } from "react-router-dom";

                const adminRoutes = [
                  { path: "users", lazy: () => import("./Users") },
                ];

                export const router = createBrowserRouter([
                  {
                    path: "/",
                    element: <Layout />,
                    children: [
                      { index: true, element: <Suspense><Home /></Suspense> },
                      { path: "admin", children: adminRoutes },
                      { path: "*", element: <Navigate to="/" /> },
                    ],
                  },
                ]);

                function Layout() { return <Outlet />; }
                function Home() { return <h1>Ana sayfa</h1>; }
                """);
        JsModule users = module("src/Users.tsx", """
                export function Component() { return <table/>; }
                """);

        assertThat(extract(app, users))
                .extracting(ScreenFact::routePath, ScreenFact::name)
                .containsExactly(tuple("/", "Layout"), tuple("/", "Home"), tuple("/admin/users", "Users"));
        assertThat(issues).isEmpty();
    }

    @Test
    void jsxRoutesAndNonRouteArrays() {
        JsModule app = module("src/App.tsx", """
                const menu = [{ path: "/orders", title: "Siparişler", icon: <Icon /> }];

                export default function App() {
                  return (
                    <Routes>
                      <Route path="/orders" element={<Orders />}>
                        <Route path=":id" Component={OrderDetail} />
                      </Route>
                    </Routes>
                  );
                }

                function Orders() { return <div/>; }
                function OrderDetail() { return <div/>; }
                """);

        assertThat(extract(app))
                .extracting(ScreenFact::routePath, ScreenFact::name)
                .containsExactly(tuple("/orders", "Orders"), tuple("/orders/:id", "OrderDetail"));
        assertThat(issues).isEmpty();
    }
}

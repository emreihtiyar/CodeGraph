package me.emreihtiyar.codegraph.extract.ts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import me.emreihtiyar.codegraph.extract.model.ScreenFact;
import me.emreihtiyar.codegraph.extract.ts.ComponentExtractor.Component;
import me.emreihtiyar.codegraph.extract.ts.Js.Arr;
import me.emreihtiyar.codegraph.extract.ts.Js.Ident;
import me.emreihtiyar.codegraph.extract.ts.Js.Jsx;
import me.emreihtiyar.codegraph.extract.ts.Js.JsxAttr;
import me.emreihtiyar.codegraph.extract.ts.Js.Literal;
import me.emreihtiyar.codegraph.extract.ts.Js.Node;
import me.emreihtiyar.codegraph.extract.ts.Js.Obj;
import me.emreihtiyar.codegraph.extract.ts.Js.Prop;
import me.emreihtiyar.codegraph.extract.ts.Js.Spread;
import me.emreihtiyar.codegraph.extract.ts.UrlEvaluator.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Route tanımlarından ekranlar (PRD FR-08).
 *
 * <p>Route dizisi, elemanlarından biri {@code path} (ya da {@code index}) ile birlikte component anahtarı
 * ({@code component}, {@code element}, {@code Component}, {@code lazy}) veya {@code children} içeren nesne literali
 * olan dizidir. Dizinin nerede tanımlandığı ya da kime verildiği önemsizdir ({@code export default routes},
 * {@code createBrowserRouter([...])}):
 *
 * <pre>
 * const routes = [
 *   {
 *     path: "/seller-contribution",
 *     processId: 6013,
 *     component: asyncComponent(() => import("./features/sellerContribution/SellerContributionPage")),
 *   },
 * ];
 * </pre>
 *
 * Component değeri dinamik import'u saran bir çağrı ({@code asyncComponent(() => import('./X'))},
 * {@code lazy(...)}), dinamik import'un kendisi ({@code lazy: () => import('./X')}), JSX ({@code <X/>};
 * {@code <Suspense><X/></Suspense>} gibi sarmalayıcılarda en içteki proje component'i) ya da referans ({@code X})
 * olabilir. Dinamik import'ta modülün {@code Component} export'u, yoksa varsayılan export'u alınır. JSX
 * {@code <Route>} ağaçları da desteklenir. Path'i olmayan (yalnızca layout) route'lar ekran değildir; index route'u
 * üst route'un path'ini alır.
 */
final class RouteExtractor {

    private static final Logger log = LoggerFactory.getLogger(RouteExtractor.class);

    /** Bir nesnede birden fazlası varsa ilki geçerlidir. */
    private static final List<String> COMPONENT_KEYS = List.of("element", "Component", "component", "lazy");

    private record RouteArray(Arr arr, JsModule module) {}

    private final JsProject project;
    private final ComponentExtractor components;
    private final UrlEvaluator evaluator;
    private final Map<String, ScreenFact> screens = new LinkedHashMap<>();
    private List<ParseIssue> issues;

    RouteExtractor(JsProject project, ComponentExtractor components) {
        this.project = project;
        this.components = components;
        this.evaluator = new UrlEvaluator(project);
    }

    /** @param issues component'i çözülemeyen route'lar buraya eklenir */
    List<ScreenFact> extract(List<ParseIssue> issues) {
        this.issues = issues;
        List<RouteArray> arrays = new ArrayList<>();
        for (JsModule module : project.modules()) {
            module.body().forEach(n -> scan(n, module, arrays));
        }
        // children: adminRoutes gibi ayrı tanımlı alt diziler kökten değil, üst route'un path'iyle işlenir.
        Set<Arr> nested = Collections.newSetFromMap(new IdentityHashMap<>());
        arrays.forEach(a -> markChildren(a.arr(), a.module(), nested, 0));
        for (RouteArray a : arrays) {
            if (!nested.contains(a.arr())) {
                log.debug("{}:{} route dizisi", a.module().path(), a.arr().line());
                routes(a.arr(), a.module(), "", 0);
            }
        }
        return new ArrayList<>(screens.values());
    }

    private void scan(Node node, JsModule module, List<RouteArray> arrays) {
        if (node instanceof Arr arr && isRouteArray(arr)) {
            arrays.add(new RouteArray(arr, module));
            return; // iç içe children dizileri üst route'tan işlenir
        }
        if (node instanceof Jsx j && isRoute(j)) {
            jsxRoute(j, module, "");
            return;
        }
        Js.forEachChild(node, child -> scan(child, module, arrays));
    }

    private static boolean isRouteArray(Arr arr) {
        for (Node element : arr.elements()) {
            if (element instanceof Obj o && (prop(o, "path").isPresent() || prop(o, "index").isPresent())
                    && (COMPONENT_KEYS.stream().anyMatch(k -> prop(o, k).isPresent()) || prop(o, "children").isPresent())) {
                return true;
            }
        }
        return false;
    }

    // ---- nesne route'ları ----

    private void routes(Node node, JsModule module, String parentPath, int depth) {
        if (depth > 10) {
            return;
        }
        Optional<RouteArray> array = array(node, module);
        if (array.isEmpty()) {
            return;
        }
        for (Node element : array.get().arr().elements()) {
            JsModule m = array.get().module();
            if (element instanceof Obj route) {
                objectRoute(route, m, parentPath, depth);
            } else if (element instanceof Ident i) {
                project.resolveName(m, i.name())
                        .filter(s -> s.value() instanceof Obj)
                        .ifPresent(s -> objectRoute((Obj) s.value(), s.module(), parentPath, depth + 1));
            } else if (element instanceof Spread s) {
                routes(s.expr(), m, parentPath, depth + 1);
            }
        }
    }

    /** Dizi literali ya da diziyle tanımlı sabit. */
    private Optional<RouteArray> array(Node node, JsModule module) {
        if (node instanceof Arr arr) {
            return Optional.of(new RouteArray(arr, module));
        }
        if (node instanceof Ident i) {
            return project.resolveName(module, i.name())
                    .filter(s -> s.value() instanceof Arr)
                    .map(s -> new RouteArray((Arr) s.value(), s.module()));
        }
        return Optional.empty();
    }

    private void markChildren(Arr arr, JsModule module, Set<Arr> nested, int depth) {
        if (depth > 10) {
            return;
        }
        for (Node element : arr.elements()) {
            if (element instanceof Obj route) {
                prop(route, "children").flatMap(c -> array(c, module)).ifPresent(c -> {
                    if (nested.add(c.arr())) {
                        markChildren(c.arr(), c.module(), nested, depth + 1);
                    }
                    c.arr().elements().stream()
                            .filter(e -> e instanceof Spread)
                            .flatMap(e -> array(((Spread) e).expr(), c.module()).stream())
                            .forEach(s -> {
                                if (nested.add(s.arr())) {
                                    markChildren(s.arr(), s.module(), nested, depth + 1);
                                }
                            });
                });
            }
        }
    }

    private void objectRoute(Obj route, JsModule module, String parentPath, int depth) {
        String path = prop(route, "path").map(p -> path(p, module, route.line())).orElse(null);
        boolean index = prop(route, "index").filter(RouteExtractor::isTrue).isPresent();
        String fullPath = path == null ? parentPath : join(parentPath, path);

        Optional<Node> value = COMPONENT_KEYS.stream().flatMap(k -> prop(route, k).stream()).findFirst();
        if ((path != null || index) && value.isPresent()) {
            screen(fullPath, value.get(), module, route.line());
        }
        prop(route, "children").ifPresent(c -> routes(c, module, fullPath, depth + 1));
    }

    /**
     * Route path'inin değeri. Statik olarak bilinemiyorsa ({@code path: getPath(x)}) ekran kaybolmasın diye ifadenin
     * adı süslü parantezle yazılır: {@code {RoutePaths.SELLER}}.
     */
    private String path(Node node, JsModule module, int line) {
        Optional<String> value = evaluator.evalString(node, new Scope(module));
        if (value.isPresent()) {
            return value.get();
        }
        String name = Js.dottedName(node);
        log.debug("{}:{} route path'i statik olarak bilinemiyor ({})", module.path(), line, name);
        return "{" + (name != null ? name : "?") + "}";
    }

    private static Optional<Node> prop(Obj obj, String key) {
        for (Prop p : obj.props()) {
            if (key.equals(p.key())) {
                return Optional.ofNullable(p.value());
            }
        }
        return Optional.empty();
    }

    /** {@code true} ayrıştırıcıda tanımlayıcı olarak iner. */
    private static boolean isTrue(Node node) {
        return node instanceof Ident i && i.name().equals("true") || node instanceof Literal l && l.text().equals("true");
    }

    // ---- JSX route'ları ----

    private static boolean isRoute(Jsx j) {
        return j.name() != null && (j.name().equals("Route") || j.name().endsWith(".Route"));
    }

    private void jsxRoute(Jsx route, JsModule module, String parentPath) {
        String path = null;
        boolean index = false;
        Node value = null;
        for (JsxAttr attr : route.attrs()) {
            if (attr.name() == null) {
                continue;
            }
            switch (attr.name()) {
                case "path" -> path = attr.value() == null ? null : path(attr.value(), module, route.line());
                case "index" -> index = attr.value() == null || isTrue(attr.value());
                case "element", "Component", "component" -> value = attr.value() == null ? value : attr.value();
                default -> { }
            }
        }
        String fullPath = path == null ? parentPath : join(parentPath, path);
        if ((path != null || index) && value != null) {
            screen(fullPath, value, module, route.line());
        }
        for (Node child : route.children()) {
            nestedRoutes(child, module, fullPath);
        }
    }

    private void nestedRoutes(Node node, JsModule module, String parentPath) {
        if (node instanceof Jsx j && isRoute(j)) {
            jsxRoute(j, module, parentPath);
            return;
        }
        Js.forEachChild(node, child -> nestedRoutes(child, module, parentPath));
    }

    // ---- component çözümü ----

    /**
     * Route değerinin component'i. Dinamik import'ta ekran adı modülün adıdır ({@code SellerContributionPage});
     * varsayılan export {@code withPage(SellerContributionContent)} gibi sarmalanmış olsa da.
     * Bulunamazsa: dinamik import'ta (proje modülü olduğu kesin) sorun kaydedilir; JSX ve referanslarda
     * ({@code <Navigate/>}, kütüphane component'leri) yalnızca DEBUG logu yazılır.
     */
    private void screen(String path, Node value, JsModule module, int line) {
        String specifier = JsProject.dynamicImport(value);
        if (specifier == null) {
            specifier = JsProject.wrappedImport(value);
        }
        if (specifier == null) {
            Optional<Component> component = value instanceof Jsx jsx
                    ? innermost(jsx, module)
                    : project.resolve(module, value).flatMap(components::componentOf);
            if (component.isPresent()) {
                add(path, component.get().name(), component.get());
            } else {
                log.debug("{}:{} route '{}' proje component'ine çözülemedi", module.path(), line, path);
            }
            return;
        }
        Optional<JsModule> target = project.resolveModule(module, specifier);
        Optional<Component> component = target.flatMap(t -> project.resolveExport(t, "Component")
                .flatMap(components::componentOf)
                .or(() -> project.resolveExport(t, JsModule.DEFAULT).flatMap(components::componentOf)));
        if (component.isPresent()) {
            add(path, target.get().baseName(), component.get());
            return;
        }
        String reason = target.isEmpty()
                ? "import edilen modül projede yok ('" + specifier + "')"
                : "'" + specifier + "' modülünün varsayılan export'u component değil";
        issues.add(new ParseIssue(module.path(), line, "Route '" + path + "': component bulunamadı; " + reason));
        log.debug("{}:{} route '{}' component'i bulunamadı: {}", module.path(), line, path, reason);
    }

    private void add(String path, String name, Component component) {
        screens.putIfAbsent(path + "|" + component.key(), new ScreenFact(path, name, component.key()));
    }

    /** JSX ağacındaki en içteki proje component'i; çocuklarda yoksa kökün kendisi. */
    private Optional<Component> innermost(Jsx jsx, JsModule module) {
        for (Node child : jsx.children()) {
            Optional<Component> inner = Optional.empty();
            if (child instanceof Jsx j) {
                inner = innermost(j, module);
            }
            if (inner.isPresent()) {
                return inner;
            }
        }
        return components.componentOfTag(module, jsx.name(), jsx.line());
    }

    /** React Router path birleştirme: mutlak alt path üst path'i ezer. */
    static String join(String parent, String child) {
        if (child.startsWith("/")) {
            return child;
        }
        String base = parent.isEmpty() || parent.equals("/") ? "" : parent;
        return child.isEmpty() ? (base.isEmpty() ? "/" : base) : base + "/" + child;
    }
}

package me.emreihtiyar.codegraph.extract.ts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.emreihtiyar.codegraph.extract.model.ScreenFact;
import me.emreihtiyar.codegraph.extract.ts.ComponentExtractor.Component;
import me.emreihtiyar.codegraph.extract.ts.Js.Arr;
import me.emreihtiyar.codegraph.extract.ts.Js.Call;
import me.emreihtiyar.codegraph.extract.ts.Js.Ident;
import me.emreihtiyar.codegraph.extract.ts.Js.Jsx;
import me.emreihtiyar.codegraph.extract.ts.Js.JsxAttr;
import me.emreihtiyar.codegraph.extract.ts.Js.Literal;
import me.emreihtiyar.codegraph.extract.ts.Js.Node;
import me.emreihtiyar.codegraph.extract.ts.Js.Obj;
import me.emreihtiyar.codegraph.extract.ts.Js.Prop;
import me.emreihtiyar.codegraph.extract.ts.JsProject.Symbol;
import me.emreihtiyar.codegraph.extract.ts.UrlEvaluator.Scope;

/**
 * Route tanımlarından ekranlar (PRD FR-08): React Router nesne route'ları ({@code createBrowserRouter},
 * {@code createHashRouter}, {@code createMemoryRouter}, {@code useRoutes}; {@code path}, {@code index},
 * {@code element}, {@code Component}, {@code lazy}, iç içe {@code children}) ve JSX {@code <Route>} ağaçları.
 *
 * <p>{@code element} birden çok component içeriyorsa ({@code <Suspense><X/></Suspense>},
 * {@code <RequireAuth><X/></RequireAuth>}) en içteki proje component'i ekran kabul edilir. Path'i olmayan
 * (yalnızca layout) route'lar ekran değildir; index route'u üst route'un path'ini alır.
 */
final class RouteExtractor {

    private static final Set<String> ROUTER_FACTORIES =
            Set.of("createBrowserRouter", "createHashRouter", "createMemoryRouter", "useRoutes");

    private final JsProject project;
    private final ComponentExtractor components;
    private final UrlEvaluator evaluator;
    private final Map<String, ScreenFact> screens = new LinkedHashMap<>();

    RouteExtractor(JsProject project, ComponentExtractor components) {
        this.project = project;
        this.components = components;
        this.evaluator = new UrlEvaluator(project);
    }

    List<ScreenFact> extract() {
        for (JsModule module : project.modules()) {
            module.body().forEach(n -> scan(n, module));
        }
        return new ArrayList<>(screens.values());
    }

    private void scan(Node node, JsModule module) {
        if (node instanceof Call call && Js.calleeName(call) != null && ROUTER_FACTORIES.contains(Js.calleeName(call))
                && !call.args().isEmpty()) {
            routes(call.args().get(0), module, "", 0);
        }
        if (node instanceof Jsx j && isRoute(j)) {
            jsxRoute(j, module, "");
            return;
        }
        Js.forEachChild(node, child -> scan(child, module));
    }

    // ---- nesne route'ları ----

    private void routes(Node node, JsModule module, String parentPath, int depth) {
        if (depth > 10) {
            return;
        }
        if (node instanceof Ident i) {
            Optional<Symbol> symbol = project.resolveName(module, i.name());
            if (symbol.isPresent() && symbol.get().value() != null) {
                routes(symbol.get().value(), symbol.get().module(), parentPath, depth + 1);
            }
            return;
        }
        if (!(node instanceof Arr arr)) {
            return;
        }
        for (Node element : arr.elements()) {
            if (element instanceof Obj route) {
                objectRoute(route, module, parentPath, depth);
            } else if (element instanceof Ident i) {
                project.resolveName(module, i.name())
                        .filter(s -> s.value() instanceof Obj)
                        .ifPresent(s -> objectRoute((Obj) s.value(), s.module(), parentPath, depth + 1));
            } else if (element instanceof Js.Spread s) {
                routes(s.expr(), module, parentPath, depth + 1);
            }
        }
    }

    private void objectRoute(Obj route, JsModule module, String parentPath, int depth) {
        Scope scope = new Scope(module);
        String path = prop(route, "path").flatMap(p -> evaluator.evalString(p, scope)).orElse(null);
        boolean index = prop(route, "index").filter(v -> v instanceof Literal l && l.text().equals("true")).isPresent();
        String fullPath = path == null ? parentPath : join(parentPath, path);

        Optional<Component> component = prop(route, "element")
                .filter(e -> e instanceof Jsx)
                .flatMap(e -> innermost((Jsx) e, module));
        if (component.isEmpty()) {
            component = prop(route, "Component")
                    .flatMap(c -> project.resolve(module, c))
                    .flatMap(components::componentOf);
        }
        if (component.isEmpty()) {
            component = prop(route, "lazy").flatMap(l -> lazyComponent(l, module));
        }
        if ((path != null || index) && component.isPresent()) {
            screen(fullPath, component.get());
        }
        prop(route, "children").ifPresent(c -> routes(c, module, fullPath, depth + 1));
    }

    /** {@code lazy: () => import('./X')} — modülün {@code Component} veya varsayılan export'u. */
    private Optional<Component> lazyComponent(Node lazy, JsModule module) {
        String specifier = JsProject.dynamicImport(lazy);
        Optional<JsModule> target = project.resolveModule(module, specifier);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        return project.resolveExport(target.get(), "Component")
                .or(() -> project.resolveExport(target.get(), JsModule.DEFAULT))
                .flatMap(components::componentOf);
    }

    private static Optional<Node> prop(Obj obj, String key) {
        for (Prop p : obj.props()) {
            if (key.equals(p.key())) {
                return Optional.ofNullable(p.value());
            }
        }
        return Optional.empty();
    }

    // ---- JSX route'ları ----

    private static boolean isRoute(Jsx j) {
        return j.name() != null && (j.name().equals("Route") || j.name().endsWith(".Route"));
    }

    private void jsxRoute(Jsx route, JsModule module, String parentPath) {
        Scope scope = new Scope(module);
        String path = null;
        boolean index = false;
        Optional<Component> component = Optional.empty();
        for (JsxAttr attr : route.attrs()) {
            if (attr.name() == null) {
                continue;
            }
            switch (attr.name()) {
                case "path" -> path = attr.value() == null ? null : evaluator.evalString(attr.value(), scope).orElse(null);
                case "index" -> index = attr.value() == null
                        || attr.value() instanceof Literal l && l.text().equals("true");
                case "element" -> {
                    if (attr.value() instanceof Jsx element) {
                        component = innermost(element, module);
                    }
                }
                case "Component", "component" -> {
                    if (attr.value() != null) {
                        component = project.resolve(module, attr.value()).flatMap(components::componentOf);
                    }
                }
                default -> { }
            }
        }
        String fullPath = path == null ? parentPath : join(parentPath, path);
        if ((path != null || index) && component.isPresent()) {
            screen(fullPath, component.get());
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

    // ---- yardımcılar ----

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

    private void screen(String path, Component component) {
        screens.putIfAbsent(path + "|" + component.key(), new ScreenFact(path, component.name(), component.key()));
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

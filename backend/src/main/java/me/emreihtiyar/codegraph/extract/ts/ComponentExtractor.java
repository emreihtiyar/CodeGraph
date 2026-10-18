package me.emreihtiyar.codegraph.extract.ts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.emreihtiyar.codegraph.extract.model.ApiCallUsageFact;
import me.emreihtiyar.codegraph.extract.model.ApiCallVia;
import me.emreihtiyar.codegraph.extract.model.ComponentFact;
import me.emreihtiyar.codegraph.extract.model.RenderFact;
import me.emreihtiyar.codegraph.extract.model.SourceRef;
import me.emreihtiyar.codegraph.extract.ts.Js.Call;
import me.emreihtiyar.codegraph.extract.ts.Js.ClassNode;
import me.emreihtiyar.codegraph.extract.ts.Js.Func;
import me.emreihtiyar.codegraph.extract.ts.Js.Ident;
import me.emreihtiyar.codegraph.extract.ts.Js.Jsx;
import me.emreihtiyar.codegraph.extract.ts.Js.Member;
import me.emreihtiyar.codegraph.extract.ts.Js.Node;
import me.emreihtiyar.codegraph.extract.ts.Js.VarDecl;
import me.emreihtiyar.codegraph.extract.ts.JsProject.Symbol;

/**
 * React component'leri, ApiCall kullanımları ve RENDERS ilişkileri (PRD FR-08).
 *
 * <p>Component: büyük harfle başlayan ve JSX döndüren üst düzey fonksiyon/ok fonksiyonu, {@code memo}/
 * {@code forwardRef} gibi sarmalayıcılara verilen satır içi fonksiyon, JSX içeren sınıf veya adsız varsayılan
 * export (adı dosyadan gelir).
 *
 * <p>ApiCall kullanımı: component gövdesinde ApiCall'a yapılan her başvuru (çağrı veya {@code mutationFn: x} gibi
 * referans). İki kullanım biçimi desteklenir:
 * <ul>
 *   <li>doğrudan çağrı, ör. olay işleyicisinde {@code const r = await searchSubContracts(req)} —
 *       {@link ApiCallVia#DIRECT};</li>
 *   <li>React Query hook'u içinden, component'te ya da projedeki özel bir hook'ta
 *       ({@code useSubContractPaymentPlan(id)} → {@code useQuery({ queryFn: () => getPaymentPlan(id) })}) —
 *       {@link ApiCallVia#REACT_QUERY}.</li>
 * </ul>
 * Özel hook'lar ({@code useX}) çağrıldıkları yerde component'e satır içi açılır; kullanımın {@code hook} alanı hook
 * zincirini tutar ({@code useSubContractPaymentPlan > useQuery}). Sıra kaynak sırasıdır.
 */
final class ComponentExtractor {

    static final Set<String> REACT_QUERY_HOOKS = Set.of(
            "useQuery", "useQueries", "useMutation", "useInfiniteQuery", "useSuspenseQuery", "useSuspenseQueries",
            "useSuspenseInfiniteQuery", "usePrefetchQuery", "usePrefetchInfiniteQuery",
            "prefetchQuery", "fetchQuery", "ensureQueryData", "prefetchInfiniteQuery", "fetchInfiniteQuery");

    /**
     * @param key  {@code dosya#bildirimAdı} (adsız varsayılan export'ta bildirim adı {@code default})
     * @param name görünen ad
     * @param impl component'in gövdesi ({@link Func} veya {@link ClassNode})
     */
    record Component(String key, String name, JsModule module, Node impl, int line, int endLine) {}

    private record Usage(String apiCallKey, ApiCallVia via, String hook, int line) {}

    private final JsProject project;
    private final Set<String> apiCallKeys;
    private final Map<String, Component> components = new LinkedHashMap<>();

    ComponentExtractor(JsProject project, Set<String> apiCallKeys) {
        this.project = project;
        this.apiCallKeys = apiCallKeys;
    }

    Collection<Component> components() {
        return components.values();
    }

    void findComponents() {
        for (JsModule module : project.modules()) {
            for (var entry : module.declarations().entrySet()) {
                String declName = entry.getKey();
                Node decl = entry.getValue();
                if (decl instanceof VarDecl v && v.target().name() == null) {
                    continue; // yapı bozma
                }
                boolean isDefault = declName.equals(JsModule.DEFAULT);
                String name = isDefault ? module.baseName() : declName;
                if (!isDefault && !Character.isUpperCase(name.charAt(0))) {
                    continue;
                }
                Node value = decl instanceof VarDecl v ? v.init() : decl;
                Node impl = implementation(value);
                if (impl != null) {
                    int line = decl instanceof VarDecl v ? v.line() : decl.line();
                    int endLine = impl instanceof Func f ? f.endLine() : ((ClassNode) impl).endLine();
                    components.put(module.path() + "#" + declName,
                            new Component(module.path() + "#" + declName, name, module, impl, line, endLine));
                }
            }
        }
    }

    /** Değer bir component gerçekleştirimi mi: JSX içeren fonksiyon/sınıf veya sarmalayıcıdaki satır içi fonksiyon. */
    private static Node implementation(Node value) {
        if (value instanceof Func f) {
            return Js.containsJsx(f) ? f : null;
        }
        if (value instanceof ClassNode c) {
            return Js.containsJsx(c) ? c : null;
        }
        if (value instanceof Call call) { // memo(() => ...), forwardRef(function X() {...}), observer(...)
            for (Node arg : call.args()) {
                Node impl = implementation(arg);
                if (impl != null) {
                    return impl;
                }
            }
        }
        return null;
    }

    /** Sembolün component'i; {@code memo(X)} veya {@code withRouter(X)} gibi sarmalanmış referanslar izlenir. */
    Optional<Component> componentOf(Symbol symbol) {
        return componentOf(symbol, 0);
    }

    private Optional<Component> componentOf(Symbol symbol, int depth) {
        Component direct = components.get(symbol.key());
        if (direct != null || depth > 3) {
            return Optional.ofNullable(direct);
        }
        if (symbol.value() instanceof Call call) {
            for (Node arg : call.args()) {
                if (arg instanceof Ident || arg instanceof Member) {
                    Optional<Component> c = project.resolve(symbol.module(), arg).flatMap(s -> componentOf(s, depth + 1));
                    if (c.isPresent()) {
                        return c;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** JSX etiket adının ({@code ScoreBadge}, {@code Ui.Badge}) component'i. */
    Optional<Component> componentOfTag(JsModule module, String tag, int line) {
        if (tag == null || tag.isEmpty() || !Character.isUpperCase(tag.charAt(0)) && !tag.contains(".")) {
            return Optional.empty();
        }
        String[] parts = tag.split("\\.");
        Node expr = new Ident(parts[0], line);
        for (int i = 1; i < parts.length; i++) {
            expr = new Member(expr, parts[i], line);
        }
        return project.resolve(module, expr).flatMap(this::componentOf);
    }

    ComponentFact fact(Component c) {
        return new ComponentFact(c.key(), c.name(), new SourceRef(c.module().path(), c.line(), c.endLine()),
                c.module().code(c.line(), c.endLine()));
    }

    // ---- ApiCall kullanımları ----

    List<ApiCallUsageFact> usages(Component component) {
        List<Usage> found = new ArrayList<>();
        walk(component.impl(), component.module(), null, null, -1, new HashSet<>(), found);
        List<ApiCallUsageFact> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Usage u : found) {
            if (seen.add(u.apiCallKey() + "|" + u.via() + "|" + u.hook())) {
                out.add(new ApiCallUsageFact(component.key(), u.apiCallKey(), out.size() + 1, u.line(), u.via(), u.hook()));
            }
        }
        return out;
    }

    /**
     * @param customHooks içinden geçilen özel hook'lar ({@code useA > useB}); yoksa null
     * @param queryHook   içinde bulunulan React Query hook'u; yoksa null
     * @param siteLine    satır içi açılan özel hook'ta, component'teki çağrı satırı; aksi halde -1
     */
    private void walk(Node node, JsModule module, String customHooks, String queryHook, int siteLine,
            Set<Func> openHooks, List<Usage> out) {
        if (node instanceof Call call) {
            String name = Js.calleeName(call);
            String argHook = name != null && REACT_QUERY_HOOKS.contains(name) ? name : queryHook;
            walk(call.callee(), module, customHooks, queryHook, siteLine, openHooks, out);
            call.args().forEach(a -> walk(a, module, customHooks, argHook, siteLine, openHooks, out));
            if (isCustomHookName(name)) {
                Optional<Symbol> symbol = project.resolve(module, call.callee());
                if (symbol.isPresent() && symbol.get().value() instanceof Func f && openHooks.add(f)) {
                    int line = siteLine < 0 ? call.line() : siteLine;
                    String chain = customHooks == null ? name : customHooks + " > " + name;
                    f.body().forEach(s -> walk(s, symbol.get().module(), chain, queryHook, line, openHooks, out));
                    openHooks.remove(f);
                }
            }
            return;
        }
        if (node instanceof Ident || node instanceof Member) {
            Optional<Symbol> symbol = project.resolve(module, node);
            if (symbol.isPresent() && apiCallKeys.contains(symbol.get().key())) {
                ApiCallVia via = queryHook != null ? ApiCallVia.REACT_QUERY : ApiCallVia.DIRECT;
                String hook = customHooks == null ? queryHook
                        : queryHook == null ? customHooks : customHooks + " > " + queryHook;
                out.add(new Usage(symbol.get().key(), via, hook, siteLine < 0 ? node.line() : siteLine));
                return;
            }
        }
        Js.forEachChild(node, child -> walk(child, module, customHooks, queryHook, siteLine, openHooks, out));
    }

    private static boolean isCustomHookName(String name) {
        return name != null && name.length() > 3 && name.startsWith("use") && Character.isUpperCase(name.charAt(3))
                && !REACT_QUERY_HOOKS.contains(name);
    }

    // ---- RENDERS ----

    List<RenderFact> renders(Component component) {
        Set<String> children = new LinkedHashSet<>();
        Js.walk(component.impl(), n -> {
            if (n instanceof Jsx j) {
                componentOfTag(component.module(), j.name(), j.line())
                        .filter(c -> !c.key().equals(component.key()))
                        .ifPresent(c -> children.add(c.key()));
            }
        });
        return children.stream().map(child -> new RenderFact(component.key(), child)).toList();
    }
}

package me.emreihtiyar.codegraph.extract.ts;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import me.emreihtiyar.codegraph.extract.ts.Js.Call;
import me.emreihtiyar.codegraph.extract.ts.Js.Func;
import me.emreihtiyar.codegraph.extract.ts.Js.Ident;
import me.emreihtiyar.codegraph.extract.ts.Js.Member;
import me.emreihtiyar.codegraph.extract.ts.Js.Node;
import me.emreihtiyar.codegraph.extract.ts.Js.Obj;
import me.emreihtiyar.codegraph.extract.ts.Js.Prop;
import me.emreihtiyar.codegraph.extract.ts.Js.Return;
import me.emreihtiyar.codegraph.extract.ts.Js.Str;
import me.emreihtiyar.codegraph.extract.ts.Js.VarDecl;
import me.emreihtiyar.codegraph.extract.ts.JsModule.ExportRef;
import me.emreihtiyar.codegraph.extract.ts.JsModule.ImportRef;

/**
 * Bir frontend reposunun modülleri ve aralarındaki sembol çözümü: göreli import'lar, tsconfig/jsconfig
 * {@code paths} takma adları, yeniden export'lar ({@code export * from}, {@code export { a as b } from}), ad alanı
 * import'ları ve dinamik import sarmalayıcıları ({@code lazy(() => import('./X'))}, {@code asyncComponent(...)}).
 */
public final class JsProject {

    private static final List<String> EXTENSIONS = List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs");

    /**
     * Çözülmüş sembol.
     *
     * @param module tanımlandığı modül
     * @param name   modüldeki bildirim adı; nesne özelliklerinde {@code nesne.özellik}, ad alanında {@code *}
     * @param node   bildirim düğümü ({@link Func}, {@link Js.ClassNode}, {@link VarDecl}) veya özellik değeri;
     *               ad alanında null
     */
    public record Symbol(JsModule module, String name, Node node) {

        public boolean isNamespace() {
            return node == null;
        }

        /** Değer ifadesi: değişken bildiriminde başlangıç değeri, aksi halde düğümün kendisi. */
        public Node value() {
            return node instanceof VarDecl v ? v.init() : node;
        }

        /** Repo içinde benzersiz anahtar: {@code dosya#ad}. */
        public String key() {
            return module.path() + "#" + name;
        }
    }

    private final Map<String, JsModule> modules = new LinkedHashMap<>();
    /** Büyük-küçük harf duyarsız yedek: Windows'ta {@code ./button} import'u {@code Button.tsx}'e de çözülür. */
    private final Map<String, JsModule> modulesIgnoreCase = new LinkedHashMap<>();
    private final PathAliases aliases;

    public JsProject(Collection<JsModule> modules, PathAliases aliases) {
        modules.forEach(m -> this.modules.put(m.path(), m));
        modules.forEach(m -> modulesIgnoreCase.putIfAbsent(m.path().toLowerCase(Locale.ROOT), m));
        this.aliases = aliases;
    }

    public Collection<JsModule> modules() {
        return modules.values();
    }

    public Optional<JsModule> module(String path) {
        return Optional.ofNullable(modules.get(path));
    }

    /** Belirteç proje içini mi gösteriyor (göreli yol veya tsconfig takma adı); harici paketlerde false. */
    public boolean isInternal(String specifier) {
        return specifier != null && (specifier.startsWith(".") || aliases.matchesPattern(specifier));
    }

    /** Import belirtecini projedeki modüle çözer; harici paketlerde boş döner. */
    public Optional<JsModule> resolveModule(JsModule from, String specifier) {
        if (specifier == null) {
            return Optional.empty();
        }
        if (specifier.startsWith(".")) {
            String dir = from.path().contains("/") ? from.path().substring(0, from.path().lastIndexOf('/')) : "";
            return candidates(normalize(dir.isEmpty() ? specifier : dir + "/" + specifier));
        }
        for (String target : aliases.resolve(specifier)) {
            Optional<JsModule> module = candidates(normalize(target));
            if (module.isPresent()) {
                return module;
            }
        }
        return Optional.empty();
    }

    private Optional<JsModule> candidates(String base) {
        if (base == null) {
            return Optional.empty();
        }
        Optional<JsModule> exact = candidates(base, modules::get);
        return exact.isPresent()
                ? exact
                : candidates(base.toLowerCase(Locale.ROOT), modulesIgnoreCase::get);
    }

    private static Optional<JsModule> candidates(String base, Function<String, JsModule> lookup) {
        if (lookup.apply(base) != null) {
            return Optional.of(lookup.apply(base));
        }
        // ESM tarzı TS import'u: './x.js' → x.ts
        String stripped = base.replaceFirst("\\.(js|jsx|mjs|cjs)$", "");
        for (String ext : EXTENSIONS) {
            JsModule m = lookup.apply(stripped + ext);
            if (m != null) {
                return Optional.of(m);
            }
        }
        for (String ext : EXTENSIONS) {
            JsModule m = lookup.apply(base + "/index" + ext);
            if (m != null) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** {@code a/./b/../c} → {@code a/c}; kökün dışına çıkan yolda null. */
    static String normalize(String path) {
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    // ---- semboller ----

    /** Modül kapsamındaki bir adın sembolü: yerel bildirim veya import. */
    public Optional<Symbol> resolveName(JsModule module, String name) {
        return resolveName(module, name, new HashSet<>());
    }

    private Optional<Symbol> resolveName(JsModule module, String name, Set<String> visited) {
        if (!visited.add(module.path() + "#" + name)) {
            return Optional.empty();
        }
        Node decl = module.declarations().get(name);
        if (decl != null) {
            return Optional.of(follow(new Symbol(module, name, decl), visited));
        }
        ImportRef ref = module.imports().get(name);
        if (ref == null) {
            return Optional.empty();
        }
        Optional<JsModule> target = resolveModule(module, ref.from());
        if (target.isEmpty()) {
            return Optional.empty();
        }
        if (ref.imported().equals("*")) {
            return Optional.of(new Symbol(target.get(), "*", null));
        }
        return resolveExport(target.get(), ref.imported(), visited);
    }

    /** Modülün dışa aktardığı bir adın sembolü. */
    public Optional<Symbol> resolveExport(JsModule module, String exported) {
        return resolveExport(module, exported, new HashSet<>());
    }

    private Optional<Symbol> resolveExport(JsModule module, String exported, Set<String> visited) {
        if (!visited.add(module.path() + "@" + exported)) {
            return Optional.empty();
        }
        ExportRef ref = module.exports().get(exported);
        if (ref != null) {
            if (ref.local() != null) {
                return resolveName(module, ref.local(), visited);
            }
            Optional<JsModule> target = resolveModule(module, ref.from());
            if (target.isEmpty()) {
                return Optional.empty();
            }
            if (ref.imported().equals("*")) {
                return Optional.of(new Symbol(target.get(), "*", null));
            }
            return resolveExport(target.get(), ref.imported(), visited);
        }
        if (exported.equals(JsModule.DEFAULT)) {
            return Optional.empty();
        }
        for (String from : module.starExports()) {
            Optional<JsModule> target = resolveModule(module, from);
            if (target.isPresent()) {
                Optional<Symbol> symbol = resolveExport(target.get(), exported, visited);
                if (symbol.isPresent()) {
                    return symbol;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Takma adları izler: {@code const A = B} → B'nin sembolü; {@code const A = lazy(() => import('./A'))} veya
     * {@code asyncComponent(() => import('./A'))} → A modülünün varsayılan export'u.
     */
    private Symbol follow(Symbol symbol, Set<String> visited) {
        Node value = symbol.value();
        if (value instanceof Ident i && !i.name().equals(symbol.name())) {
            return resolveName(symbol.module(), i.name(), visited).orElse(symbol);
        }
        String importTarget = wrappedImport(value);
        if (importTarget != null) {
            Optional<JsModule> target = resolveModule(symbol.module(), importTarget);
            if (target.isPresent()) {
                return resolveExport(target.get(), JsModule.DEFAULT, visited).orElse(symbol);
            }
        }
        return symbol;
    }

    /**
     * Dinamik import'u saran çağrının import belirteci: {@code lazy(() => import('./X'))}, {@code React.lazy(...)},
     * {@code asyncComponent(() => import('./X'))}, {@code loadable(() => import('./X'), {...})}.
     */
    static String wrappedImport(Node value) {
        if (value instanceof Call call) {
            for (Node arg : call.args()) {
                String specifier = dynamicImport(arg);
                if (specifier != null) {
                    return specifier;
                }
            }
        }
        return null;
    }

    /** {@code () => import('./X')} veya {@code () => import('./X').then(...)} ise belirteç. */
    static String dynamicImport(Node node) {
        if (node instanceof Func f && f.body().size() == 1 && f.body().get(0) instanceof Return r) {
            Node value = r.value();
            if (value instanceof Js.Unary u && u.op().equals("await")) {
                value = u.operand();
            }
            while (value instanceof Call c && c.callee() instanceof Member m && m.object() instanceof Call inner) {
                value = inner;
            }
            if (value instanceof Call c && c.callee() instanceof Ident i && i.name().equals("import")
                    && c.args().size() == 1 && c.args().get(0) instanceof Str s) {
                return s.value();
            }
        }
        return null;
    }

    /**
     * Bir ifadenin sembolü: {@code ad}, {@code adAlanı.ad} veya {@code nesne.özellik} (nesne literaliyle tanımlı
     * sabitlerde, ör. {@code export const api = { getX: () => ... }}).
     */
    public Optional<Symbol> resolve(JsModule module, Node expr) {
        if (expr instanceof Ident i) {
            return resolveName(module, i.name());
        }
        if (expr instanceof Member m && m.property() != null) {
            Optional<Symbol> object = resolve(module, m.object());
            if (object.isEmpty()) {
                return Optional.empty();
            }
            Symbol o = object.get();
            if (o.isNamespace()) {
                return resolveExport(o.module(), m.property());
            }
            if (o.value() instanceof Obj obj) {
                for (Prop p : obj.props()) {
                    if (m.property().equals(p.key())) {
                        return Optional.of(new Symbol(o.module(), o.name() + "." + p.key(), p.value()));
                    }
                }
            }
        }
        return Optional.empty();
    }
}

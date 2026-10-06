package me.emreihtiyar.codegraph.extract.ts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import me.emreihtiyar.codegraph.extract.PathNormalizer;
import me.emreihtiyar.codegraph.extract.ts.Js.Arr;
import me.emreihtiyar.codegraph.extract.ts.Js.Binary;
import me.emreihtiyar.codegraph.extract.ts.Js.Call;
import me.emreihtiyar.codegraph.extract.ts.Js.Cond;
import me.emreihtiyar.codegraph.extract.ts.Js.Func;
import me.emreihtiyar.codegraph.extract.ts.Js.Ident;
import me.emreihtiyar.codegraph.extract.ts.Js.Literal;
import me.emreihtiyar.codegraph.extract.ts.Js.Member;
import me.emreihtiyar.codegraph.extract.ts.Js.Node;
import me.emreihtiyar.codegraph.extract.ts.Js.Obj;
import me.emreihtiyar.codegraph.extract.ts.Js.Prop;
import me.emreihtiyar.codegraph.extract.ts.Js.Return;
import me.emreihtiyar.codegraph.extract.ts.Js.Str;
import me.emreihtiyar.codegraph.extract.ts.Js.Template;
import me.emreihtiyar.codegraph.extract.ts.Js.Unary;
import me.emreihtiyar.codegraph.extract.ts.Js.VarDecl;
import me.emreihtiyar.codegraph.extract.ts.JsProject.Symbol;

/**
 * URL ifadelerinin statik değerlendirmesi (PRD FR-07). Sonuç parçalardan oluşur:
 * <ul>
 *   <li>metin: literaller ve çözülebilen sabitler (import edilenler dahil),</li>
 *   <li>gateway: ortam değişkeni ({@code import.meta.env.X}, {@code process.env.X}, {@code window._env_.X}) — gateway
 *       taban adresi kabul edilir,</li>
 *   <li>dinamik: parametreler ve değerlendirilemeyen ifadeler; path'te {@code {*}} olur.</li>
 * </ul>
 * Basit yardımcı fonksiyonlar ({@code buildUrl(path)} gibi tek {@code return} ifadesi olanlar) argümanlar
 * bağlanarak satır içi değerlendirilir.
 */
final class UrlEvaluator {

    enum Kind {
        TEXT,
        GATEWAY,
        DYNAMIC
    }

    record Part(Kind kind, String text) {
        static final Part GATEWAY = new Part(Kind.GATEWAY, null);
        static final Part DYNAMIC = new Part(Kind.DYNAMIC, null);

        static Part text(String text) {
            return new Part(Kind.TEXT, text);
        }
    }

    /**
     * @param targetPrefix   gateway sonrası ilk segment (hedef BFF)
     * @param normalizedPath kalan path, normalize edilmiş
     */
    record Target(String targetPrefix, String normalizedPath) {}

    private static final Pattern ENV_ACCESS = Pattern.compile(
            "^(import\\.meta\\.env|process\\.env|window\\.(_env_|env|__ENV__|ENV|__env))\\.[\\w$]+$");
    private static final int MAX_DEPTH = 8;

    /** Değerlendirme kapsamı: fonksiyon parametrelerine bağlı değerler ve fonksiyon içi yerel bildirimler. */
    static final class Scope {
        final JsModule module;
        final Map<String, List<Part>> bindings = new HashMap<>();
        final Map<String, Node> locals = new HashMap<>();

        Scope(JsModule module) {
            this.module = module;
        }

        /** Fonksiyon kapsamı: parametreler argüman değerlerine (yoksa dinamik) bağlanır. */
        static Scope function(JsModule module, Func f, List<List<Part>> args) {
            Scope scope = new Scope(module);
            for (int i = 0; i < f.params().size(); i++) {
                Js.Pattern p = f.params().get(i);
                List<Part> value = i < args.size() && p.name() != null ? args.get(i) : List.of(Part.DYNAMIC);
                p.names().forEach(n -> scope.bindings.put(n, value));
            }
            for (Node statement : f.body()) {
                Js.walk(statement, n -> {
                    if (n instanceof VarDecl v && v.target().name() != null && v.init() != null) {
                        scope.locals.putIfAbsent(v.target().name(), v.init());
                    } else if (n instanceof VarDecl v) {
                        v.target().names().forEach(name -> scope.bindings.putIfAbsent(name, List.of(Part.DYNAMIC)));
                    }
                });
            }
            return scope;
        }
    }

    private final JsProject project;

    UrlEvaluator(JsProject project) {
        this.project = project;
    }

    List<Part> eval(Node node, Scope scope) {
        return eval(node, scope, 0);
    }

    private List<Part> eval(Node node, Scope scope, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return dynamic();
        }
        if (node instanceof Str s) {
            return List.of(Part.text(s.value()));
        }
        if (node instanceof Literal l) {
            return l.text().matches("[0-9][0-9_.]*") ? List.of(Part.text(l.text())) : dynamic();
        }
        if (node instanceof Template t) {
            List<Part> out = new ArrayList<>();
            for (int i = 0; i < t.exprs().size(); i++) {
                out.add(Part.text(t.quasis().get(i)));
                out.addAll(eval(t.exprs().get(i), scope, depth + 1));
            }
            out.add(Part.text(t.quasis().get(t.quasis().size() - 1)));
            return merge(out);
        }
        if (node instanceof Binary b && b.op().equals("+")) {
            List<Part> out = new ArrayList<>(eval(b.left(), scope, depth + 1));
            out.addAll(eval(b.right(), scope, depth + 1));
            return merge(out);
        }
        if (node instanceof Binary b && (b.op().equals("||") || b.op().equals("??"))) {
            List<Part> left = eval(b.left(), scope, depth + 1);
            return isDynamic(left) ? eval(b.right(), scope, depth + 1) : left;
        }
        if (node instanceof Cond c) {
            List<Part> a = eval(c.then(), scope, depth + 1);
            List<Part> b = eval(c.otherwise(), scope, depth + 1);
            return a.equals(b) ? a : dynamic();
        }
        if (node instanceof Unary u && u.op().equals("await")) {
            return eval(u.operand(), scope, depth + 1);
        }
        if (node instanceof Ident i) {
            return evalName(i.name(), scope, depth);
        }
        if (node instanceof Member m) {
            String dotted = Js.dottedName(m);
            if (dotted != null && ENV_ACCESS.matcher(dotted).matches()) {
                return List.of(Part.GATEWAY);
            }
            Optional<Node> property = property(m.object(), m.property(), scope);
            if (property.isPresent()) {
                return eval(property.get(), scope, depth + 1);
            }
            return dynamic();
        }
        if (node instanceof Call c) {
            return evalCall(c, scope, depth);
        }
        return dynamic();
    }

    private List<Part> evalName(String name, Scope scope, int depth) {
        if (scope.bindings.containsKey(name)) {
            return scope.bindings.get(name);
        }
        Node local = scope.locals.get(name);
        if (local != null) {
            Scope shadowed = new Scope(scope.module);
            shadowed.bindings.putAll(scope.bindings);
            shadowed.locals.putAll(scope.locals);
            shadowed.locals.remove(name);
            return eval(local, shadowed, depth + 1);
        }
        Optional<Symbol> symbol = project.resolveName(scope.module, name);
        if (symbol.isPresent() && symbol.get().node() instanceof VarDecl v && v.target().name() != null) {
            return eval(v.init(), new Scope(symbol.get().module()), depth + 1);
        }
        return dynamic();
    }

    private List<Part> evalCall(Call call, Scope scope, int depth) {
        // ['a', b].join('/')
        if (call.callee() instanceof Member m && "join".equals(m.property()) && m.object() instanceof Arr arr) {
            String separator = call.args().isEmpty() ? "," : evalString(call.args().get(0), scope).orElse(null);
            if (separator == null) {
                return dynamic();
            }
            List<Part> out = new ArrayList<>();
            for (int i = 0; i < arr.elements().size(); i++) {
                if (i > 0) {
                    out.add(Part.text(separator));
                }
                out.addAll(eval(arr.elements().get(i), scope, depth + 1));
            }
            return merge(out);
        }
        Optional<Symbol> symbol = project.resolve(scope.module, call.callee());
        if (symbol.isPresent() && symbol.get().value() instanceof Func f) {
            Node returned = singleReturn(f);
            if (returned != null) {
                List<List<Part>> args = call.args().stream().map(a -> eval(a, scope, depth + 1)).toList();
                return eval(returned, Scope.function(symbol.get().module(), f, args), depth + 1);
            }
        }
        return dynamic();
    }

    /** Fonksiyonun tek üst düzey {@code return} ifadesi; yoksa veya birden çoksa null. */
    private static Node singleReturn(Func f) {
        Node found = null;
        for (Node statement : f.body()) {
            if (statement instanceof Return r) {
                if (found != null) {
                    return null;
                }
                found = r.value();
            }
        }
        return found;
    }

    /** Değeri tamamen metin olan ifade (ör. {@code method: 'POST'}). */
    Optional<String> evalString(Node node, Scope scope) {
        List<Part> parts = eval(node, scope);
        if (parts.stream().allMatch(p -> p.kind() == Kind.TEXT)) {
            StringBuilder sb = new StringBuilder();
            parts.forEach(p -> sb.append(p.text()));
            return Optional.of(sb.toString());
        }
        return Optional.empty();
    }

    /** Nesne ifadesinin (literal veya literalle tanımlı sabit) bir özelliğinin değeri. */
    Optional<Node> property(Node object, String key, Scope scope) {
        return objectLiteral(object, scope, 0).flatMap(o -> {
            for (Prop p : o.props()) {
                if (key.equals(p.key())) {
                    return Optional.of(p.value());
                }
            }
            return Optional.empty();
        });
    }

    Optional<Obj> objectLiteral(Node node, Scope scope) {
        return objectLiteral(node, scope, 0);
    }

    private Optional<Obj> objectLiteral(Node node, Scope scope, int depth) {
        if (depth > MAX_DEPTH) {
            return Optional.empty();
        }
        if (node instanceof Obj o) {
            return Optional.of(o);
        }
        if (node instanceof Ident i) {
            Node local = scope.locals.get(i.name());
            if (local != null) {
                return objectLiteral(local, scope, depth + 1);
            }
            if (scope.bindings.containsKey(i.name())) {
                return Optional.empty();
            }
            return project.resolveName(scope.module, i.name())
                    .filter(s -> s.value() instanceof Obj)
                    .map(s -> (Obj) s.value());
        }
        return Optional.empty();
    }

    // ---- parçalar ----

    private static List<Part> dynamic() {
        return List.of(Part.DYNAMIC);
    }

    private static boolean isDynamic(List<Part> parts) {
        return parts.stream().allMatch(p -> p.kind() == Kind.DYNAMIC);
    }

    /** Ardışık metinleri birleştirir, boş metinleri ve ardışık dinamikleri atar. */
    static List<Part> merge(List<Part> parts) {
        List<Part> out = new ArrayList<>();
        for (Part p : parts) {
            if (p.kind() == Kind.TEXT && p.text().isEmpty()) {
                continue;
            }
            Part last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.kind() == Kind.TEXT && p.kind() == Kind.TEXT) {
                out.set(out.size() - 1, Part.text(last.text() + p.text()));
            } else if (last != null && last.kind() == Kind.DYNAMIC && p.kind() == Kind.DYNAMIC) {
                continue;
            } else {
                out.add(p);
            }
        }
        return out;
    }

    /** Okunabilir şablon: gateway {@code {gateway}}, dinamik kısım {@code {*}}. */
    static String render(List<Part> parts) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            sb.append(switch (p.kind()) {
                case TEXT -> p.text();
                case GATEWAY -> "{gateway}";
                case DYNAMIC -> PathNormalizer.WILDCARD;
            });
        }
        return sb.toString();
    }

    /** Taban adresli istemcinin göreli URL'si: taban + URL (URL mutlaksa yalnızca URL). */
    static List<Part> withBase(List<Part> base, List<Part> url) {
        if (base == null || base.isEmpty() || isAbsolute(url)) {
            return url;
        }
        List<Part> out = new ArrayList<>(base);
        if (!url.isEmpty() && url.get(0).kind() == Kind.TEXT && !url.get(0).text().startsWith("/")) {
            out.add(Part.text("/"));
        }
        out.addAll(url);
        return merge(out);
    }

    private static boolean isAbsolute(List<Part> url) {
        if (url.isEmpty()) {
            return false;
        }
        Part first = url.get(0);
        return first.kind() == Kind.GATEWAY
                || first.kind() == Kind.TEXT && first.text().matches("(?i)^(https?:)?//.*");
    }

    /** URL path'e benziyor mu (genel istemcilerde {@code map.get(key)} gibi yanlış pozitifleri elemek için). */
    static boolean looksLikeUrl(List<Part> url) {
        if (url.isEmpty()) {
            return false;
        }
        Part first = url.get(0);
        return first.kind() == Kind.GATEWAY
                || first.kind() == Kind.TEXT && (first.text().startsWith("/") || first.text().matches("(?i)^https?://.*"));
    }

    /**
     * Gateway sonrası hedef: ilk segment BFF'in gateway prefix'i, kalanı BFF'teki path. Gateway tabanı ortam
     * değişkeni, mutlak adresin host'u veya ({@code /} ile başlayan göreli URL'de) proxy kabul edilir. İlk segment
     * statik değilse hedef çözülemez.
     */
    static Optional<Target> target(List<Part> parts) {
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        List<Part> rest = new ArrayList<>();
        Part first = parts.get(0);
        if (first.kind() == Kind.GATEWAY) {
            rest.addAll(parts.subList(1, parts.size()));
        } else if (first.kind() == Kind.TEXT && first.text().matches("(?i)^(https?:)?//.*")) {
            String text = first.text().replaceFirst("(?i)^(https?:)?//[^/]*", "");
            if (text.isEmpty() && parts.size() > 1) {
                return Optional.empty(); // host dinamik devam ediyor
            }
            rest.add(Part.text(text));
            rest.addAll(parts.subList(1, parts.size()));
        } else if (first.kind() == Kind.TEXT && first.text().startsWith("/")) {
            rest.addAll(parts);
        } else {
            return Optional.empty();
        }
        String path = render(merge(rest)).replace("{gateway}", PathNormalizer.WILDCARD);
        if (!path.startsWith("/")) {
            return Optional.empty(); // gateway'den sonra doğrudan dinamik kısım: buildUrl(path)
        }
        String cleaned = PathNormalizer.clean(path);
        String[] segments = cleaned.substring(1).split("/", 2);
        String prefix = segments[0];
        if (prefix.isEmpty() || prefix.contains("{") || prefix.contains("$")) {
            return Optional.empty();
        }
        String remaining = segments.length > 1 ? segments[1] : "";
        return Optional.of(new Target(prefix, PathNormalizer.normalize(remaining)));
    }
}

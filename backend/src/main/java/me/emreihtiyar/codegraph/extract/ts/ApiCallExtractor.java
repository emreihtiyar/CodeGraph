package me.emreihtiyar.codegraph.extract.ts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.emreihtiyar.codegraph.extract.model.ApiCallFact;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import me.emreihtiyar.codegraph.extract.model.SourceRef;
import me.emreihtiyar.codegraph.extract.ts.Js.Call;
import me.emreihtiyar.codegraph.extract.ts.Js.Func;
import me.emreihtiyar.codegraph.extract.ts.Js.Ident;
import me.emreihtiyar.codegraph.extract.ts.Js.Member;
import me.emreihtiyar.codegraph.extract.ts.Js.Node;
import me.emreihtiyar.codegraph.extract.ts.Js.Obj;
import me.emreihtiyar.codegraph.extract.ts.Js.Prop;
import me.emreihtiyar.codegraph.extract.ts.Js.VarDecl;
import me.emreihtiyar.codegraph.extract.ts.JsProject.Symbol;
import me.emreihtiyar.codegraph.extract.ts.UrlEvaluator.Part;
import me.emreihtiyar.codegraph.extract.ts.UrlEvaluator.Scope;
import me.emreihtiyar.codegraph.extract.ts.UrlEvaluator.Target;

/**
 * ApiCalls dosyalarındaki endpoint tanımları (PRD FR-07). Dosyanın export ettiği her fonksiyon (veya export edilen
 * nesne literalindeki her fonksiyon özelliği, ör. {@code export const api = { getX: () => ... }}) bir ApiCall
 * adayıdır; gövdesindeki ilk HTTP çağrısı aranır:
 * <ul>
 *   <li>{@code fetch(url, { method })} — metot verilmezse GET,</li>
 *   <li>{@code axios(config)}, {@code axios(url, config)}, {@code axios.get(url)} ...,</li>
 *   <li>{@code axios.create({ baseURL })} ile oluşturulmuş istemciler — taban adres URL'nin önüne eklenir,</li>
 *   <li>diğer istemciler ({@code http.get('/x')}) — URL path'e benziyorsa,</li>
 *   <li>projedeki yardımcı fonksiyonlar ({@code request('POST', url)}) — argümanlar bağlanarak 3 seviyeye kadar
 *       içine girilir.</li>
 * </ul>
 */
final class ApiCallExtractor {

    private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final int MAX_INLINE_DEPTH = 3;

    private record HttpCall(HttpMethod method, List<Part> url) {}

    private final JsProject project;
    private final UrlEvaluator evaluator;

    ApiCallExtractor(JsProject project) {
        this.project = project;
        this.evaluator = new UrlEvaluator(project);
    }

    /** @return ApiCall sembol anahtarı → olgu */
    Map<String, ApiCallFact> extract(List<JsModule> apiCallModules, List<ParseIssue> issues) {
        Map<String, ApiCallFact> out = new LinkedHashMap<>();
        for (JsModule module : apiCallModules) {
            for (String exported : module.exports().keySet()) {
                Optional<Symbol> symbol = project.resolveExport(module, exported);
                if (symbol.isEmpty() || symbol.get().module() != module || out.containsKey(symbol.get().key())) {
                    continue; // yeniden export edilenler kendi dosyalarında işlenir
                }
                candidates(symbol.get()).forEach(c -> analyze(c, out, issues));
            }
        }
        return out;
    }

    /** Export edilen fonksiyon veya nesne literalindeki fonksiyon özellikleri. */
    private List<Symbol> candidates(Symbol symbol) {
        Node value = symbol.value();
        if (value instanceof Func) {
            return List.of(symbol);
        }
        List<Symbol> out = new ArrayList<>();
        if (value instanceof Obj obj) {
            for (Prop p : obj.props()) {
                if (p.key() != null && p.value() instanceof Func) {
                    out.add(new Symbol(symbol.module(), symbol.name() + "." + p.key(), p.value()));
                }
            }
        }
        return out;
    }

    private void analyze(Symbol symbol, Map<String, ApiCallFact> out, List<ParseIssue> issues) {
        Func f = (Func) symbol.value();
        JsModule module = symbol.module();
        HttpCall call = find(f, Scope.function(module, f, List.of()), 0, new HashSet<>());
        if (call == null) {
            issues.add(new ParseIssue(module.path(), f.line(),
                    "ApiCall adayı '" + symbol.name() + "' içinde HTTP çağrısı bulunamadı"));
            return;
        }
        Optional<Target> target = UrlEvaluator.target(call.url());
        int start = startLine(symbol);
        out.put(symbol.key(), new ApiCallFact(
                symbol.key(),
                symbol.name(),
                call.method(),
                UrlEvaluator.render(call.url()),
                target.map(Target::normalizedPath).orElse(null),
                target.map(Target::targetPrefix).orElse(null),
                new SourceRef(module.path(), start, f.endLine()),
                module.code(start, f.endLine())));
    }

    /** Değişkenle tanımlı fonksiyonda bildirim satırı ({@code export const x = async () => ...}). */
    private static int startLine(Symbol symbol) {
        if (symbol.node() instanceof VarDecl v) {
            return Math.min(v.line(), ((Func) symbol.value()).line());
        }
        return ((Func) symbol.value()).line();
    }

    // ---- HTTP çağrısı arama ----

    private HttpCall find(Func f, Scope scope, int depth, Set<Func> active) {
        if (!active.add(f)) {
            return null;
        }
        try {
            for (Node statement : f.body()) {
                HttpCall call = visit(statement, scope, depth, active);
                if (call != null) {
                    return call;
                }
            }
            return null;
        } finally {
            active.remove(f);
        }
    }

    private HttpCall visit(Node node, Scope scope, int depth, Set<Func> active) {
        if (node instanceof Call c) {
            HttpCall http = asHttpCall(c, scope);
            if (http != null) {
                return http;
            }
        }
        for (Node child : Js.children(node)) {
            HttpCall http = visit(child, scope, depth, active);
            if (http != null) {
                return http;
            }
        }
        if (node instanceof Call c && depth < MAX_INLINE_DEPTH) {
            Optional<Symbol> callee = project.resolve(scope.module, c.callee());
            if (callee.isPresent() && callee.get().value() instanceof Func helper) {
                List<List<Part>> args = c.args().stream().map(a -> evaluator.eval(a, scope)).toList();
                return find(helper, Scope.function(callee.get().module(), helper, args), depth + 1, active);
            }
        }
        return null;
    }

    private HttpCall asHttpCall(Call call, Scope scope) {
        Node callee = call.callee();
        String dotted = Js.dottedName(callee);
        if ("fetch".equals(dotted) || "window.fetch".equals(dotted) || "globalThis.fetch".equals(dotted)) {
            if (call.args().isEmpty()) {
                return null;
            }
            HttpMethod method = call.args().size() > 1 ? methodOf(call.args().get(1), scope, HttpMethod.GET) : HttpMethod.GET;
            return new HttpCall(method, evaluator.eval(call.args().get(0), scope));
        }
        if (isAxios(callee, scope)) {
            return axiosCall(call.args(), null, null, scope);
        }
        if (callee instanceof Member m && m.property() != null) {
            String verb = m.property();
            boolean isRequest = verb.equals("request");
            if (!VERBS.contains(verb) && !isRequest) {
                return null;
            }
            List<Part> base = null;
            boolean known = isAxios(m.object(), scope);
            if (!known) {
                Optional<List<Part>> instanceBase = axiosInstanceBase(m.object(), scope);
                known = instanceBase.isPresent();
                base = instanceBase.orElse(null);
            }
            HttpMethod method = isRequest ? null : HttpMethod.valueOf(verb.toUpperCase(Locale.ROOT));
            HttpCall http = isRequest
                    ? axiosCall(call.args(), base, null, scope)
                    : call.args().isEmpty() ? null : new HttpCall(method,
                            UrlEvaluator.withBase(base, evaluator.eval(call.args().get(0), scope)));
            if (http == null || !known && !UrlEvaluator.looksLikeUrl(http.url())) {
                return null;
            }
            return http;
        }
        return null;
    }

    /** {@code axios(config)}, {@code axios(url, config)}, {@code instance.request(config)}. */
    private HttpCall axiosCall(List<Node> args, List<Part> base, HttpMethod fallback, Scope scope) {
        if (args.isEmpty()) {
            return null;
        }
        Optional<Obj> config = evaluator.objectLiteral(args.get(0), scope);
        if (config.isPresent()) {
            Optional<Node> url = evaluator.property(config.get(), "url", scope);
            if (url.isEmpty()) {
                return null;
            }
            List<Part> configBase = evaluator.property(config.get(), "baseURL", scope)
                    .map(b -> evaluator.eval(b, scope))
                    .orElse(base);
            return new HttpCall(methodOf(config.get(), scope, fallback == null ? HttpMethod.GET : fallback),
                    UrlEvaluator.withBase(configBase, evaluator.eval(url.get(), scope)));
        }
        HttpMethod method = args.size() > 1 ? methodOf(args.get(1), scope, HttpMethod.GET) : HttpMethod.GET;
        return new HttpCall(method, UrlEvaluator.withBase(base, evaluator.eval(args.get(0), scope)));
    }

    private HttpMethod methodOf(Node options, Scope scope, HttpMethod fallback) {
        return evaluator.property(options, "method", scope)
                .flatMap(m -> evaluator.evalString(m, scope))
                .map(m -> {
                    try {
                        return HttpMethod.valueOf(m.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    /** {@code axios} paketinin varsayılan/ad alanı import'u (veya global {@code axios}). */
    private boolean isAxios(Node node, Scope scope) {
        if (!(node instanceof Ident i)) {
            return false;
        }
        var ref = scope.module.imports().get(i.name());
        if (ref != null) {
            return ref.from().equals("axios") && (ref.imported().equals("default") || ref.imported().equals("*"));
        }
        return i.name().equals("axios") && !scope.locals.containsKey("axios")
                && !scope.module.declarations().containsKey("axios");
    }

    /** {@code axios.create({ baseURL })} ile oluşturulmuş istemcinin taban adresi (baseURL yoksa boş liste). */
    private Optional<List<Part>> axiosInstanceBase(Node node, Scope scope) {
        Node init = null;
        Scope initScope = scope;
        if (node instanceof Ident i && scope.locals.containsKey(i.name())) {
            init = scope.locals.get(i.name());
        } else {
            Optional<Symbol> symbol = project.resolve(scope.module, node);
            if (symbol.isPresent()) {
                init = symbol.get().value();
                initScope = new Scope(symbol.get().module());
            }
        }
        if (init instanceof Call c && c.callee() instanceof Member m && "create".equals(m.property())
                && isAxios(m.object(), initScope)) {
            if (c.args().isEmpty()) {
                return Optional.of(List.of());
            }
            Scope s = initScope;
            return Optional.of(evaluator.property(c.args().get(0), "baseURL", s)
                    .map(b -> evaluator.eval(b, s))
                    .orElse(List.of()));
        }
        return Optional.empty();
    }
}

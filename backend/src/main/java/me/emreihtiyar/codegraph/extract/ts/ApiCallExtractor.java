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
import me.emreihtiyar.codegraph.extract.ts.Js.VarDecl;
import me.emreihtiyar.codegraph.extract.ts.JsProject.Symbol;
import me.emreihtiyar.codegraph.extract.ts.UrlEvaluator.Part;
import me.emreihtiyar.codegraph.extract.ts.UrlEvaluator.Scope;
import me.emreihtiyar.codegraph.extract.ts.UrlEvaluator.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ApiCalls dosyalarındaki endpoint tanımları (PRD FR-07). Dosyanın export ettiği her fonksiyon, export edilen nesne
 * literalindeki her fonksiyon özelliği ({@code export const api = { getX: () => ... }}) ve export edilen sınıfın (veya
 * {@code export default new XApiCalls()} örneğinin) metotları bir ApiCall adayıdır; gövdesindeki ilk HTTP çağrısı
 * aranır:
 * <ul>
 *   <li>{@code fetch(url, { method })} — metot verilmezse GET,</li>
 *   <li>{@code axios(config)}, {@code axios(url, config)}, {@code axios.get(url)} ...,</li>
 *   <li>{@code axios.create({ baseURL })} ile oluşturulmuş istemciler — taban adres URL'nin önüne eklenir,</li>
 *   <li>diğer istemciler ({@code http.get('/x')}) — URL path'e benziyorsa,</li>
 *   <li>projedeki yardımcı fonksiyonlar ({@code request('POST', url)}) — argümanlar bağlanarak 3 seviyeye kadar
 *       içine girilir.</li>
 * </ul>
 * Bunların hiçbiri yoksa son çare olarak argümanı (ya da nesne argümanının {@code url/path/endpoint} özelliği) URL'ye
 * benzeyen ilk çağrı alınır; metot nesnenin {@code method} özelliğinden veya çağrılan adın fiilinden
 * ({@code httpPost}, {@code postData}) çıkarılır, yoksa GET.
 */
final class ApiCallExtractor {

    private static final Logger log = LoggerFactory.getLogger(ApiCallExtractor.class);

    private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final int MAX_INLINE_DEPTH = 3;
    private static final List<String> URL_KEYS = List.of("url", "path", "endpoint", "uri", "serviceUrl", "apiUrl");
    private static final java.util.regex.Pattern WORDS = java.util.regex.Pattern.compile("[a-z]+|[A-Z][a-z]*");

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
        Set<String> seen = new HashSet<>();
        for (JsModule module : apiCallModules) {
            int before = out.size();
            int candidateCount = 0;
            for (String exported : module.exports().keySet()) {
                Optional<Symbol> symbol = project.resolveExport(module, exported);
                if (symbol.isEmpty() || symbol.get().module() != module) {
                    log.debug("{}: export '{}' atlandı ({})", module.path(), exported,
                            symbol.isEmpty() ? "çözülemedi" : "başka dosyadan yeniden export, orada işlenir");
                    continue;
                }
                List<Symbol> candidates = candidates(symbol.get());
                if (candidates.isEmpty()) {
                    log.debug("{}: export '{}' fonksiyon/nesne/sınıf değil ({}), ApiCall adayı sayılmadı", module.path(),
                            exported, kind(symbol.get().value()));
                }
                for (Symbol c : candidates) {
                    if (seen.add(c.key())) {
                        candidateCount++;
                        analyze(c, out, issues);
                    }
                }
            }
            log.debug("{}: {} export, {} ApiCall adayı, {} ApiCall bulundu", module.path(), module.exports().size(),
                    candidateCount, out.size() - before);
            if (module.exports().isEmpty()) {
                log.debug("{}: hiç export yok; ApiCall'lar export edilen fonksiyon, nesne ya da sınıf olmalı", module.path());
            }
        }
        return out;
    }

    /** Export edilen fonksiyon; nesne literalinin, sınıfın veya sınıf örneğinin fonksiyon üyeleri. */
    private List<Symbol> candidates(Symbol symbol) {
        if (symbol.value() instanceof Func) {
            return List.of(symbol);
        }
        return project.members(symbol).values().stream().filter(s -> s.value() instanceof Func).toList();
    }

    private void analyze(Symbol symbol, Map<String, ApiCallFact> out, List<ParseIssue> issues) {
        Func f = (Func) symbol.value();
        JsModule module = symbol.module();
        Scope scope = Scope.function(module, f, List.of());
        HttpCall call = find(f, scope, 0, new HashSet<>());
        if (call == null) {
            call = findLoose(f, scope);
            if (call != null) {
                log.debug("{}: '{}' bilinen bir HTTP istemcisi kullanmıyor; URL'ye benzeyen argümandan çıkarıldı",
                        module.path(), symbol.name());
            }
        }
        if (call == null) {
            String calls = describeCalls(f);
            issues.add(new ParseIssue(module.path(), f.line(), "ApiCall adayı '" + symbol.name()
                    + "' içinde HTTP çağrısı bulunamadı; gövdedeki çağrılar: " + (calls.isEmpty() ? "yok" : calls)));
            log.debug("{}:{} '{}' içinde HTTP çağrısı yok; çağrılar: {}", module.path(), f.line(), symbol.name(), calls);
            return;
        }
        Optional<Target> target = UrlEvaluator.target(call.url());
        log.debug("{}:{} ApiCall '{}' → {} {} (hedef prefix: {}, path: {})", module.path(), f.line(), symbol.name(),
                call.method(), UrlEvaluator.render(call.url()), target.map(Target::targetPrefix).orElse("çözülemedi"),
                target.map(Target::normalizedPath).orElse("-"));
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

    // ---- son çare: URL'ye benzeyen argüman ----

    /** Bilinen istemci yoksa: argümanı ya da nesne argümanının URL özelliği URL'ye benzeyen ilk çağrı. */
    private HttpCall findLoose(Func f, Scope scope) {
        List<Call> calls = new ArrayList<>();
        f.body().forEach(statement -> Js.walk(statement, n -> {
            if (n instanceof Call c) {
                calls.add(c);
            }
        }));
        for (Call c : calls) {
            for (Node arg : c.args()) {
                Optional<Obj> config = evaluator.objectLiteral(arg, scope);
                List<Part> url = config.isPresent() ? urlProperty(config.get(), scope) : evaluator.eval(arg, scope);
                if (url != null && UrlEvaluator.looksLikeUrl(url)) {
                    return new HttpCall(looseMethod(c, scope), url);
                }
            }
        }
        return null;
    }

    private List<Part> urlProperty(Obj config, Scope scope) {
        for (String key : URL_KEYS) {
            Optional<Node> value = evaluator.property(config, key, scope);
            if (value.isPresent()) {
                return evaluator.eval(value.get(), scope);
            }
        }
        return null;
    }

    /** Nesne argümanındaki {@code method}, yoksa çağrılan adın fiili ({@code httpPost} → POST), yoksa GET. */
    private HttpMethod looseMethod(Call call, Scope scope) {
        for (Node arg : call.args()) {
            if (evaluator.objectLiteral(arg, scope).flatMap(o -> evaluator.property(o, "method", scope)).isPresent()) {
                return methodOf(arg, scope, HttpMethod.GET);
            }
        }
        String name = Js.calleeName(call);
        if (name != null) {
            var words = WORDS.matcher(name);
            while (words.find()) {
                String word = words.group().toLowerCase(Locale.ROOT);
                if (VERBS.contains(word)) {
                    return HttpMethod.valueOf(word.toUpperCase(Locale.ROOT));
                }
            }
        }
        return HttpMethod.GET;
    }

    // ---- teşhis ----

    /** Gövdedeki ilk birkaç çağrı, ör. {@code ServiceCaller.call({serviceName, path}), r.json()}. */
    static String describeCalls(Func f) {
        Set<String> out = new java.util.LinkedHashSet<>();
        f.body().forEach(statement -> Js.walk(statement, n -> {
            if (n instanceof Call c && out.size() < 5) {
                String callee = Js.dottedName(c.callee());
                if (callee == null) {
                    callee = c.callee() instanceof Member m && m.property() != null ? "…." + m.property() : "…";
                }
                out.add(callee + "(" + String.join(", ", c.args().stream().limit(3).map(ApiCallExtractor::brief).toList())
                        + (c.args().size() > 3 ? ", …" : "") + ")");
            }
        }));
        return String.join(", ", out);
    }

    private static String brief(Node node) {
        if (node instanceof Js.Str s) {
            return "'" + (s.value().length() > 40 ? s.value().substring(0, 40) + "…" : s.value()) + "'";
        }
        if (node instanceof Js.Template) {
            return "`…`";
        }
        if (node instanceof Obj o) {
            return "{" + String.join(", ", o.props().stream().limit(6)
                    .map(p -> p.spread() ? "..." : p.key() == null ? "[…]" : p.key()).toList())
                    + (o.props().size() > 6 ? ", …" : "") + "}";
        }
        if (node instanceof Func) {
            return "() => …";
        }
        String dotted = Js.dottedName(node);
        return dotted != null ? dotted : "…";
    }

    private static String kind(Node node) {
        return node == null ? "değer yok" : node.getClass().getSimpleName();
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

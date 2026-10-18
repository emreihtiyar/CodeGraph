package me.emreihtiyar.codegraph.extract.ts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * ApiCalls dosyalarındaki endpoint tanımları (PRD FR-07). ApiCall, ApiCalls dosyasının export ettiği ve gövdesinde
 * {@value #CALL_API} çağıran fonksiyondur:
 *
 * <pre>
 * export function getBaseUrl(): string {
 *   return "leasing-contract-bff";
 * }
 *
 * export function getPaymentPlan(subContractId: number) {
 *   return callApi({
 *     endpoint: getBaseUrl() + `/api/sub-contract-view/${subContractId}/payment-plan`,
 *     method: "GET",
 *     notifyErrors: false,
 *   });
 * }
 * </pre>
 *
 * {@code endpoint} gateway'e görelidir: ilk segment BFF'in gateway prefix'i, kalanı BFF'teki path. {@code method}
 * verilmezse GET, statik olarak bilinemezse ANY kabul edilir. {@value #CALL_API} çağırmayan export'lar
 * ({@code getBaseUrl} gibi yardımcılar) ApiCall değildir.
 */
final class ApiCallExtractor {

    static final String CALL_API = "callApi";

    private static final Logger log = LoggerFactory.getLogger(ApiCallExtractor.class);

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
            int before = out.size();
            for (String exported : module.exports().keySet()) {
                Optional<Symbol> symbol = project.resolveExport(module, exported);
                if (symbol.isEmpty() || symbol.get().module() != module || out.containsKey(symbol.get().key())) {
                    continue; // yeniden export edilenler kendi dosyalarında işlenir
                }
                if (symbol.get().value() instanceof Func f) {
                    analyze(symbol.get(), f, out, issues);
                }
            }
            log.debug("{}: {} export, {} ApiCall", module.path(), module.exports().size(), out.size() - before);
        }
        return out;
    }

    private void analyze(Symbol symbol, Func f, Map<String, ApiCallFact> out, List<ParseIssue> issues) {
        JsModule module = symbol.module();
        Call call = findCallApi(f);
        if (call == null) {
            log.debug("{}:{} '{}' {} çağırmıyor, ApiCall sayılmadı; çağrılar: {}", module.path(), f.line(),
                    symbol.name(), CALL_API, describeCalls(f));
            return;
        }
        Scope scope = Scope.function(module, f, List.of());
        Optional<Obj> options = call.args().isEmpty() ? Optional.empty() : evaluator.objectLiteral(call.args().get(0), scope);
        Optional<Node> endpoint = options.flatMap(o -> evaluator.property(o, "endpoint", scope));
        if (endpoint.isEmpty()) {
            issues.add(new ParseIssue(module.path(), call.line(), "ApiCall '" + symbol.name() + "': " + CALL_API
                    + " çağrısında 'endpoint' bulunamadı (argüman nesne literali olmalı)"));
            log.debug("{}:{} '{}' {} çağrısında endpoint yok", module.path(), call.line(), symbol.name(), CALL_API);
            return;
        }
        List<Part> url = evaluator.eval(endpoint.get(), scope);
        HttpMethod method = options.flatMap(o -> evaluator.property(o, "method", scope))
                .map(m -> methodOf(m, scope))
                .orElse(HttpMethod.GET);
        Optional<Target> target = UrlEvaluator.target(url);
        int start = startLine(symbol, f);
        out.put(symbol.key(), new ApiCallFact(
                symbol.key(),
                symbol.name(),
                method,
                UrlEvaluator.render(url),
                target.map(Target::normalizedPath).orElse(null),
                target.map(Target::targetPrefix).orElse(null),
                new SourceRef(module.path(), start, f.endLine()),
                module.code(start, f.endLine())));
        log.debug("{}:{} ApiCall '{}' → {} {} (hedef prefix: {}, path: {})", module.path(), f.line(), symbol.name(),
                method, UrlEvaluator.render(url), target.map(Target::targetPrefix).orElse("çözülemedi"),
                target.map(Target::normalizedPath).orElse("-"));
    }

    /** Gövdedeki ilk {@value #CALL_API} çağrısı (iç içe fonksiyonlar dahil). */
    private static Call findCallApi(Func f) {
        Call[] found = {null};
        f.body().forEach(statement -> Js.walk(statement, n -> {
            if (found[0] == null && n instanceof Call c && CALL_API.equals(Js.calleeName(c))) {
                found[0] = c;
            }
        }));
        return found[0];
    }

    /** {@code "POST"}, {@code 'post'} veya {@code HttpMethod.POST} gibi enum üyesi; bilinemezse ANY. */
    private HttpMethod methodOf(Node node, Scope scope) {
        Optional<String> text = evaluator.evalString(node, scope);
        if (text.isEmpty() && node instanceof Member m) {
            text = Optional.ofNullable(m.property());
        }
        try {
            return text.map(t -> HttpMethod.valueOf(t.trim().toUpperCase(Locale.ROOT))).orElse(HttpMethod.ANY);
        } catch (IllegalArgumentException e) {
            return HttpMethod.ANY;
        }
    }

    /** Değişkenle tanımlı fonksiyonda bildirim satırı ({@code export const x = async () => ...}). */
    private static int startLine(Symbol symbol, Func f) {
        return symbol.node() instanceof VarDecl v ? Math.min(v.line(), f.line()) : f.line();
    }

    /** Gövdedeki ilk birkaç çağrı, ör. {@code request({endpoint, method}), getBaseUrl()}; DEBUG logu için. */
    private static String describeCalls(Func f) {
        Set<String> out = new LinkedHashSet<>();
        f.body().forEach(statement -> Js.walk(statement, n -> {
            if (n instanceof Call c && out.size() < 5) {
                String callee = Js.dottedName(c.callee());
                if (callee == null) {
                    callee = c.callee() instanceof Member m && m.property() != null ? "…." + m.property() : "…";
                }
                List<String> args = new ArrayList<>();
                c.args().stream().limit(3).forEach(a -> args.add(brief(a)));
                out.add(callee + "(" + String.join(", ", args) + (c.args().size() > 3 ? ", …" : "") + ")");
            }
        }));
        return out.isEmpty() ? "yok" : String.join(", ", out);
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
}

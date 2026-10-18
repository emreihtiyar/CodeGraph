package me.emreihtiyar.codegraph.extract.ts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.config.RepoDefinition;
import me.emreihtiyar.codegraph.extract.SourceText;
import me.emreihtiyar.codegraph.extract.model.ApiCallFact;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.model.ServiceFact;
import me.emreihtiyar.codegraph.extract.ts.ComponentExtractor.Component;
import me.emreihtiyar.codegraph.scan.ScannedRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Bir frontend reposunun Parse + Extract aşamaları (PRD §8.1, FR-07, FR-08): ApiCall'lar, component'ler, ApiCall
 * kullanımları, RENDERS ve route'lardan ekranlar.
 *
 * <p>Ayrıştırılamayan dosyalar analizi durdurmaz: ANTLR'ın kısmi ağacı kullanılır, hatalar raporlanır.
 *
 * <p>Ayrıntı için {@code logging.level.me.emreihtiyar.codegraph.extract.ts=DEBUG} (dosya başına TRACE) açılabilir.
 */
@Service
public class FrontendRepoAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FrontendRepoAnalyzer.class);

    public RepoFacts analyze(ScannedRepo scanned) {
        RepoDefinition repo = scanned.repo();
        String serviceName = repo.serviceName() != null ? repo.serviceName() : repo.name();
        RepoFacts facts = RepoFacts.empty(repo.name(), repo.layer(), new ServiceFact(serviceName, repo.layer(), "", null));

        log.debug("{}: {} ApiCalls dosyası, {} diğer kaynak dosyası ({})", repo.name(), scanned.apiCallFiles().size(),
                scanned.frontendSources().size(), repo.path());
        if (scanned.apiCallFiles().isEmpty()) {
            log.warn("{}: adı 'ApiCalls' içeren .ts/.tsx/.js/.jsx dosyası yok; ApiCall çıkarılamaz", repo.name());
        }
        List<JsModule> apiCallModules = new ArrayList<>();
        List<JsModule> modules = new ArrayList<>();
        for (Path file : scanned.apiCallFiles()) {
            parse(scanned, file, facts.issues()).ifPresent(m -> {
                apiCallModules.add(m);
                modules.add(m);
            });
        }
        for (Path file : scanned.frontendSources()) {
            parse(scanned, file, facts.issues()).ifPresent(modules::add);
        }

        PathAliases aliases = PathAliases.load(repo.path(), facts.issues());
        log.debug("{}: import takma adları: {}", repo.name(), aliases);
        JsProject project = new JsProject(modules, aliases);
        if (log.isDebugEnabled()) {
            logUnresolvedImports(repo.name(), project);
        }
        Map<String, ApiCallFact> apiCalls = new ApiCallExtractor(project).extract(apiCallModules, facts.issues());
        facts.apiCalls().addAll(apiCalls.values());
        if (apiCalls.isEmpty() && !apiCallModules.isEmpty()) {
            log.warn("{}: {} ApiCalls dosyasından hiç ApiCall çıkarılamadı; nedenleri Analiz ekranındaki sorunlarda "
                    + "(veya DEBUG logunda)", repo.name(), apiCallModules.size());
        }

        ComponentExtractor components = new ComponentExtractor(project, apiCalls.keySet());
        components.findComponents();
        for (Component c : components.components()) {
            facts.components().add(components.fact(c));
            facts.apiCallUsages().addAll(components.usages(c));
            facts.renders().addAll(components.renders(c));
        }
        facts.screens().addAll(new RouteExtractor(project, components).extract(facts.issues()));
        log.debug("{}: {} component, {} ApiCall kullanımı, {} RENDERS, {} ekran", repo.name(), facts.components().size(),
                facts.apiCallUsages().size(), facts.renders().size(), facts.screens().size());
        if (log.isTraceEnabled()) {
            facts.components().forEach(c -> log.trace("{}: component {}", repo.name(), c.key()));
            facts.apiCallUsages().forEach(u -> log.trace("{}: {} → {} ({})", repo.name(), u.componentKey(),
                    u.apiCallKey(), u.via()));
        }
        facts.screens().forEach(s -> log.debug("{}: ekran {} '{}' → {}", repo.name(), s.routePath(), s.name(),
                s.componentKey()));
        if (facts.screens().isEmpty()) {
            log.warn("{}: hiç ekran bulunamadı; route dizisi ({ path, component/element } nesneleri) ya da <Route> "
                    + "aranır, ayrıntı için DEBUG logu", repo.name());
        }
        return facts;
    }

    /** Proje içi görünen ama dosyası bulunamayan import'lar; çoğu zaman tsconfig paths ya da harf farkı. */
    private static void logUnresolvedImports(String repo, JsProject project) {
        int count = 0;
        for (JsModule module : project.modules()) {
            for (JsModule.ImportRef ref : new java.util.LinkedHashSet<>(module.imports().values())) {
                if (project.isInternal(ref.from()) && project.resolveModule(module, ref.from()).isEmpty()) {
                    if (count++ < 50) {
                        log.debug("{}: {} içindeki import '{}' projede bulunamadı", repo, module.path(), ref.from());
                    }
                }
            }
        }
        if (count > 0) {
            log.debug("{}: toplam {} çözülemeyen proje içi import", repo, count);
        }
    }

    private static java.util.Optional<JsModule> parse(ScannedRepo scanned, Path relative, List<ParseIssue> issues) {
        String path = relative.toString().replace('\\', '/');
        String source;
        try {
            source = read(scanned.absolute(relative));
        } catch (IOException e) {
            issues.add(new ParseIssue(path, 0, "Dosya okunamadı: " + e.getMessage()));
            log.debug("{}: okunamadı", path, e);
            return java.util.Optional.empty();
        }
        try {
            long t0 = System.nanoTime();
            TsSourceParser.Result result = TsSourceParser.parse(source, TsSourceParser.jsxEnabledFor(path));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            for (String error : result.errors()) {
                issues.add(new ParseIssue(path, lineOf(error), "Sözdizimi hatası: " + error));
            }
            if (!result.errors().isEmpty()) {
                log.debug("{}: {} sözdizimi hatası ({} ms), ilki: {}", path, result.errors().size(), ms,
                        result.errors().get(0));
            } else {
                log.trace("{}: ayrıştırıldı ({} ms)", path, ms);
            }
            return java.util.Optional.of(new JsModule(path, source, JsLowering.program(result.program())));
        } catch (RuntimeException | StackOverflowError e) {
            issues.add(new ParseIssue(path, 0, "Ayrıştırılamadı: " + e));
            log.debug("{}: ayrıştırılamadı", path, e);
            return java.util.Optional.empty();
        }
    }

    private static String read(Path file) throws IOException {
        return SourceText.read(file);
    }

    private static int lineOf(String error) {
        int colon = error.indexOf(':');
        try {
            return colon > 0 ? Integer.parseInt(error.substring(0, colon)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

package me.emreihtiyar.codegraph.extract.ts;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.config.RepoDefinition;
import me.emreihtiyar.codegraph.extract.model.ApiCallFact;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.model.ServiceFact;
import me.emreihtiyar.codegraph.extract.ts.ComponentExtractor.Component;
import me.emreihtiyar.codegraph.scan.ScannedRepo;
import org.springframework.stereotype.Service;

/**
 * Bir frontend reposunun Parse + Extract aşamaları (PRD §8.1, FR-07, FR-08): ApiCall'lar, component'ler, ApiCall
 * kullanımları, RENDERS ve route'lardan ekranlar.
 *
 * <p>Ayrıştırılamayan dosyalar analizi durdurmaz: ANTLR'ın kısmi ağacı kullanılır, hatalar raporlanır.
 */
@Service
public class FrontendRepoAnalyzer {

    public RepoFacts analyze(ScannedRepo scanned) {
        RepoDefinition repo = scanned.repo();
        String serviceName = repo.serviceName() != null ? repo.serviceName() : repo.name();
        RepoFacts facts = RepoFacts.empty(repo.name(), repo.layer(), new ServiceFact(serviceName, repo.layer(), "", null));

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

        JsProject project = new JsProject(modules, PathAliases.load(repo.path(), facts.issues()));
        Map<String, ApiCallFact> apiCalls = new ApiCallExtractor(project).extract(apiCallModules, facts.issues());
        facts.apiCalls().addAll(apiCalls.values());

        ComponentExtractor components = new ComponentExtractor(project, apiCalls.keySet());
        components.findComponents();
        for (Component c : components.components()) {
            facts.components().add(components.fact(c));
            facts.apiCallUsages().addAll(components.usages(c));
            facts.renders().addAll(components.renders(c));
        }
        facts.screens().addAll(new RouteExtractor(project, components).extract());
        return facts;
    }

    private static java.util.Optional<JsModule> parse(ScannedRepo scanned, Path relative, List<ParseIssue> issues) {
        String path = relative.toString().replace('\\', '/');
        String source;
        try {
            source = read(scanned.absolute(relative));
        } catch (IOException e) {
            issues.add(new ParseIssue(path, 0, "Dosya okunamadı: " + e.getMessage()));
            return java.util.Optional.empty();
        }
        try {
            TsSourceParser.Result result = TsSourceParser.parse(source, TsSourceParser.jsxEnabledFor(path));
            for (String error : result.errors()) {
                issues.add(new ParseIssue(path, lineOf(error), "Sözdizimi hatası: " + error));
            }
            return java.util.Optional.of(new JsModule(path, source, JsLowering.program(result.program())));
        } catch (RuntimeException | StackOverflowError e) {
            issues.add(new ParseIssue(path, 0, "Ayrıştırılamadı: " + e));
            return java.util.Optional.empty();
        }
    }

    private static String read(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return Files.readString(file, StandardCharsets.ISO_8859_1);
        }
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

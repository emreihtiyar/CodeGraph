package me.emreihtiyar.codegraph.extract.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.config.RepoDefinition;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.model.ServiceFact;
import me.emreihtiyar.codegraph.scan.ScannedRepo;
import org.springframework.stereotype.Component;

/**
 * Bir Java reposunun Parse + Extract aşamaları (PRD §8.1): application dosyaları, endpoint'ler, Feign, JPA, sınıf
 * ve metotlar, çağrı grafı.
 */
@Component
public class JavaRepoAnalyzer {

    /**
     * @param result bir reponun olguları ve sonraki repolara aktarılacak tip modeli (LIBRARY repoları için)
     */
    public record Result(RepoFacts facts, TypeModel model) {}

    /**
     * @param libraryRoots  LIBRARY repolarının Java kaynak kökleri (tip çözümü için)
     * @param libraryModel  LIBRARY repolarının birleşik tip modeli
     * @param placeholders  repo yapılandırmasındaki elle verilen placeholder değerleri
     */
    public Result analyze(
            ScannedRepo scanned,
            Collection<Path> libraryRoots,
            TypeModel libraryModel,
            Map<String, String> placeholders) {
        RepoDefinition repo = scanned.repo();
        List<ParseIssue> configIssues = new ArrayList<>();
        SpringProperties properties =
                SpringProperties.load(repo.path(), scanned.appConfigs(), placeholders, configIssues);
        RepoFacts facts = RepoFacts.empty(repo.name(), repo.layer(), service(repo, properties));
        facts.issues().addAll(configIssues);

        JavaSourceSet sources = JavaSourceSet.parse(repo.path(), scanned.javaSources(), libraryRoots);
        facts.issues().addAll(sources.issues());
        JavaContext ctx = new JavaContext(sources, properties, new ConstantResolver(sources, properties), facts);

        new EndpointExtractor().extract(ctx);
        new FeignExtractor().extract(ctx);
        new JpaExtractor().extract(ctx);
        TypeModel model = new ClassMethodExtractor().extract(ctx, libraryModel);

        TypeModel combined = new TypeModel();
        combined.addAll(model);
        combined.addAll(libraryModel);
        new CallGraphExtractor().extract(ctx, combined);
        return new Result(facts, model);
    }

    /**
     * Servis adı önceliği: yapılandırmadaki {@code serviceName} → {@code spring.application.name} → repo adı.
     * Gateway prefix'i yalnızca BFF'ler için; verilmezse servis adı.
     */
    static ServiceFact service(RepoDefinition repo, SpringProperties properties) {
        String name = repo.serviceName() != null
                ? repo.serviceName()
                : properties.get("spring.application.name").filter(SpringProperties::isResolved).orElse(repo.name());
        String contextPath = properties.get("server.servlet.context-path")
                .or(() -> properties.get("server.context-path"))
                .filter(SpringProperties::isResolved)
                .map(p -> p.equals("/") ? "" : p)
                .orElse("");
        String gatewayPrefix = repo.layer() == Layer.BFF
                ? (repo.gatewayPrefix() != null ? repo.gatewayPrefix() : name)
                : null;
        return new ServiceFact(name, repo.layer(), contextPath, gatewayPrefix);
    }
}

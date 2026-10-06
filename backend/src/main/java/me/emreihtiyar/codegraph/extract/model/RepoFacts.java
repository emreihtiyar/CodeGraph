package me.emreihtiyar.codegraph.extract.model;

import java.util.ArrayList;
import java.util.List;
import me.emreihtiyar.codegraph.config.Layer;

/**
 * Bir reponun Extract aşaması çıktısı (PRD §8.1). Grafa bağımlı değildir; hata ayıklama için JSON'a yazılabilir.
 *
 * <p>Listeler, çıkarıcıların sırayla doldurabilmesi için değiştirilebilirdir. Java düğümlerinin FQN'leri repo
 * içinde benzersizdir; repolar arası benzersizliği Load aşaması repo adıyla sağlar.
 */
public record RepoFacts(
        String repoName,
        Layer layer,
        ServiceFact service,
        List<ClassFact> classes,
        List<MethodFact> methods,
        List<CallFact> calls,
        List<OverrideFact> overrides,
        List<EndpointFact> endpoints,
        List<FeignClientFact> feignClients,
        List<FeignMethodFact> feignMethods,
        List<JpaRepositoryFact> jpaRepositories,
        List<RepositoryMethodFact> repositoryMethods,
        List<EntityFact> entities,
        List<EntityRelationFact> entityRelations,
        List<ApiCallFact> apiCalls,
        List<ComponentFact> components,
        List<ScreenFact> screens,
        List<ApiCallUsageFact> apiCallUsages,
        List<RenderFact> renders,
        List<ParseIssue> issues) {

    public static RepoFacts empty(String repoName, Layer layer, ServiceFact service) {
        return new RepoFacts(
                repoName,
                layer,
                service,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>());
    }
}

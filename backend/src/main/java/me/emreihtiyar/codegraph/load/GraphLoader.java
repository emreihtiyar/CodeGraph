package me.emreihtiyar.codegraph.load;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.extract.model.ApiCallFact;
import me.emreihtiyar.codegraph.extract.model.ApiCallUsageFact;
import me.emreihtiyar.codegraph.extract.model.AccessType;
import me.emreihtiyar.codegraph.extract.model.CallFact;
import me.emreihtiyar.codegraph.extract.model.CallTargetKind;
import me.emreihtiyar.codegraph.extract.model.ClassFact;
import me.emreihtiyar.codegraph.extract.model.ComponentFact;
import me.emreihtiyar.codegraph.extract.model.EndpointFact;
import me.emreihtiyar.codegraph.extract.model.EntityFact;
import me.emreihtiyar.codegraph.extract.model.EntityRelationFact;
import me.emreihtiyar.codegraph.extract.model.FeignClientFact;
import me.emreihtiyar.codegraph.extract.model.FeignMethodFact;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;
import me.emreihtiyar.codegraph.extract.model.JpaRepositoryFact;
import me.emreihtiyar.codegraph.extract.model.MethodFact;
import me.emreihtiyar.codegraph.extract.model.OverrideFact;
import me.emreihtiyar.codegraph.extract.model.RenderFact;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.model.RepositoryMethodFact;
import me.emreihtiyar.codegraph.extract.model.Resolution;
import me.emreihtiyar.codegraph.extract.model.ScreenFact;
import me.emreihtiyar.codegraph.extract.model.SourceRef;
import me.emreihtiyar.codegraph.graph.GraphBatch;
import me.emreihtiyar.codegraph.graph.NodeIds;
import me.emreihtiyar.codegraph.graph.NodeLabel;
import me.emreihtiyar.codegraph.graph.NodeRecord;
import me.emreihtiyar.codegraph.graph.RelRecord;
import me.emreihtiyar.codegraph.graph.RelType;
import me.emreihtiyar.codegraph.link.LinkResult;
import org.springframework.stereotype.Component;

/**
 * Olguları ve bağları graf kayıtlarına çevirir (PRD FR-10, FR-11, §8.6) ve servis düzeyi {@code DEPENDS_ON}
 * kenarlarını türetir.
 *
 * <p>Doğal anahtarlar: repo'ya ait düğümler {@code repo|fqn} (ya da {@code repo|dosya#ad}), servisler adıyla,
 * endpoint'ler {@code servis|METOT|normalizePath}, tablolar {@code şema|ad} (küçük harf) ile anahtarlanır; aynı tabloyu
 * kullanan servisler tek bir Table düğümünde buluşur.
 *
 * <p>Repolar arası hedefler (çağrı, override, entity) önce aynı repoda, sonra LIBRARY repolarında aranır. Hiçbir
 * yerde bulunamayan hedef kenara dönüşmez; uyarı olarak raporlanır. Hedefi olmayan (UNRESOLVED) çağrıların ifadeleri
 * çağıran metodun {@code unresolvedCalls} özelliğinde tutulur.
 */
@Component
public class GraphLoader {

    /** @param warnings Load aşamasında bağlanamayan hedefler */
    public record Result(GraphBatch batch, List<String> warnings) {}

    public Result build(List<AnalyzedRepo> repos, LinkResult links, Instant analyzedAt) {
        return new Builder(repos, links, analyzedAt).build();
    }

    private static final class Builder {

        private final List<AnalyzedRepo> repos;
        private final LinkResult links;
        private final Instant analyzedAt;

        private final Map<String, NodeRecord> nodes = new LinkedHashMap<>();
        private final List<RelRecord> rels = new ArrayList<>();
        private final Set<String> relKeys = new HashSet<>();
        private final List<String> warnings = new ArrayList<>();

        /** Tür → fqn → onu tanımlayan repolar (tanım sırasıyla). */
        private final Map<String, Map<String, List<String>>> owners = new HashMap<>();
        private final Set<String> libraryRepos = new HashSet<>();
        private final Map<String, String> serviceOfRepo = new HashMap<>();
        private final Map<String, Integer> dependsOn = new LinkedHashMap<>();

        Builder(List<AnalyzedRepo> repos, LinkResult links, Instant analyzedAt) {
            this.repos = repos;
            this.links = links;
            this.analyzedAt = analyzedAt;
        }

        Result build() {
            index();
            for (AnalyzedRepo repo : repos) {
                repoAndService(repo);
            }
            for (AnalyzedRepo repo : repos) {
                RepoFacts f = repo.facts();
                classesAndMethods(f);
                endpoints(f);
                feign(f);
                jpa(f);
                calls(f);
                frontend(f);
            }
            linksToEndpoints();
            dependencies();
            return new Result(new GraphBatch(new ArrayList<>(nodes.values()), rels), warnings);
        }

        // ---- indeks ----

        private void index() {
            for (AnalyzedRepo repo : repos) {
                RepoFacts f = repo.facts();
                if (repo.definition().layer() == Layer.LIBRARY) {
                    libraryRepos.add(repo.name());
                } else {
                    serviceOfRepo.put(repo.name(), f.service().name());
                }
                f.methods().forEach(m -> own("method", m.fqn(), repo.name()));
                f.feignMethods().forEach(m -> own("feignMethod", m.fqn(), repo.name()));
                f.repositoryMethods().forEach(m -> own("repositoryMethod", m.fqn(), repo.name()));
                f.entities().forEach(e -> own("entity", e.fqn(), repo.name()));
                f.classes().forEach(c -> own("class", c.fqn(), repo.name()));
            }
        }

        private void own(String kind, String fqn, String repo) {
            owners.computeIfAbsent(kind, k -> new HashMap<>()).computeIfAbsent(fqn, k -> new ArrayList<>()).add(repo);
        }

        /** Hedefin sahibi: önce aynı repo, sonra ilk LIBRARY repo; yoksa null. */
        private String owner(String kind, String fqn, String fromRepo) {
            List<String> candidates = owners.getOrDefault(kind, Map.of()).getOrDefault(fqn, List.of());
            if (candidates.contains(fromRepo)) {
                return fromRepo;
            }
            return candidates.stream().filter(libraryRepos::contains).findFirst().orElse(null);
        }

        // ---- repo / servis ----

        private void repoAndService(AnalyzedRepo repo) {
            RepoFacts f = repo.facts();
            String repoId = node(NodeLabel.CODE_REPO, repo.name(), props(
                    "name", repo.name(),
                    "path", repo.definition().path().toString(),
                    "layer", repo.definition().layer(),
                    "analyzedAt", analyzedAt.toString(),
                    "commitHash", repo.commitHash()));
            if (repo.definition().layer() == Layer.LIBRARY) {
                return;
            }
            String serviceId = node(NodeLabel.SERVICE, f.service().name(), props(
                    "name", f.service().name(),
                    "layer", f.service().layer(),
                    "contextPath", f.service().contextPath(),
                    "gatewayPrefix", f.service().gatewayPrefix(),
                    "repo", repo.name(),
                    "analyzed", true));
            rel(RelType.CONTAINS, repoId, serviceId);
        }

        /** Analizde olmayan bir servis (ör. Feign hedefi); sistem görünümünde gösterilir. */
        private String externalService(String name) {
            String id = NodeIds.of(NodeLabel.SERVICE, name);
            if (!nodes.containsKey(id)) {
                node(NodeLabel.SERVICE, name, props("name", name, "analyzed", false));
            }
            return id;
        }

        private static String serviceId(String name) {
            return NodeIds.of(NodeLabel.SERVICE, name);
        }

        // ---- Java ----

        private void classesAndMethods(RepoFacts f) {
            String repoId = NodeIds.of(NodeLabel.CODE_REPO, f.repoName());
            for (ClassFact c : f.classes()) {
                String id = node(NodeLabel.CLASS, key(f, c.fqn()), withSource(owned(f, props(
                        "fqn", c.fqn(),
                        "name", simpleName(c.fqn()),
                        "kind", c.kind(),
                        "isInterface", c.isInterface(),
                        "isAbstract", c.isAbstract(),
                        "superClass", c.superClass(),
                        "interfaces", c.interfaces())), c.source()));
                rel(RelType.CONTAINS, repoId, id);
            }
            Map<String, List<String>> unresolved = new HashMap<>();
            for (CallFact call : f.calls()) {
                if (call.resolution() == Resolution.UNRESOLVED || call.targetFqn() == null) {
                    unresolved.computeIfAbsent(call.callerFqn(), k -> new ArrayList<>()).add(call.targetText());
                }
            }
            for (MethodFact m : f.methods()) {
                Map<String, Object> p = owned(f, props(
                        "fqn", m.fqn(),
                        "name", m.name(),
                        "signature", m.signature(),
                        "className", m.classFqn(),
                        "visibility", m.visibility(),
                        "isStatic", m.isStatic(),
                        "isAbstract", m.isAbstract(),
                        "isAccessor", m.isAccessor(),
                        "synthetic", m.synthetic(),
                        "code", m.code(),
                        "unresolvedCalls", unresolved.get(m.fqn())));
                String id = node(NodeLabel.METHOD, key(f, m.fqn()), withSource(p, m.source()));
                String classId = NodeIds.of(NodeLabel.CLASS, key(f, m.classFqn()));
                if (nodes.containsKey(classId)) {
                    rel(RelType.DECLARES, classId, id);
                }
            }
            for (OverrideFact o : f.overrides()) {
                String target = owner("method", o.overriddenFqn(), f.repoName());
                if (target == null) {
                    warnings.add(f.repoName() + ": override hedefi bulunamadı: " + o.methodFqn() + " → " + o.overriddenFqn());
                    continue;
                }
                rel(RelType.OVERRIDES, NodeIds.of(NodeLabel.METHOD, key(f, o.methodFqn())),
                        NodeIds.of(NodeLabel.METHOD, NodeIds.key(target, o.overriddenFqn())));
            }
        }

        private void endpoints(RepoFacts f) {
            String serviceId = serviceId(f.service().name());
            for (EndpointFact e : f.endpoints()) {
                String id = node(NodeLabel.ENDPOINT, endpointKey(f.service().name(), e.httpMethod(), e.normalizedPath()),
                        props(
                                "httpMethod", e.httpMethod(),
                                "path", e.path(),
                                "normalizedPath", e.normalizedPath(),
                                "service", f.service().name(),
                                "repo", f.repoName()));
                if (f.layer() != Layer.LIBRARY) {
                    rel(RelType.EXPOSES, serviceId, id);
                }
                String handler = NodeIds.of(NodeLabel.METHOD, key(f, e.handlerMethodFqn()));
                if (nodes.containsKey(handler)) {
                    rel(RelType.HANDLED_BY, id, handler);
                } else {
                    warnings.add(f.repoName() + ": endpoint handler'ı bulunamadı: " + e.handlerMethodFqn());
                }
            }
        }

        private void feign(RepoFacts f) {
            Map<String, LinkResult.Link> methodLinks = new HashMap<>();
            Map<String, LinkResult.FeignLink> clientLinks = new HashMap<>();
            for (LinkResult.FeignLink link : links.feignClients()) {
                if (link.repo().equals(f.repoName())) {
                    clientLinks.put(link.clientFqn(), link);
                    link.methods().forEach(m -> methodLinks.put(m.sourceKey(), m));
                }
            }
            for (FeignClientFact c : f.feignClients()) {
                String id = node(NodeLabel.FEIGN_CLIENT, key(f, c.fqn()), withSource(owned(f, props(
                        "fqn", c.fqn(),
                        "name", simpleName(c.fqn()),
                        "rawName", c.rawName(),
                        "targetServiceName", c.targetServiceName(),
                        "path", c.path())), c.source()));
                LinkResult.FeignLink link = clientLinks.get(c.fqn());
                if (link != null && link.targetService() != null) {
                    String target = link.targetRepo() != null ? serviceId(link.targetService())
                            : externalService(link.targetService());
                    rel(RelType.TARGETS, id, target);
                }
            }
            for (FeignMethodFact m : f.feignMethods()) {
                LinkResult.Link link = methodLinks.get(m.fqn());
                String id = node(NodeLabel.FEIGN_METHOD, key(f, m.fqn()), withSource(owned(f, props(
                        "fqn", m.fqn(),
                        "name", methodName(m.fqn()),
                        "clientFqn", m.clientFqn(),
                        "httpMethod", m.httpMethod(),
                        "path", m.path(),
                        "normalizedPath", m.normalizedPath(),
                        "code", m.code(),
                        "targetService", link == null ? null : link.targetService(),
                        "resolution", link == null ? Resolution.UNRESOLVED : link.resolution(),
                        "unresolvedReason", link == null ? null : link.reason())), m.source()));
                rel(RelType.DECLARES, NodeIds.of(NodeLabel.FEIGN_CLIENT, key(f, m.clientFqn())), id);
            }
        }

        private void jpa(RepoFacts f) {
            for (EntityFact e : f.entities()) {
                String id = node(NodeLabel.ENTITY, key(f, e.fqn()), withSource(owned(f, props(
                        "fqn", e.fqn(),
                        "name", simpleName(e.fqn()),
                        "tableName", e.tableName(),
                        "schema", e.schema(),
                        "code", e.code())), e.source()));
                String table = node(NodeLabel.TABLE, tableKey(e.schema(), e.tableName()), props(
                        "name", e.tableName(),
                        "schema", e.schema(),
                        "qualifiedName", e.schema() == null ? e.tableName() : e.schema() + "." + e.tableName()));
                rel(RelType.MAPPED_TO, id, table);
            }
            for (EntityRelationFact r : f.entityRelations()) {
                String to = owner("entity", r.toEntityFqn(), f.repoName());
                if (to == null) {
                    warnings.add(f.repoName() + ": ilişki hedef entity'si bulunamadı: " + r.toEntityFqn());
                    continue;
                }
                rel(RelType.RELATES_TO, NodeIds.of(NodeLabel.ENTITY, key(f, r.fromEntityFqn())),
                        NodeIds.of(NodeLabel.ENTITY, NodeIds.key(to, r.toEntityFqn())),
                        props("type", r.type(), "field", r.field()));
            }
            Map<String, String> entityOfRepository = new HashMap<>();
            for (JpaRepositoryFact r : f.jpaRepositories()) {
                node(NodeLabel.JPA_REPOSITORY, key(f, r.fqn()), withSource(owned(f, props(
                        "fqn", r.fqn(),
                        "name", simpleName(r.fqn()),
                        "entityFqn", r.entityFqn())), r.source()));
                entityOfRepository.put(r.fqn(), r.entityFqn());
            }
            for (RepositoryMethodFact m : f.repositoryMethods()) {
                String id = node(NodeLabel.REPOSITORY_METHOD, key(f, m.fqn()), owned(f, props(
                        "fqn", m.fqn(),
                        "name", m.name(),
                        "repositoryFqn", m.repositoryFqn(),
                        "accessType", m.accessType(),
                        "query", m.query(),
                        "inherited", m.inherited())));
                rel(RelType.DECLARES, NodeIds.of(NodeLabel.JPA_REPOSITORY, key(f, m.repositoryFqn())), id);
                String entityFqn = entityOfRepository.get(m.repositoryFqn());
                String entityRepo = entityFqn == null ? null : owner("entity", entityFqn, f.repoName());
                if (entityRepo == null) {
                    warnings.add(f.repoName() + ": repository entity'si bulunamadı: " + m.repositoryFqn());
                    continue;
                }
                rel(m.accessType() == AccessType.WRITE ? RelType.WRITES : RelType.READS, id,
                        NodeIds.of(NodeLabel.ENTITY, NodeIds.key(entityRepo, entityFqn)));
            }
        }

        private void calls(RepoFacts f) {
            for (CallFact call : f.calls()) {
                if (call.targetFqn() == null || call.resolution() == Resolution.UNRESOLVED) {
                    continue;
                }
                NodeLabel label;
                RelType type;
                String kind;
                switch (call.targetKind()) {
                    case FEIGN_METHOD -> { label = NodeLabel.FEIGN_METHOD; type = RelType.INVOKES; kind = "feignMethod"; }
                    case REPOSITORY_METHOD -> {
                        label = NodeLabel.REPOSITORY_METHOD; type = RelType.USES_REPOSITORY; kind = "repositoryMethod";
                    }
                    default -> { label = NodeLabel.METHOD; type = RelType.CALLS; kind = "method"; }
                }
                String targetRepo = owner(kind, call.targetFqn(), f.repoName());
                if (targetRepo == null) {
                    warnings.add(f.repoName() + ": çağrı hedefi bulunamadı: " + call.callerFqn() + " → " + call.targetFqn());
                    continue;
                }
                Map<String, Object> p = props(
                        "order", call.order(),
                        "line", call.line(),
                        "conditional", call.conditional(),
                        "inLoop", call.inLoop(),
                        "async", call.async());
                if (type == RelType.CALLS) {
                    p.putAll(props(
                            "conditionText", call.conditionText(),
                            "viaLambda", call.viaLambda(),
                            "recursive", call.recursive(),
                            "resolution", call.resolution()));
                } else {
                    p.putAll(props("conditionText", call.conditionText(), "viaLambda", call.viaLambda()));
                }
                rel(type, NodeIds.of(NodeLabel.METHOD, key(f, call.callerFqn())),
                        NodeIds.of(label, NodeIds.key(targetRepo, call.targetFqn())), p);
                if (type == RelType.INVOKES) {
                    feignDependency(f.repoName(), targetRepo, call.targetFqn());
                }
            }
        }

        // ---- frontend ----

        private void frontend(RepoFacts f) {
            String repoId = NodeIds.of(NodeLabel.CODE_REPO, f.repoName());
            Map<String, LinkResult.Link> apiLinks = new HashMap<>();
            links.apiCalls().stream().filter(l -> l.repo().equals(f.repoName())).forEach(l -> apiLinks.put(l.sourceKey(), l));
            for (ComponentFact c : f.components()) {
                String id = node(NodeLabel.COMPONENT, key(f, c.key()), withSource(owned(f, props(
                        "name", c.name(),
                        "componentKey", c.key(),
                        "code", c.code())), c.source()));
                rel(RelType.CONTAINS, repoId, id);
            }
            for (ScreenFact s : f.screens()) {
                String id = node(NodeLabel.SCREEN, NodeIds.key(f.repoName(), s.routePath(), s.componentKey()), owned(f, props(
                        "routePath", s.routePath(),
                        "name", s.name(),
                        "componentKey", s.componentKey())));
                rel(RelType.HAS_SCREEN, serviceId(f.service().name()), id);
                rel(RelType.RENDERS, id, NodeIds.of(NodeLabel.COMPONENT, key(f, s.componentKey())));
            }
            for (RenderFact r : f.renders()) {
                rel(RelType.RENDERS, NodeIds.of(NodeLabel.COMPONENT, key(f, r.parentComponentKey())),
                        NodeIds.of(NodeLabel.COMPONENT, key(f, r.childComponentKey())));
            }
            for (ApiCallFact a : f.apiCalls()) {
                LinkResult.Link link = apiLinks.get(a.key());
                node(NodeLabel.API_CALL, key(f, a.key()), withSource(owned(f, props(
                        "name", a.name(),
                        "apiCallKey", a.key(),
                        "httpMethod", a.httpMethod(),
                        "urlTemplate", a.urlTemplate(),
                        "normalizedPath", a.normalizedPath(),
                        "targetPrefix", a.targetPrefix(),
                        "code", a.code(),
                        "targetService", link == null ? null : link.targetService(),
                        "resolution", link == null ? Resolution.UNRESOLVED : link.resolution(),
                        "unresolvedReason", link == null ? null : link.reason())), a.source()));
                if (link != null && link.targetService() != null) {
                    dependency(f.service().name(), link.targetService());
                }
            }
            for (ApiCallUsageFact u : f.apiCallUsages()) {
                rel(RelType.USES_API_CALL, NodeIds.of(NodeLabel.COMPONENT, key(f, u.componentKey())),
                        NodeIds.of(NodeLabel.API_CALL, key(f, u.apiCallKey())),
                        props("order", u.order(), "line", u.line(), "via", u.via(), "hook", u.hook()));
            }
        }

        // ---- repolar arası ----

        private void linksToEndpoints() {
            for (LinkResult.Link link : links.apiCalls()) {
                String from = NodeIds.of(NodeLabel.API_CALL, NodeIds.key(link.repo(), link.sourceKey()));
                for (LinkResult.EndpointRef target : link.targets()) {
                    rel(RelType.REQUESTS, from, endpointId(target), props("resolution", link.resolution()));
                }
            }
            for (LinkResult.Link link : links.feignMethods()) {
                String from = NodeIds.of(NodeLabel.FEIGN_METHOD, NodeIds.key(link.repo(), link.sourceKey()));
                for (LinkResult.EndpointRef target : link.targets()) {
                    rel(RelType.RESOLVES_TO, from, endpointId(target), props("resolution", link.resolution()));
                }
            }
            // Çağrısı olmasa da Feign istemcisi tanımlı servisler arasında bağımlılık vardır.
            for (LinkResult.FeignLink client : links.feignClients()) {
                String caller = serviceOfRepo.get(client.repo());
                if (caller != null && client.targetService() != null) {
                    dependsOn.putIfAbsent(caller + "\u0000" + client.targetService(), 0);
                }
            }
        }

        /** Feign çağrısı: çağıran reponun servisi → Feign istemcisinin hedef servisi. */
        private void feignDependency(String callerRepo, String feignRepo, String feignMethodFqn) {
            String caller = serviceOfRepo.get(callerRepo);
            if (caller == null) {
                return;
            }
            for (LinkResult.FeignLink client : links.feignClients()) {
                if (client.repo().equals(feignRepo) && client.targetService() != null
                        && client.methods().stream().anyMatch(m -> m.sourceKey().equals(feignMethodFqn))) {
                    dependency(caller, client.targetService());
                    return;
                }
            }
        }

        private void dependency(String from, String to) {
            if (!from.equals(to)) {
                dependsOn.merge(from + "\u0000" + to, 1, Integer::sum);
            }
        }

        private void dependencies() {
            dependsOn.forEach((pair, count) -> {
                String[] parts = pair.split("\u0000");
                String to = nodes.containsKey(serviceId(parts[1])) ? serviceId(parts[1]) : externalService(parts[1]);
                rel(RelType.DEPENDS_ON, serviceId(parts[0]), to, props("callCount", count));
            });
        }

        // ---- yardımcılar ----

        private String node(NodeLabel label, String naturalKey, Map<String, Object> properties) {
            NodeRecord record = NodeRecord.of(label, naturalKey, properties);
            NodeRecord previous = nodes.putIfAbsent(record.id(), record);
            if (previous != null && label != NodeLabel.TABLE && label != NodeLabel.SERVICE && label != NodeLabel.ENDPOINT) {
                warnings.add("Tekrarlanan " + label.label() + " anahtarı: " + naturalKey);
            }
            return record.id();
        }

        private void rel(RelType type, String from, String to) {
            rel(type, from, to, Map.of());
        }

        /** Aynı uçlar arasında aynı tip ve özellikli kenar bir kez yazılır. */
        private void rel(RelType type, String from, String to, Map<String, Object> properties) {
            RelRecord record = RelRecord.of(type, from, to, properties);
            if (relKeys.add(type + "|" + from + "|" + to + "|" + record.properties())) {
                rels.add(record);
            }
        }

        private Map<String, Object> owned(RepoFacts f, Map<String, Object> p) {
            p.put("repo", f.repoName());
            if (f.layer() != Layer.LIBRARY) {
                p.put("service", f.service().name());
            }
            return p;
        }

        private static Map<String, Object> withSource(Map<String, Object> p, SourceRef source) {
            if (source != null) {
                p.put("file", source.file());
                p.put("startLine", source.startLine());
                p.put("endLine", source.endLine());
            }
            return p;
        }

        private static Map<String, Object> props(Object... keyValues) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < keyValues.length; i += 2) {
                if (keyValues[i + 1] != null) {
                    map.put((String) keyValues[i], keyValues[i + 1]);
                }
            }
            return map;
        }

        private static String key(RepoFacts f, String localKey) {
            return NodeIds.key(f.repoName(), localKey);
        }

        static String endpointKey(String service, HttpMethod method, String normalizedPath) {
            return NodeIds.key(service, method.name(), normalizedPath);
        }

        private static String endpointId(LinkResult.EndpointRef ref) {
            return NodeIds.of(NodeLabel.ENDPOINT, endpointKey(ref.service(), ref.httpMethod(), ref.normalizedPath()));
        }

        private static String tableKey(String schema, String name) {
            return NodeIds.key(schema == null ? "" : schema.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
        }

        private static String simpleName(String fqn) {
            String s = fqn.contains("#") ? fqn.substring(0, fqn.indexOf('#')) : fqn;
            return s.substring(s.lastIndexOf('.') + 1);
        }

        private static String methodName(String fqn) {
            int hash = fqn.indexOf('#');
            int paren = fqn.indexOf('(', hash);
            return hash < 0 ? fqn : fqn.substring(hash + 1, paren < 0 ? fqn.length() : paren);
        }
    }
}

package me.emreihtiyar.codegraph.link;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.extract.PathNormalizer;
import me.emreihtiyar.codegraph.extract.model.ApiCallFact;
import me.emreihtiyar.codegraph.extract.model.EndpointFact;
import me.emreihtiyar.codegraph.extract.model.FeignClientFact;
import me.emreihtiyar.codegraph.extract.model.FeignMethodFact;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.model.Resolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Repolar arası bağlar (PRD FR-09, §8.1 Link):
 * <ul>
 *   <li><b>ApiCall → BFF Endpoint:</b> gateway sonrası ilk segment, gateway prefix'i bu değer olan BFF'i seçer. Kalan
 *       path BFF endpoint'leriyle eşleştirilir; gateway prefix'i kesmiyorsa diye prefix'li path de denenir.</li>
 *   <li><b>FeignClient → Service:</b> çözülmüş {@code name/value}; servis adı, repo adı veya {@code aliases} ile
 *       büyük-küçük harf duyarsız eşleşir.</li>
 *   <li><b>FeignMethod → Endpoint:</b> Feign path'i hedefin endpoint path'iyle (context-path dahil) eşleştirilir;
 *       eşleşme yoksa context-path eklenmiş hali denenir. O da yoksa ön ek atılmış haller denenir: {@code
 *       @FeignClient(path)} ön eki ve hedefin adlarından biri (servis adı, repo adı, alias) olan ilk segment
 *       ({@code @GetMapping("/leasing-contract/test")}). Böyle bir ön ek, analiz edilmeyen bir yerde tanımlı
 *       context-path'e ya da gateway yönlendirmesine karşılık gelebilir.</li>
 * </ul>
 * HTTP metodu aynı olmalıdır ({@code ANY} her metotla eşleşir). Birden çok endpoint eşleşirse en özgül olan(lar)
 * seçilir (bkz. {@link #mostSpecific}); yine de birden fazla kalırsa sonuç {@link Resolution#AMBIGUOUS} olur.
 */
@Component
public class Linker {

    private static final Logger log = LoggerFactory.getLogger(Linker.class);

    /** @param aliases repo adı → yapılandırmadaki Feign adı eşlemeleri ({@code aliases}) */
    public LinkResult link(List<RepoFacts> repos, Map<String, List<String>> aliases) {
        Map<String, RepoFacts> services = new LinkedHashMap<>();
        Map<String, RepoFacts> bffsByPrefix = new LinkedHashMap<>();
        List<RepoFacts> targets = repos.stream()
                .filter(r -> r.layer() != Layer.FRONTEND && r.layer() != Layer.LIBRARY)
                .toList();
        for (RepoFacts repo : targets) {
            services.putIfAbsent(lower(repo.service().name()), repo);
            if (repo.layer() == Layer.BFF && repo.service().gatewayPrefix() != null) {
                bffsByPrefix.putIfAbsent(lower(repo.service().gatewayPrefix()), repo);
            }
        }
        // Repo adı ve alias'lar, gerçek servis adlarını ezmesin diye sonra eklenir.
        for (RepoFacts repo : targets) {
            services.putIfAbsent(lower(repo.repoName()), repo);
            services.putIfAbsent(lower(lastDotted(repo.service().name())), repo);
            aliases.getOrDefault(repo.repoName(), List.of()).forEach(a -> services.putIfAbsent(lower(a), repo));
            if (repo.layer() == Layer.BFF) {
                // Gateway prefix'i çoğu zaman servis adının kendisi değil: 'LEASING.NGLA.leasing-contract-bff' →
                // URL'de 'leasing-contract-bff'. Yedek olarak repo adı, noktalı adın son parçası ve alias'lar denenir.
                bffsByPrefix.putIfAbsent(lower(repo.repoName()), repo);
                bffsByPrefix.putIfAbsent(lower(lastDotted(repo.service().name())), repo);
                aliases.getOrDefault(repo.repoName(), List.of()).forEach(a -> bffsByPrefix.putIfAbsent(lower(a), repo));
            }
        }
        log.debug("BFF gateway prefix'leri: {}", bffsByPrefix.entrySet().stream()
                .map(e -> e.getKey() + " → " + e.getValue().repoName()).toList());
        log.debug("Feign hedef adları: {}", services.entrySet().stream()
                .map(e -> e.getKey() + " → " + e.getValue().repoName()).toList());
        LinkResult result = new LinkResult(linkApiCalls(repos, bffsByPrefix), linkFeign(repos, services));
        logLinks("ApiCall", result.apiCalls());
        logLinks("Feign", result.feignMethods());
        return result;
    }

    private static void logLinks(String kind, List<LinkResult.Link> links) {
        Map<Resolution, Long> counts = new java.util.EnumMap<>(Resolution.class);
        links.forEach(l -> counts.merge(l.resolution(), 1L, Long::sum));
        log.info("{} bağları: {} çözüldü, {} belirsiz, {} çözülemedi", kind, counts.getOrDefault(Resolution.RESOLVED, 0L),
                counts.getOrDefault(Resolution.AMBIGUOUS, 0L), counts.getOrDefault(Resolution.UNRESOLVED, 0L));
        if (log.isDebugEnabled()) {
            for (LinkResult.Link l : links) {
                log.debug("{} {} [{}] {} → {}{}", kind, l.sourceKey(), l.repo(), l.resolution(),
                        l.targets().isEmpty() ? l.targetService() : l.targets().stream()
                                .map(t -> t.service() + " " + t.httpMethod() + " " + t.normalizedPath()).toList(),
                        l.reason() == null ? "" : " (" + l.reason() + ")");
            }
        }
    }

    private static List<LinkResult.Link> linkApiCalls(List<RepoFacts> repos, Map<String, RepoFacts> bffsByPrefix) {
        List<LinkResult.Link> links = new ArrayList<>();
        for (RepoFacts repo : repos) {
            for (ApiCallFact call : repo.apiCalls()) {
                if (call.targetPrefix() == null) {
                    links.add(LinkResult.Link.unresolved(repo.repoName(), call.key(), null,
                            "URL statik olarak çözülemedi: " + call.urlTemplate()));
                    continue;
                }
                RepoFacts bff = bffsByPrefix.get(lower(call.targetPrefix()));
                if (bff == null) {
                    links.add(LinkResult.Link.unresolved(repo.repoName(), call.key(), null,
                            "Gateway prefix'i '" + call.targetPrefix() + "' olan BFF yok"));
                    continue;
                }
                List<String> candidates = List.of(call.normalizedPath(),
                        PathNormalizer.join(call.targetPrefix(), call.normalizedPath()));
                links.add(resolve(repo.repoName(), call.key(), bff, call.httpMethod(), candidates,
                        bff.service().name() + " servisinde " + call.httpMethod() + " " + call.normalizedPath()
                                + " endpoint'i yok"));
            }
        }
        return links;
    }

    private static List<LinkResult.FeignLink> linkFeign(List<RepoFacts> repos, Map<String, RepoFacts> services) {
        List<LinkResult.FeignLink> out = new ArrayList<>();
        for (RepoFacts repo : repos) {
            for (FeignClientFact client : repo.feignClients()) {
                RepoFacts target = client.targetServiceName() == null ? null : services.get(lower(client.targetServiceName()));
                List<LinkResult.Link> methods = new ArrayList<>();
                for (FeignMethodFact method : repo.feignMethods()) {
                    if (method.clientFqn().equals(client.fqn())) {
                        methods.add(linkFeignMethod(repo, client, method, target, services));
                    }
                }
                out.add(new LinkResult.FeignLink(repo.repoName(), client.fqn(),
                        target != null ? target.service().name() : client.targetServiceName(),
                        target != null ? target.repoName() : null, methods));
            }
        }
        return out;
    }

    private static LinkResult.Link linkFeignMethod(
            RepoFacts repo, FeignClientFact client, FeignMethodFact method, RepoFacts target,
            Map<String, RepoFacts> services) {
        if (client.targetServiceName() == null) {
            return LinkResult.Link.unresolved(repo.repoName(), method.fqn(), null, "Feign adı çözülemedi: " + client.rawName());
        }
        if (target == null) {
            return LinkResult.Link.unresolved(repo.repoName(), method.fqn(), client.targetServiceName(),
                    "Hedef servis analizde yok: " + client.targetServiceName()
                            + " (analizdeyse repos dosyasında aliases'ına ekleyin)");
        }
        String contextPath = target.service().contextPath();
        List<String> candidates = new ArrayList<>(List.of(method.normalizedPath()));
        if (!contextPath.isEmpty()) {
            candidates.add(PathNormalizer.join(contextPath, method.normalizedPath()));
        }
        Set<String> stripped = new LinkedHashSet<>();
        String withoutClientPath = withoutPrefix(method.normalizedPath(), PathNormalizer.normalize(client.path()));
        if (withoutClientPath != null) {
            stripped.add(withoutClientPath);
        }
        String withoutServiceName = withoutTargetName(method.normalizedPath(), target, services);
        if (withoutServiceName != null) {
            stripped.add(withoutServiceName);
        }
        for (String path : stripped) {
            candidates.add(path);
            if (!contextPath.isEmpty()) {
                candidates.add(PathNormalizer.join(contextPath, path));
            }
        }
        return resolve(repo.repoName(), method.fqn(), target, method.httpMethod(), candidates,
                target.service().name() + " servisinde " + method.httpMethod() + " " + method.normalizedPath()
                        + (stripped.isEmpty() ? "" : " (ya da " + String.join(", ", stripped) + ")") + " endpoint'i yok");
    }

    private static LinkResult.Link resolve(String repo, String sourceKey, RepoFacts target, HttpMethod method,
            List<String> candidatePaths, String notFoundReason) {
        for (String path : candidatePaths) {
            List<EndpointFact> matches = target.endpoints().stream()
                    .filter(e -> e.httpMethod() == method || e.httpMethod() == HttpMethod.ANY || method == HttpMethod.ANY)
                    .filter(e -> PathNormalizer.matches(e.normalizedPath(), path))
                    .toList();
            if (!matches.isEmpty()) {
                List<LinkResult.EndpointRef> refs = mostSpecific(matches, path, method).stream()
                        .map(e -> new LinkResult.EndpointRef(target.repoName(), target.service().name(), e.httpMethod(),
                                e.normalizedPath()))
                        .distinct()
                        .toList();
                return new LinkResult.Link(repo, sourceKey, target.service().name(), refs,
                        refs.size() == 1 ? Resolution.RESOLVED : Resolution.AMBIGUOUS, null);
            }
        }
        return LinkResult.Link.unresolved(repo, sourceKey, target.service().name(), notFoundReason);
    }

    /**
     * Spring'in eşleme önceliğine benzer seçim: path'i segment segment birebir tutan (sabit-sabit ve joker-joker)
     * endpoint'ler önce gelir ({@code /a/me} isteği {@code /a/me}'ye, {@code /a/{*}} isteği {@code /a/{*}}'ya); eşitlikte
     * HTTP metodu açıkça aynı olan, {@code ANY}'ye tercih edilir.
     */
    private static List<EndpointFact> mostSpecific(List<EndpointFact> matches, String path, HttpMethod method) {
        int best = -1;
        List<EndpointFact> out = new ArrayList<>();
        for (EndpointFact e : matches) {
            int score = exactSegments(e.normalizedPath(), path) * 2 + (e.httpMethod() == method ? 1 : 0);
            if (score > best) {
                best = score;
                out.clear();
            }
            if (score == best) {
                out.add(e);
            }
        }
        return out;
    }

    private static int exactSegments(String a, String b) {
        String[] x = a.split("/");
        String[] y = b.split("/");
        int score = 0;
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (x[i].equals(y[i])) {
                score++;
            }
        }
        return score;
    }

    /** {@code /a/b/c} − {@code /a} → {@code /b/c}; ön ek yoksa ya da path'in tamamıysa null. */
    private static String withoutPrefix(String path, String prefix) {
        if (prefix.equals("/") || !path.startsWith(prefix + "/")) {
            return null;
        }
        return path.substring(prefix.length());
    }

    /** İlk segment hedefin adlarından biriyse ({@code /leasing-contract/test}) onsuz hali; değilse null. */
    private static String withoutTargetName(String path, RepoFacts target, Map<String, RepoFacts> services) {
        int end = path.indexOf('/', 1);
        if (end < 0) {
            return null;
        }
        return services.get(lower(path.substring(1, end))) == target ? path.substring(end) : null;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    /** {@code LEASING.NGLA.leasing-contract-bff} → {@code leasing-contract-bff}. */
    private static String lastDotted(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}

package me.emreihtiyar.codegraph.link;

import java.util.List;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;
import me.emreihtiyar.codegraph.extract.model.Resolution;

/**
 * Link aşamasının çıktısı (PRD FR-09). Çözümlenemeyen bağlar da tutulur; grafta kaynak düğümün
 * {@code resolution} özelliğine ve analiz raporuna yazılır.
 */
public record LinkResult(List<Link> apiCalls, List<FeignLink> feignClients) {

    public LinkResult {
        apiCalls = List.copyOf(apiCalls);
        feignClients = List.copyOf(feignClients);
    }

    /** Hedef endpoint: Endpoint düğümünün doğal anahtarı (servis, metot, normalize path). */
    public record EndpointRef(String repo, String service, HttpMethod httpMethod, String normalizedPath) {}

    /**
     * Bir ApiCall'ın ya da Feign metodunun bağı.
     *
     * @param sourceKey ApiCall anahtarı ({@code dosya#ad}) ya da Feign metodu FQN'i
     * @param targetService hedef servis; endpoint bulunamasa da servis belli olabilir (DEPENDS_ON için). Bilinmiyorsa null
     * @param targets       RESOLVED'da bir, AMBIGUOUS'ta birden çok, UNRESOLVED'da sıfır endpoint
     * @param reason        yalnızca UNRESOLVED için açıklama
     */
    public record Link(String repo, String sourceKey, String targetService, List<EndpointRef> targets,
            Resolution resolution, String reason) {

        public Link {
            targets = List.copyOf(targets);
        }

        static Link unresolved(String repo, String sourceKey, String targetService, String reason) {
            return new Link(repo, sourceKey, targetService, List.of(), Resolution.UNRESOLVED, reason);
        }
    }

    /**
     * @param targetService çözülmüş Feign adı; çözülemediyse null
     * @param targetRepo    hedef servisin reposu; analizde yoksa null
     */
    public record FeignLink(String repo, String clientFqn, String targetService, String targetRepo, List<Link> methods) {

        public FeignLink {
            methods = List.copyOf(methods);
        }
    }

    public List<Link> feignMethods() {
        return feignClients.stream().flatMap(c -> c.methods().stream()).toList();
    }
}

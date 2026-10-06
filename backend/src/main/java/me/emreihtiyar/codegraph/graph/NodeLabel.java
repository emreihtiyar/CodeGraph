package me.emreihtiyar.codegraph.graph;

/**
 * Graf düğüm etiketleri (PRD §8.6). Her düğüm ayrıca ortak {@link #BASE} etiketini ve benzersiz {@code id}
 * özelliğini taşır; kenarlar bu sayede etiketten bağımsız olarak id ile bağlanır.
 */
public enum NodeLabel {
    CODE_REPO("CodeRepo", "repo"),
    SERVICE("Service", "service"),
    SCREEN("Screen", "screen"),
    COMPONENT("Component", "component"),
    API_CALL("ApiCall", "apicall"),
    ENDPOINT("Endpoint", "endpoint"),
    CLASS("Class", "class"),
    METHOD("Method", "method"),
    FEIGN_CLIENT("FeignClient", "feignclient"),
    FEIGN_METHOD("FeignMethod", "feignmethod"),
    JPA_REPOSITORY("JpaRepository", "jparepo"),
    REPOSITORY_METHOD("RepositoryMethod", "repomethod"),
    ENTITY("Entity", "entity"),
    TABLE("Table", "table"),
    ANALYSIS_RUN("AnalysisRun", "run");

    /** Tüm düğümlerde bulunan ortak etiket. */
    public static final String BASE = "Node";

    private final String label;
    private final String idPrefix;

    NodeLabel(String label, String idPrefix) {
        this.label = label;
        this.idPrefix = idPrefix;
    }

    public String label() {
        return label;
    }

    public String idPrefix() {
        return idPrefix;
    }

    public static NodeLabel fromLabel(String label) {
        for (NodeLabel value : values()) {
            if (value.label.equals(label)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Bilinmeyen etiket: " + label);
    }
}

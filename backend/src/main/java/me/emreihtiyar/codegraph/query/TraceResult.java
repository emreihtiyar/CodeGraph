package me.emreihtiyar.codegraph.query;

import java.util.List;

/**
 * İleri ya da geri iz (FR-16, FR-17).
 *
 * @param direction {@code FORWARD} ya da {@code BACKWARD}
 */
public record TraceResult(String direction, NodeSummary start, Subgraph graph, Summary summary) {

    /**
     * Izdeki önemli düğümler.
     *
     * @param apiCalls   ekranlardan çıkan istekler (ApiCall), URL'ye göre sıralı
     * @param services   katman → servis adları (katman sırasıyla)
     * @param unresolved hedefi çözülemeyen ApiCall/Feign metotları
     */
    public record Summary(
            List<NodeSummary> screens,
            List<NodeSummary> apiCalls,
            List<LayerServices> services,
            List<NodeSummary> endpoints,
            List<TableAccess> tables,
            List<NodeSummary> unresolved) {}

    public record LayerServices(String layer, List<String> services) {}

    /** @param access {@code READ} ve/veya {@code WRITE} */
    public record TableAccess(String id, String name, List<String> access) {}
}

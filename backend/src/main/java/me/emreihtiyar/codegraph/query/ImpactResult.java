package me.emreihtiyar.codegraph.query;

import java.util.List;
import java.util.Map;

/**
 * Etki analizi (FR-21): düğümü doğrudan ya da dolaylı kullanan tüm yukarı akış düğümleri.
 *
 * @param countsByLabel etiket → etkilenen düğüm sayısı
 */
public record ImpactResult(
        NodeSummary target,
        List<Affected> affected,
        Map<String, Long> countsByLabel,
        List<TraceResult.LayerServices> services,
        List<String> screens,
        boolean truncated,
        String note) {

    /** @param distance hedefe kenar sayısı olarak uzaklık (1 = doğrudan kullanan) */
    public record Affected(NodeSummary node, int distance) {}
}

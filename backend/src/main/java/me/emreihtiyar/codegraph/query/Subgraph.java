package me.emreihtiyar.codegraph.query;

import java.util.List;

/**
 * Bir sorgunun döndürdüğü alt graf.
 *
 * @param truncated düğüm sınırı aşıldığı için genişletme durduruldu
 * @param note      sınır aşıldıysa daraltma önerisi
 */
public record Subgraph(List<NodeSummary> nodes, List<EdgeView> edges, boolean truncated, String note) {

    public Subgraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}

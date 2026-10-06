package me.emreihtiyar.codegraph.graph;

import java.util.List;

public record GraphBatch(List<NodeRecord> nodes, List<RelRecord> relationships) {

    public GraphBatch {
        nodes = List.copyOf(nodes);
        relationships = List.copyOf(relationships);
    }
}

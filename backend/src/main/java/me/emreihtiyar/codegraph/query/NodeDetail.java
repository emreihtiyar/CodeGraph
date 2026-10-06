package me.emreihtiyar.codegraph.query;

import java.util.List;

/**
 * Düğüm detayı ve komşuları.
 *
 * @param outgoingTotal giden kenar sayısı; listede en fazla {@link GraphQueryService#NEIGHBOR_LIMIT} tanesi var
 */
public record NodeDetail(
        NodeSummary node,
        boolean hasCode,
        List<Neighbor> outgoing,
        List<Neighbor> incoming,
        long outgoingTotal,
        long incomingTotal) {

    public record Neighbor(EdgeView edge, NodeSummary node) {}
}

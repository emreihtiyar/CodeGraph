package me.emreihtiyar.codegraph.graph;

import java.util.List;

/** Sorgu sonucundaki bir yol: {@code nodes.size() == relationships.size() + 1}. */
public record GraphPath(List<GraphNode> nodes, List<GraphRelationship> relationships) {}

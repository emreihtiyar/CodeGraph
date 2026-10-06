package me.emreihtiyar.codegraph.graph;

import java.util.Map;
import java.util.Set;

/** Sorgu sonucundaki bir düğüm; sürücüden bağımsız. {@code labels} ortak {@link NodeLabel#BASE} etiketini içermez. */
public record GraphNode(String id, Set<String> labels, Map<String, Object> properties) {}

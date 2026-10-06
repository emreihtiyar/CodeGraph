package me.emreihtiyar.codegraph.graph;

import java.util.Map;

/** Sorgu sonucundaki bir ilişki; uçlar düğüm id'leriyle verilir. */
public record GraphRelationship(String type, String startId, String endId, Map<String, Object> properties) {}

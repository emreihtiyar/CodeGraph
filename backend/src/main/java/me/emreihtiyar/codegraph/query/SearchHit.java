package me.emreihtiyar.codegraph.query;

/** @param matchedField eşleşen alan ({@code name}, {@code fqn}, {@code path}, {@code routePath} …) */
public record SearchHit(NodeSummary node, String matchedField, int score) {}

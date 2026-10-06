package me.emreihtiyar.codegraph.extract.model;

/** @param schema {@code @Table(schema)}; yoksa null */
public record EntityFact(String fqn, String tableName, String schema, SourceRef source, String code) {}

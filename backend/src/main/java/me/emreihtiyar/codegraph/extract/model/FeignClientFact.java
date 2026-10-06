package me.emreihtiyar.codegraph.extract.model;

/**
 * @param rawName           anotasyondaki ham {@code name/value} (placeholder içerebilir)
 * @param targetServiceName placeholder'ları çözülmüş hedef servis adı; çözülemediyse null
 */
public record FeignClientFact(String fqn, String rawName, String targetServiceName, String path, SourceRef source) {}

package me.emreihtiyar.codegraph.extract.model;

/**
 * @param path           context-path + sınıf + metot mapping'i birleşmiş tam path
 * @param normalizedPath path değişkenleri {@code {*}} olarak normalize edilmiş hali (PRD FR-09)
 */
public record EndpointFact(HttpMethod httpMethod, String path, String normalizedPath, String handlerMethodFqn) {}

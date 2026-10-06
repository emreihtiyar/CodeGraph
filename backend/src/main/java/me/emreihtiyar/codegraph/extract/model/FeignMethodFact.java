package me.emreihtiyar.codegraph.extract.model;

/** @param path Feign {@code path} niteliği + metot mapping'i */
public record FeignMethodFact(
        String fqn,
        String clientFqn,
        HttpMethod httpMethod,
        String path,
        String normalizedPath,
        SourceRef source,
        String code) {}

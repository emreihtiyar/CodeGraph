package me.emreihtiyar.codegraph.query;

/**
 * Bir düğümün kod bloğu (FR-11, FR-18). Endpoint için handler metodunun, ekran için component'in, tablo için
 * entity'nin kodu döner ({@code sourceNodeId}).
 *
 * @param language {@code java}, {@code typescript}, {@code tsx}, {@code javascript}, {@code jsx}
 */
public record CodeBlock(
        String nodeId,
        String sourceNodeId,
        String name,
        String repo,
        String service,
        String file,
        Integer startLine,
        Integer endLine,
        String language,
        String code) {}

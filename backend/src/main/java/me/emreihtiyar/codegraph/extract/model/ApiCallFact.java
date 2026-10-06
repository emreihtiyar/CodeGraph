package me.emreihtiyar.codegraph.extract.model;

/**
 * ApiCalls dosyasındaki bir endpoint tanımı (PRD FR-07).
 *
 * @param key            repo içinde benzersiz anahtar: {@code dosya#ad}
 * @param urlTemplate    kaynaktaki URL şablonu
 * @param normalizedPath gateway ve BFF prefix'i sonrası kalan, normalize edilmiş path; çözülemediyse null
 * @param targetPrefix   gateway sonrası ilk segment (hedef BFF); çözülemediyse null
 */
public record ApiCallFact(
        String key,
        String name,
        HttpMethod httpMethod,
        String urlTemplate,
        String normalizedPath,
        String targetPrefix,
        SourceRef source,
        String code) {}

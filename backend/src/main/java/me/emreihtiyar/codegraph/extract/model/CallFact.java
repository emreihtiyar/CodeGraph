package me.emreihtiyar.codegraph.extract.model;

/**
 * Bir metodun içindeki tek bir çağrı (PRD FR-04). AMBIGUOUS çağrılar her aday için ayrı bir olgu olarak,
 * aynı {@code order} ile üretilir.
 *
 * @param targetFqn     çözümlenen hedef; UNRESOLVED ise null
 * @param targetText    kaynaktaki çağrı ifadesi (ör. {@code creditClient.checkLimit(req)}), raporlama için
 * @param order         çağıran metot içindeki 1 tabanlı sıra numarası
 * @param conditionText koşullu ise koşul ifadesinin metni
 */
public record CallFact(
        String callerFqn,
        CallTargetKind targetKind,
        String targetFqn,
        String targetText,
        int order,
        int line,
        boolean conditional,
        String conditionText,
        boolean inLoop,
        boolean async,
        boolean viaLambda,
        boolean recursive,
        Resolution resolution) {}

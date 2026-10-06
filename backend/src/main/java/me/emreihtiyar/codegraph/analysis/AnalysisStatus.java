package me.emreihtiyar.codegraph.analysis;

import java.time.Instant;

/**
 * Analizin anlık durumu (PRD FR-20).
 *
 * @param phase    {@code CONFIG}, {@code ANALYZE}, {@code LINK}, {@code LOAD}, {@code REPORT}
 * @param current  işlenen repo (ANALYZE aşamasında)
 * @param done     tamamlanan repo sayısı
 * @param total    toplam repo sayısı
 * @param error    FAILED ise hata mesajı
 */
public record AnalysisStatus(
        String runId,
        State state,
        String phase,
        String current,
        int done,
        int total,
        Instant startedAt,
        Instant finishedAt,
        String error) {

    public enum State {
        IDLE,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    static AnalysisStatus idle() {
        return new AnalysisStatus(null, State.IDLE, null, null, 0, 0, null, null, null);
    }
}

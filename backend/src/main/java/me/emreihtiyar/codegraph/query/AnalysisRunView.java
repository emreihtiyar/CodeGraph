package me.emreihtiyar.codegraph.query;

import me.emreihtiyar.codegraph.analysis.AnalysisReport;
import me.emreihtiyar.codegraph.analysis.AnalysisStatus;

/**
 * Bir analiz çalıştırması (FR-12, FR-20).
 *
 * @param state  {@code RUNNING}, {@code SUCCEEDED}, {@code FAILED}
 * @param status çalışıyorsa anlık ilerleme; değilse null
 * @param report tamamlandıysa rapor; liste görünümünde null
 */
public record AnalysisRunView(
        String runId,
        String state,
        String startedAt,
        String finishedAt,
        Long durationMs,
        Integer issueCount,
        String error,
        AnalysisStatus status,
        AnalysisReport report) {}

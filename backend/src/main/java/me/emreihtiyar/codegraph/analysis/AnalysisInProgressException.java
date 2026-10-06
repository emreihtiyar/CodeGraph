package me.emreihtiyar.codegraph.analysis;

/** Bir analiz sürerken yenisi başlatılmak istendi. */
public class AnalysisInProgressException extends RuntimeException {

    public AnalysisInProgressException(String runId) {
        super("Analiz zaten çalışıyor: " + runId);
    }
}

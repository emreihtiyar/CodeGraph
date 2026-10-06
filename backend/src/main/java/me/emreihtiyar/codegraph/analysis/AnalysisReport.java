package me.emreihtiyar.codegraph.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Analiz raporu (PRD FR-12): istatistikler, çözümlenemeyen ve belirsiz bağlar, parse hatası alan dosyalar.
 * {@code AnalysisRun} düğümünde JSON olarak saklanır.
 */
public record AnalysisReport(
        String runId,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        Map<String, Integer> nodeCounts,
        Map<String, Integer> relationshipCounts,
        List<RepoSummary> repos,
        List<LinkIssue> unresolvedLinks,
        List<LinkIssue> ambiguousLinks,
        List<CallIssue> ambiguousCalls,
        List<CallIssue> unresolvedCalls,
        List<FileIssue> parseIssues,
        List<String> warnings) {

    /** Repo başına özet. */
    public record RepoSummary(
            String name,
            String layer,
            String service,
            String commitHash,
            int files,
            int classes,
            int methods,
            int calls,
            int endpoints,
            int feignMethods,
            int entities,
            int apiCalls,
            int components,
            int screens,
            int issues,
            long durationMs) {}

    /**
     * @param kind       {@code API_CALL} ya da {@code FEIGN_METHOD}
     * @param source     ApiCall anahtarı ya da Feign metodu FQN'i
     * @param candidates AMBIGUOUS'ta aday endpoint'ler ({@code servis METOT path})
     */
    public record LinkIssue(
            String kind, String repo, String source, String targetService, String reason, List<String> candidates) {}

    /** @param candidates AMBIGUOUS'ta aday metotlar; UNRESOLVED'da boş */
    public record CallIssue(String repo, String caller, String expression, int line, List<String> candidates) {}

    public record FileIssue(String repo, String file, int line, String message) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** {@code AnalysisRun.report} özelliğindeki JSON'dan okur. */
    public static AnalysisReport fromJson(String json) {
        return JSON.readValue(json, AnalysisReport.class);
    }

    public String toJson() {
        return JSON.writeValueAsString(this);
    }

    static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }

    public int issueCount() {
        return unresolvedLinks.size() + ambiguousLinks.size() + parseIssues.size();
    }
}

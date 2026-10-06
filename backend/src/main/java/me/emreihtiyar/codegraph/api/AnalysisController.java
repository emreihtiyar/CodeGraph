package me.emreihtiyar.codegraph.api;

import java.util.List;
import me.emreihtiyar.codegraph.analysis.AnalysisService;
import me.emreihtiyar.codegraph.analysis.AnalysisStatus;
import me.emreihtiyar.codegraph.query.AnalysisRunView;
import me.emreihtiyar.codegraph.query.GraphQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Analiz başlatma ve analiz çalıştırmaları (PRD FR-12). */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisService analysis;
    private final GraphQueryService queries;

    public AnalysisController(AnalysisService analysis, GraphQueryService queries) {
        this.analysis = analysis;
        this.queries = queries;
    }

    /** Analizi arka planda başlatır; ilerleme {@code GET /api/analysis/{runId}} ile izlenir. Çalışan varsa 409. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RestOnly("Yazma işlemi; MCP araçları salt-okunurdur (PRD §8.8)")
    public AnalysisStatus start() {
        return analysis.start();
    }

    @GetMapping
    @McpEquivalent(ToolNames.LIST_ANALYSIS_RUNS)
    public List<AnalysisRunView> list(@RequestParam(defaultValue = "20") int limit) {
        return queries.listAnalysisRuns(Math.max(1, Math.min(limit, 200)));
    }

    /** @param id analiz id'si ya da {@code latest} */
    @GetMapping("/{id}")
    @McpEquivalent(ToolNames.GET_ANALYSIS_REPORT)
    public AnalysisRunView get(@PathVariable String id) {
        return queries.analysisRun(id);
    }
}

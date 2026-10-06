package me.emreihtiyar.codegraph.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import me.emreihtiyar.codegraph.analysis.AnalysisInProgressException;
import me.emreihtiyar.codegraph.analysis.AnalysisService;
import me.emreihtiyar.codegraph.analysis.AnalysisStatus;
import me.emreihtiyar.codegraph.query.GraphQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Analiz başlatma: grafı değiştirdiği için paylaşılan canlı bağlam yerine sahte servisle. */
@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AnalysisService analysis;

    @MockitoBean
    private GraphQueryService queries;

    @Test
    void startReturnsAcceptedWithRunId() throws Exception {
        when(analysis.start()).thenReturn(new AnalysisStatus("run-1", AnalysisStatus.State.RUNNING, "CONFIG", null, 0, 0,
                Instant.parse("2026-10-07T10:00:00Z"), null, null));
        mvc.perform(post("/api/analysis"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.state").value("RUNNING"));
    }

    @Test
    void startWhileRunningIsConflict() throws Exception {
        when(analysis.start()).thenThrow(new AnalysisInProgressException("run-1"));
        mvc.perform(post("/api/analysis"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Analiz zaten çalışıyor: run-1"));
    }
}

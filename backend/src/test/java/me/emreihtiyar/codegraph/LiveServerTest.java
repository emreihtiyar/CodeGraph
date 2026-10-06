package me.emreihtiyar.codegraph;

import me.emreihtiyar.codegraph.analysis.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Mini sistemi analiz etmiş, gerçek portta çalışan uygulama. Aynı yapılandırmayı kullanan test sınıfları Spring
 * bağlamını (ve analiz edilmiş grafı) paylaşır; grafı değiştiren testler burada yazılmamalıdır.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "codegraph.neo4j.data-dir=target/neo4j-test/${random.uuid}")
public abstract class LiveServerTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected AnalysisService analysis;

    @DynamicPropertySource
    static void miniSystem(DynamicPropertyRegistry registry) {
        registry.add("codegraph.repos-file", () -> Fixtures.miniSystemConfig().toString());
    }

    @BeforeEach
    void analyzeOnce() {
        synchronized (LiveServerTest.class) {
            if (analysis.lastReport().isEmpty()) {
                analysis.run();
            }
        }
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}

package me.emreihtiyar.codegraph.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import me.emreihtiyar.codegraph.config.CodeGraphProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Streamable HTTP MCP sunucusu (PRD §8.8): uygulamayla aynı JVM'de, {@code codegraph.mcp.endpoint} yolunda
 * (varsayılan {@code /mcp}) ayrı bir servlet olarak çalışır. stdio isteyen istemciler {@link StdioBridge} kullanır.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "codegraph.mcp.enabled", matchIfMissing = true)
public class McpServerConfiguration {

    static final String SERVER_NAME = "codegraph";
    static final String SERVER_VERSION = "0.1.0";

    static final String INSTRUCTIONS = """
            CodeGraph, mikroservis sisteminin statik analizle çıkarılmış bağımlılık grafını sorgular: React ekranları → \
            ApiCall'lar → BFF endpoint'leri → Feign çağrıları → API/adapter endpoint'leri → JPA repository'leri → \
            veritabanı tabloları. Graf son analiz anındaki kodu yansıtır (list_analysis_runs).
            Tipik akış: list_services ile sistemi tanıyın; adını bilmediğiniz şeyi search ile bulun; \
            trace_forward (ekrandan tablolara) ya da trace_backward (tablodan ekranlara) ile uçtan uca izleyin; \
            get_endpoint_orchestration ile bir BFF endpoint'inin çağrı sırasını, get_call_tree ile metot içi \
            akışı, get_code ile kaynak kodu görün. impact_analysis bir değişikliğin etkisini verir.
            Çıktılardaki [id] değerleri her araçta düğüm referansı olarak kullanılabilir. Bir iz beklenmedik yerde \
            kesiliyorsa nedeni get_analysis_report'taki çözülemeyen bağlantılardadır.""";

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport(CodeGraphProperties properties, JsonMapper jsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.mcp().endpoint())
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigins(properties.mcp().allowedOrigins())
                        .build())
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport, CodeGraphProperties properties) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, properties.mcp().endpoint());
        registration.setName("mcp");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport, CodeGraphTools tools,
            JsonMapper jsonMapper) {
        return McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions(INSTRUCTIONS)
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.specifications())
                .build();
    }
}

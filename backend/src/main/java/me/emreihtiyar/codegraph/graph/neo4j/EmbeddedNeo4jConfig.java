package me.emreihtiyar.codegraph.graph.neo4j;

import java.nio.file.Path;
import me.emreihtiyar.codegraph.config.CodeGraphProperties;
import me.emreihtiyar.codegraph.graph.GraphStore;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedded Neo4j yaşam döngüsü. Embedded veritabanı aynı anda tek bir süreç tarafından açılabildiği için REST ve
 * MCP aynı JVM'de bu tek örneği paylaşır (PRD §8.3).
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddedNeo4jConfig {

    @Bean(destroyMethod = "shutdown")
    DatabaseManagementService neo4jManagementService(CodeGraphProperties properties) {
        return create(properties.neo4j().dataDir());
    }

    @Bean
    GraphStore graphStore(DatabaseManagementService managementService) {
        var store = new EmbeddedNeo4jGraphStore(managementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME));
        store.ensureSchema();
        return store;
    }

    /** Dış erişim (Bolt) kapalıdır; veritabanına yalnızca bu uygulama üzerinden erişilir (NFR-05). */
    public static DatabaseManagementService create(Path homeDirectory) {
        return new DatabaseManagementServiceBuilder(homeDirectory.toAbsolutePath().normalize())
                .setConfig(BoltConnector.enabled, false)
                .build();
    }
}

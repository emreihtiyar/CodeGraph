package me.emreihtiyar.codegraph.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Uygulama ayarları ({@code codegraph.*}).
 *
 * @param reposFile analiz edilecek repoları tanımlayan YAML dosyası (PRD FR-01)
 * @param neo4j     Embedded Neo4j ayarları
 */
@ConfigurationProperties("codegraph")
public record CodeGraphProperties(
        @DefaultValue("./codegraph-repos.yml") Path reposFile, @DefaultValue Neo4j neo4j) {

    /** @param dataDir Embedded Neo4j'nin ana dizini (veri, log, transaction log) */
    public record Neo4j(@DefaultValue("./.codegraph/neo4j") Path dataDir) {}
}

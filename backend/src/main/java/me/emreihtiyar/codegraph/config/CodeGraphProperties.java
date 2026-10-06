package me.emreihtiyar.codegraph.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Uygulama ayarları ({@code codegraph.*}).
 *
 * <p>Analiz edilecek repolar (PRD FR-01) iki yoldan verilebilir: doğrudan {@code application.yml} içinde
 * {@code codegraph.repos} listesiyle ya da {@code codegraph.repos-file} ile gösterilen ayrı bir YAML dosyasıyla.
 * {@code codegraph.repos} doluysa dosya okunmaz. Her iki yolda da değerler ortam değişkenleriyle ezilebilir
 * (ör. {@code CODEGRAPH_REPOS_0_PATH}, {@code CODEGRAPH_REPOS_FILE}).
 *
 * @param reposFile    repoları tanımlayan YAML dosyası; {@code repos} boşsa kullanılır
 * @param repos        satır içi repo tanımları; göreli yollar çalışma dizinine göredir
 * @param placeholders application dosyalarında çözülemeyen {@code ${...}} değerleri; dosyadakileri ezer.
 *                     Noktalı anahtarlar köşeli parantezle yazılır: {@code "[clients.credit.name]": credit-api}
 * @param neo4j        Embedded Neo4j ayarları
 */
@ConfigurationProperties("codegraph")
public record CodeGraphProperties(
        @DefaultValue("./codegraph-repos.yml") Path reposFile,
        List<Repo> repos,
        Map<String, String> placeholders,
        @DefaultValue Neo4j neo4j) {

    public CodeGraphProperties {
        repos = repos == null ? List.of() : List.copyOf(repos);
        placeholders = placeholders == null ? Map.of() : Map.copyOf(placeholders);
    }

    /** Satır içi repo tanımı; alanların anlamı {@link RepoDefinition} ile aynıdır. */
    public record Repo(
            String name, String path, String layer, String serviceName, String gatewayPrefix, List<String> aliases) {}

    /** @param dataDir Embedded Neo4j'nin ana dizini (veri, log, transaction log) */
    public record Neo4j(@DefaultValue("./.codegraph/neo4j") Path dataDir) {}
}

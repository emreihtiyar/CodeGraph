package me.emreihtiyar.codegraph.graph;

import java.util.List;
import java.util.Locale;

/**
 * Kısıtlar ve indeksler (PRD FR-10, §8.6). Cypher olarak tutulur; Embedded ve sunucu Neo4j'de aynen çalışır.
 *
 * <p>Benzersizlik, {@link NodeIds} ile doğal anahtardan türetilen {@code Node.id} üzerinden sağlanır. PRD'deki
 * {@code Method.fqn}, {@code Class.fqn} gibi alanlar repolar arası çakışabileceği için (ör. her serviste aynı
 * {@code com.x.config.FeignConfig}) benzersiz değil, indekslidir.
 */
public final class GraphSchema {

    private GraphSchema() {}

    public static List<String> statements() {
        return List.of(
                "CREATE CONSTRAINT node_id IF NOT EXISTS FOR (n:Node) REQUIRE n.id IS UNIQUE",
                index("Service", "name"),
                index("Class", "fqn"),
                index("Method", "fqn"),
                index("Method", "name"),
                index("FeignClient", "targetServiceName"),
                index("Entity", "fqn"),
                index("Table", "name"),
                index("Endpoint", "normalizedPath"),
                index("ApiCall", "normalizedPath"),
                index("FeignMethod", "normalizedPath"),
                index("Screen", "routePath"),
                index("Component", "name"));
    }

    private static String index(String label, String property) {
        return "CREATE INDEX " + label.toLowerCase(Locale.ROOT) + "_" + property + " IF NOT EXISTS FOR (n:" + label + ") ON (n."
                + property + ")";
    }
}

package me.emreihtiyar.codegraph.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import me.emreihtiyar.codegraph.graph.GraphNode;

/**
 * Bir düğümün özeti: kimlik, etiket, okunabilir ad ve kod bloğu dışındaki özellikler.
 *
 * @param name okunabilir ad: metotlarda {@code Sinif.metot}, endpoint'lerde {@code GET /path}, ekranlarda route
 */
public record NodeSummary(String id, String label, String name, String service, Map<String, Object> properties) {

    /** Özetlerde taşınmayan büyük alanlar; {@code get_code} ve analiz raporu ile alınır. */
    private static final Set<String> HEAVY = Set.of("code", "report", "stats", "id", "key");

    static NodeSummary of(GraphNode node) {
        Map<String, Object> props = new LinkedHashMap<>();
        node.properties().forEach((k, v) -> {
            if (!HEAVY.contains(k)) {
                props.put(k, v);
            }
        });
        String label = label(node);
        return new NodeSummary(node.id(), label, displayName(label, node.properties()),
                (String) node.properties().get("service"), props);
    }

    static String label(GraphNode node) {
        return node.labels().stream().sorted().findFirst().orElse("Node");
    }

    static String displayName(String label, Map<String, Object> p) {
        return switch (label) {
            case "Endpoint" -> p.get("httpMethod") + " " + p.get("path");
            case "Screen" -> String.valueOf(p.get("routePath"));
            case "Method", "FeignMethod", "RepositoryMethod" -> methodName(p);
            case "Table" -> String.valueOf(p.get("qualifiedName"));
            case "AnalysisRun" -> String.valueOf(p.get("runId"));
            default -> String.valueOf(p.getOrDefault("name", p.get("fqn")));
        };
    }

    /** {@code com.x.CreditService#check(java.lang.String)} → {@code CreditService.check}. */
    private static String methodName(Map<String, Object> p) {
        Object fqn = p.get("fqn");
        if (fqn == null) {
            return String.valueOf(p.get("name"));
        }
        String s = fqn.toString();
        int hash = s.indexOf('#');
        if (hash < 0) {
            return s;
        }
        String type = s.substring(0, hash);
        String rest = s.substring(hash + 1);
        int paren = rest.indexOf('(');
        return type.substring(type.lastIndexOf('.') + 1) + "." + (paren < 0 ? rest : rest.substring(0, paren));
    }
}

package me.emreihtiyar.codegraph.query;

import java.util.Map;
import me.emreihtiyar.codegraph.graph.GraphRelationship;

/** Bir ilişki; uçlar düğüm id'leriyle. Çağrı kenarlarında sıra, satır, koşul/döngü/async bilgisi özelliklerdedir. */
public record EdgeView(String type, String source, String target, Map<String, Object> properties) {

    static EdgeView of(GraphRelationship r) {
        return new EdgeView(r.type(), r.startId(), r.endId(), r.properties());
    }
}

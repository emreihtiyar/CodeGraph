package me.emreihtiyar.codegraph.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Yazılacak bir ilişki; uçlar düğüm id'leriyle verilir. */
public record RelRecord(RelType type, String fromId, String toId, Map<String, Object> properties) {

    public RelRecord {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fromId, "fromId");
        Objects.requireNonNull(toId, "toId");
        properties = GraphValues.sanitize(properties);
    }

    public static RelRecord of(RelType type, String fromId, String toId) {
        return new RelRecord(type, fromId, toId, Map.of());
    }

    /** @param properties null değerler atlanır */
    public static RelRecord of(RelType type, String fromId, String toId, Map<String, ?> properties) {
        return new RelRecord(type, fromId, toId, new LinkedHashMap<>(properties));
    }
}

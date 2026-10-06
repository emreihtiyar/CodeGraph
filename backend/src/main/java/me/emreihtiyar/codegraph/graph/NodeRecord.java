package me.emreihtiyar.codegraph.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Yazılacak bir düğüm. {@code id} ve {@code key} özellikleri otomatik eklenir.
 *
 * @param key doğal anahtar (okunabilir); {@code id} bundan türetilir
 */
public record NodeRecord(NodeLabel label, String id, String key, Map<String, Object> properties) {

    public NodeRecord {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        properties = GraphValues.sanitize(properties);
        if (properties.containsKey("id") || properties.containsKey("key")) {
            throw new IllegalArgumentException("'id' ve 'key' özellikleri ayrılmıştır");
        }
    }

    /** @param properties null değerler atlanır */
    public static NodeRecord of(NodeLabel label, String naturalKey, Map<String, ?> properties) {
        return new NodeRecord(label, NodeIds.of(label, naturalKey), naturalKey, new LinkedHashMap<>(properties));
    }
}

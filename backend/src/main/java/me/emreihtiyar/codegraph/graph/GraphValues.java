package me.emreihtiyar.codegraph.graph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Özellik değerlerini graf veritabanının kabul ettiği tiplere indirger. */
final class GraphValues {

    private GraphValues() {}

    /**
     * Null değerleri atar, enum'ları adına çevirir. Koleksiyonlar listeye çevrilir. İç içe map'ler
     * desteklenmez (Neo4j özellik tipi değildir).
     */
    static Map<String, Object> sanitize(Map<String, ?> properties) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (properties == null) {
            return result;
        }
        properties.forEach((k, v) -> {
            Object value = sanitizeValue(k, v);
            if (value != null) {
                result.put(k, value);
            }
        });
        return result;
    }

    private static Object sanitizeValue(String key, Object value) {
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Collection<?> c) {
            List<Object> list = c.stream().map(v -> sanitizeValue(key, v)).toList();
            return list.contains(null) ? null : list;
        }
        if (value instanceof Map<?, ?>) {
            throw new IllegalArgumentException("İç içe map özellik olarak yazılamaz: " + key);
        }
        return value;
    }
}

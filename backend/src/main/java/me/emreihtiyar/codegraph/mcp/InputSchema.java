package me.emreihtiyar.codegraph.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Araç girdileri için küçük JSON Schema kurucusu (yalnızca kullanılan alt küme). */
final class InputSchema {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    static InputSchema object() {
        return new InputSchema();
    }

    InputSchema string(String name, String description, boolean isRequired) {
        return property(name, isRequired, "type", "string", "description", description);
    }

    InputSchema enumeration(String name, String description, List<String> values) {
        return property(name, false, "type", "string", "description", description, "enum", values);
    }

    InputSchema integer(String name, String description, int min, int max, int defaultValue) {
        return property(name, false, "type", "integer", "description", description, "minimum", min, "maximum", max,
                "default", defaultValue);
    }

    InputSchema bool(String name, String description, boolean defaultValue) {
        return property(name, false, "type", "boolean", "description", description, "default", defaultValue);
    }

    InputSchema stringArray(String name, String description) {
        return property(name, false, "type", "array", "description", description, "items", Map.of("type", "string"));
    }

    Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private InputSchema property(String name, boolean isRequired, Object... keyValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.put((String) keyValues[i], keyValues[i + 1]);
        }
        properties.put(name, p);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }
}

package me.emreihtiyar.codegraph.mcp;

import java.util.List;
import java.util.Map;

/** MCP araç argümanlarına tip güvenli erişim; hatalı tiplerde açıklayıcı {@link IllegalArgumentException}. */
record ToolArgs(Map<String, Object> values) {

    ToolArgs {
        values = values == null ? Map.of() : values;
    }

    String string(String name) {
        Object v = values.get(name);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("'" + name + "' parametresi zorunlu");
        }
        return v.toString().trim();
    }

    String optionalString(String name) {
        Object v = values.get(name);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    int integer(String name, int defaultValue) {
        Object v = values.get(name);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + name + "' bir tam sayı olmalı: " + v, e);
        }
    }

    boolean bool(String name, boolean defaultValue) {
        Object v = values.get(name);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return switch (v.toString().trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("'" + name + "' true ya da false olmalı: " + v);
        };
    }

    List<String> strings(String name) {
        Object v = values.get(name);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(v.toString().split("\\s*,\\s*"));
    }
}

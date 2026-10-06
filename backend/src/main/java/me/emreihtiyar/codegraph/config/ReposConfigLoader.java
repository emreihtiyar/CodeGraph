package me.emreihtiyar.codegraph.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Repo yapılandırma dosyasını (PRD FR-01) okur ve doğrular.
 *
 * <pre>
 * placeholders:                      # opsiyonel
 *   clients.credit.name: credit-api
 * repos:
 *   - name: leasing-credit-bff
 *     path: ../repos/leasing-credit-bff   # mutlak ya da bu dosyaya göreli
 *     layer: BFF                          # FRONTEND | BFF | API | ADAPTER | LIBRARY
 *     serviceName: leasing-credit-bff     # opsiyonel
 *     gatewayPrefix: leasing-credit-bff   # opsiyonel, yalnızca BFF
 *     aliases: [leasing-credit]           # opsiyonel, Feign adı eşlemesi
 * </pre>
 *
 * Hatalar ilk hatada durmadan toplanır ve tek bir {@link ReposConfigException} ile bildirilir.
 */
@Component
public class ReposConfigLoader {

    private static final Set<String> ROOT_KEYS = Set.of("repos", "placeholders");
    private static final Set<String> REPO_KEYS =
            Set.of("name", "path", "layer", "serviceName", "gatewayPrefix", "aliases");

    public ReposConfig load(Path file) {
        Path source = file.toAbsolutePath().normalize();
        List<String> errors = new ArrayList<>();

        Object root = readYaml(source, errors);
        if (!errors.isEmpty()) {
            throw new ReposConfigException(source, errors);
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new ReposConfigException(source, List.of("Kök eleman bir YAML map olmalı"));
        }
        checkUnknownKeys(rootMap, ROOT_KEYS, "kök", errors);

        Map<String, String> placeholders = readPlaceholders(rootMap.get("placeholders"), errors);
        List<RepoDefinition> repos = readRepos(rootMap.get("repos"), source.getParent(), errors);

        if (!errors.isEmpty()) {
            throw new ReposConfigException(source, errors);
        }
        return new ReposConfig(source, repos, placeholders);
    }

    private static Object readYaml(Path source, List<String> errors) {
        if (!Files.isRegularFile(source)) {
            errors.add("Dosya bulunamadı");
            return null;
        }
        try (Reader reader = Files.newBufferedReader(source)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (IOException | YAMLException e) {
            errors.add("Dosya okunamadı: " + e.getMessage());
            return null;
        }
    }

    private static Map<String, String> readPlaceholders(Object node, List<String> errors) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        if (!(node instanceof Map<?, ?> map)) {
            errors.add("'placeholders' bir map olmalı");
            return result;
        }
        map.forEach((k, v) -> {
            if (v == null || v instanceof Map || v instanceof List) {
                errors.add("placeholders." + k + ": değer skaler olmalı");
            } else {
                result.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return result;
    }

    private static List<RepoDefinition> readRepos(Object node, Path baseDir, List<String> errors) {
        List<RepoDefinition> repos = new ArrayList<>();
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            errors.add("'repos' boş olmayan bir liste olmalı");
            return repos;
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String where = "repos[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                errors.add(where + ": bir map olmalı");
                continue;
            }
            int errorsBefore = errors.size();
            checkUnknownKeys(map, REPO_KEYS, where, errors);

            String name = requiredString(map, "name", where, errors);
            if (name != null) {
                where = where + " (" + name + ")";
                if (!names.add(name)) {
                    errors.add(where + ": 'name' tekrar ediyor");
                }
            }
            Path path = readPath(map, baseDir, where, errors);
            Layer layer = readLayer(map, where, errors);
            String serviceName = optionalString(map, "serviceName", where, errors);
            String gatewayPrefix = trimSlashes(optionalString(map, "gatewayPrefix", where, errors));
            List<String> aliases = readStringList(map, "aliases", where, errors);

            if (errors.size() == errorsBefore) {
                repos.add(new RepoDefinition(name, path, layer, serviceName, gatewayPrefix, aliases));
            }
        }
        return repos;
    }

    private static Path readPath(Map<?, ?> map, Path baseDir, String where, List<String> errors) {
        String raw = requiredString(map, "path", where, errors);
        if (raw == null) {
            return null;
        }
        Path path = baseDir.resolve(raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            errors.add(where + ": 'path' bir dizin değil: " + path);
            return null;
        }
        return path;
    }

    private static Layer readLayer(Map<?, ?> map, String where, List<String> errors) {
        String raw = requiredString(map, "layer", where, errors);
        if (raw == null) {
            return null;
        }
        try {
            return Layer.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(where + ": geçersiz 'layer' değeri '" + raw + "' (geçerli: "
                    + String.join(", ", Arrays.stream(Layer.values()).map(Enum::name).toList()) + ")");
            return null;
        }
    }

    private static List<String> readStringList(Map<?, ?> map, String key, String where, List<String> errors) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            errors.add(where + ": '" + key + "' bir liste olmalı");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null || item instanceof Map || item instanceof List || item.toString().isBlank()) {
                errors.add(where + ": '" + key + "' yalnızca boş olmayan metinler içermeli");
            } else {
                result.add(item.toString().trim());
            }
        }
        return result;
    }

    private static String requiredString(Map<?, ?> map, String key, String where, List<String> errors) {
        String value = optionalString(map, key, where, errors);
        if (value == null && !map.containsKey(key)) {
            errors.add(where + ": '" + key + "' zorunlu");
        }
        return value;
    }

    private static String optionalString(Map<?, ?> map, String key, String where, List<String> errors) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Map || value instanceof List || value.toString().isBlank()) {
            errors.add(where + ": '" + key + "' boş olmayan bir metin olmalı");
            return null;
        }
        return value.toString().trim();
    }

    private static void checkUnknownKeys(Map<?, ?> map, Set<String> allowed, String where, List<String> errors) {
        for (Object key : map.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                errors.add(where + ": bilinmeyen alan '" + key + "' (geçerli: "
                        + String.join(", ", allowed.stream().sorted().toList()) + ")");
            }
        }
    }

    private static String trimSlashes(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.replaceAll("^/+|/+$", "");
        return trimmed.isEmpty() ? null : trimmed;
    }
}

package me.emreihtiyar.codegraph.extract.ts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.extract.SourceText;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Göreli olmayan import belirteçlerinin repo içi yollara çözümü: tsconfig/jsconfig {@code compilerOptions.paths}
 * ve {@code baseUrl}. tsconfig'te tanım yoksa yaygın Vite/CRA takma adları ({@code @/} ve {@code ~/} → {@code src/})
 * denenir.
 */
public final class PathAliases {

    private static final List<String> CONFIG_FILES =
            List.of("tsconfig.json", "tsconfig.app.json", "jsconfig.json", "tsconfig.base.json");

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
            .build();

    /** Kalıp ({@code @/*}) → hedefler ({@code src/*}), repo köküne göreli. */
    private final Map<String, List<String>> paths;
    /** {@code baseUrl}, repo köküne göreli; yoksa null. */
    private final String baseUrl;

    public PathAliases(Map<String, List<String>> paths, String baseUrl) {
        this.paths = paths;
        this.baseUrl = baseUrl;
    }

    public static PathAliases none() {
        return new PathAliases(Map.of(), null);
    }

    /** Repo kökündeki tsconfig/jsconfig dosyalarını okur; okunamayan dosya sorun olarak raporlanır. */
    public static PathAliases load(Path repoRoot, List<ParseIssue> issues) {
        Map<String, List<String>> paths = new LinkedHashMap<>();
        String[] baseUrl = {null};
        for (String name : CONFIG_FILES) {
            Path file = repoRoot.resolve(name);
            if (Files.isRegularFile(file)) {
                read(repoRoot, file, paths, baseUrl, issues, 0);
            }
        }
        return new PathAliases(paths, baseUrl[0]);
    }

    private static void read(Path root, Path file, Map<String, List<String>> paths, String[] baseUrl,
            List<ParseIssue> issues, int depth) {
        if (depth > 5) {
            return;
        }
        try {
            readConfig(root, file, paths, baseUrl, issues, depth);
        } catch (IOException | RuntimeException e) {
            issues.add(new ParseIssue(root.relativize(file).toString().replace('\\', '/'), 0,
                    "tsconfig okunamadı: " + e.getMessage()));
        }
    }

    private static void readConfig(Path root, Path file, Map<String, List<String>> paths, String[] baseUrl,
            List<ParseIssue> issues, int depth) throws IOException {
        JsonNode json = JSON.readTree(SourceText.read(file));
        // extends önce okunur; dosyanın kendi ayarları üstüne yazar
        JsonNode ext = json.path("extends");
        if (ext.isString() && ext.asString().startsWith(".")) {
            Path parent = file.getParent().resolve(ext.asString()).normalize();
            if (!parent.toString().endsWith(".json")) {
                parent = Path.of(parent + ".json");
            }
            if (Files.isRegularFile(parent) && parent.startsWith(root)) {
                read(root, parent, paths, baseUrl, issues, depth + 1);
            }
        }
        JsonNode options = json.path("compilerOptions");
        Path configDir = file.getParent();
        Path base = configDir;
        if (options.path("baseUrl").isString()) {
            base = configDir.resolve(options.path("baseUrl").asString()).normalize();
            baseUrl[0] = relative(root, base);
        }
        JsonNode p = options.path("paths");
        if (p.isObject()) {
            // Hedefler '*' içerir; Windows'ta Path'e çevrilemediği için metin olarak birleştirilir
            String baseRel = relative(root, base);
            for (var entry : p.properties()) {
                List<String> targets = new ArrayList<>();
                for (JsonNode t : entry.getValue()) {
                    String target = JsProject.normalize(baseRel.isEmpty() ? t.asString() : baseRel + "/" + t.asString());
                    if (target != null) {
                        targets.add(target);
                    }
                }
                paths.put(entry.getKey(), targets);
            }
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /** Belirteç için denenecek repo içi yollar (uzantısız olabilir). */
    public List<String> resolve(String specifier) {
        List<String> out = new ArrayList<>();
        for (var entry : paths.entrySet()) {
            String pattern = entry.getKey();
            int star = pattern.indexOf('*');
            if (star < 0) {
                if (pattern.equals(specifier)) {
                    out.addAll(entry.getValue());
                }
                continue;
            }
            String prefix = pattern.substring(0, star);
            String suffix = pattern.substring(star + 1);
            if (specifier.startsWith(prefix) && specifier.endsWith(suffix)
                    && specifier.length() >= prefix.length() + suffix.length()) {
                String matched = specifier.substring(prefix.length(), specifier.length() - suffix.length());
                entry.getValue().forEach(t -> out.add(t.replace("*", matched)));
            }
        }
        if (baseUrl != null) {
            out.add(baseUrl.isEmpty() ? specifier : baseUrl + "/" + specifier);
        }
        if (out.isEmpty()) {
            if (specifier.startsWith("@/") || specifier.startsWith("~/")) {
                out.add("src/" + specifier.substring(2));
            }
        }
        return out;
    }
}

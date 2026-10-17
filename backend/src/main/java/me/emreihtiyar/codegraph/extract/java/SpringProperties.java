package me.emreihtiyar.codegraph.extract.java;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.emreihtiyar.codegraph.extract.SourceText;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Bir reponun Spring application dosyalarından düzleştirilmiş özellikler ve {@code ${...}} placeholder çözümü
 * (PRD FR-02, FR-03).
 *
 * <p>Öncelik: profilsiz {@code application*} → profilsiz {@code bootstrap*} → profil dosyaları
 * ({@code application-dev.yml} ...) → repo yapılandırmasındaki elle verilen {@code placeholders}. Önce gelen kazanır.
 * Çok dokümanlı YAML'da ilk doküman sonrakilerden önceliklidir.
 */
public final class SpringProperties {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^${}]+)}");
    private static final int MAX_DEPTH = 10;

    private final Map<String, String> values;

    public SpringProperties(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * @param repoRoot   repo kökü
     * @param files      repo köküne göreli application dosyaları
     * @param fallbacks  diğer kaynaklarda bulunmayan anahtarlar için elle verilen değerler
     * @param issues     okunamayan dosyalar buraya eklenir
     */
    public static SpringProperties load(
            Path repoRoot, List<Path> files, Map<String, String> fallbacks, List<ParseIssue> issues) {
        List<Path> ordered = new ArrayList<>(files);
        ordered.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        Map<String, String> values = new LinkedHashMap<>();
        for (Path file : ordered) {
            try {
                for (Map<String, String> doc : read(repoRoot.resolve(file))) {
                    doc.forEach(values::putIfAbsent);
                }
            } catch (IOException | RuntimeException e) {
                issues.add(new ParseIssue(file.toString().replace('\\', '/'), 0, "Application dosyası okunamadı: "
                        + e.getMessage()));
            }
        }
        fallbacks.forEach(values::putIfAbsent);
        return new SpringProperties(values);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key)).map(this::resolve);
    }

    /**
     * {@code ${key}} ve {@code ${key:varsayılan}} ifadelerini çözer. Çözülemeyen placeholder'lar metinde olduğu gibi
     * kalır; {@link #isResolved(String)} ile kontrol edilebilir.
     */
    public String resolve(String text) {
        if (text == null) {
            return null;
        }
        String current = text;
        for (int depth = 0; depth < MAX_DEPTH && current.contains("${"); depth++) {
            Matcher m = PLACEHOLDER.matcher(current);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String expr = m.group(1);
                int colon = expr.indexOf(':');
                String key = colon >= 0 ? expr.substring(0, colon) : expr;
                String value = values.get(key.trim());
                if (value == null && colon >= 0) {
                    value = expr.substring(colon + 1);
                }
                if (value != null) {
                    changed = true;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
            }
            m.appendTail(sb);
            current = sb.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }

    public static boolean isResolved(String text) {
        return text != null && !text.contains("${");
    }

    public Map<String, String> asMap() {
        return values;
    }

    private static int rank(Path file) {
        String name = file.getFileName().toString();
        boolean profile = name.matches("(application|bootstrap)-.+");
        boolean bootstrap = name.startsWith("bootstrap");
        return (profile ? 2 : 0) + (bootstrap ? 1 : 0);
    }

    private static List<Map<String, String>> read(Path file) throws IOException {
        String name = file.getFileName().toString();
        List<Map<String, String>> docs = new ArrayList<>();
        try (Reader reader = new StringReader(SourceText.read(file))) {
            if (name.endsWith(".properties")) {
                Properties props = new Properties();
                props.load(reader);
                Map<String, String> doc = new LinkedHashMap<>();
                props.stringPropertyNames().forEach(k -> doc.put(k, props.getProperty(k)));
                docs.add(doc);
            } else {
                for (Object yamlDoc : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(reader)) {
                    Map<String, String> doc = new LinkedHashMap<>();
                    flatten("", yamlDoc, doc);
                    docs.add(doc);
                }
            }
        }
        return docs;
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
        } else if (node instanceof Collection<?> list) {
            int i = 0;
            for (Object item : list) {
                flatten(prefix + "[" + i++ + "]", item, out);
            }
        } else if (node != null && !prefix.isEmpty()) {
            out.put(prefix, String.valueOf(node));
        }
    }
}

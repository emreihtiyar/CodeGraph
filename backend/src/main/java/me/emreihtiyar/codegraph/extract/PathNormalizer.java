package me.emreihtiyar.codegraph.extract;

import java.util.regex.Pattern;

/**
 * HTTP path birleştirme ve normalizasyonu (PRD FR-09). Farklı path değişkeni biçimleri ({@code {id}},
 * {@code {id:\\d+}}, {@code :id}, {@code ${id}}) tek bir {@code {*}} jokerine indirgenir; böylece frontend, Feign ve
 * Spring tarafındaki path'ler karşılaştırılabilir.
 */
public final class PathNormalizer {

    public static final String WILDCARD = "{*}";

    /** {@code ${...}} — template literal değişkeni; içinde iç içe süslü parantez olabilir (ör. {@code ${a.b()}}). */
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\$\\{[^/]*}");
    /** {@code {id}} veya {@code {id:regex}} (regex içinde süslü parantez olabilir). */
    private static final Pattern SPRING_VAR = Pattern.compile("\\{[^/]*}");
    /** {@code :id} — yalnızca bir segmentin başında. */
    private static final Pattern COLON_VAR = Pattern.compile("(?<=/):[A-Za-z_][\\w]*");

    private PathNormalizer() {}

    /** Parçaları tek '/' ile birleştirir; boş/null parçalar atlanır. Sonuç '/' ile başlar, '/' ile bitmez. */
    public static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            sb.append('/').append(part.trim());
        }
        return clean(sb.toString());
    }

    /** Tekrarlanan '/' karakterlerini birleştirir, sondaki '/'yi ve sorgu/fragment kısmını atar. */
    public static String clean(String path) {
        if (path == null) {
            return "/";
        }
        String p = path.trim();
        int query = indexOfQueryOrFragment(p);
        if (query >= 0) {
            p = p.substring(0, query);
        }
        p = p.replaceAll("/{2,}", "/");
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** Path değişkenlerini {@value #WILDCARD} yapar. */
    public static String normalize(String path) {
        String p = clean(path);
        p = TEMPLATE_VAR.matcher(p).replaceAll("{*}");
        p = SPRING_VAR.matcher(p).replaceAll("{*}");
        p = COLON_VAR.matcher(p).replaceAll("{*}");
        return p;
    }

    /** Normalize edilmiş iki path'in eşleşip eşleşmediği; {@value #WILDCARD} tam bir segmentle eşleşir. */
    public static boolean matches(String normalizedA, String normalizedB) {
        String[] a = normalizedA.split("/", -1);
        String[] b = normalizedB.split("/", -1);
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i]) && !a[i].equals(WILDCARD) && !b[i].equals(WILDCARD)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfQueryOrFragment(String p) {
        int depth = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if ((c == '?' || c == '#') && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}

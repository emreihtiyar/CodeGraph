package me.emreihtiyar.codegraph.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;

/**
 * Bir reponun sorunlarını türe göre gruplayıp log için özetler: {@code 270× ApiCall adayı '…' içinde HTTP çağrısı
 * bulunamadı (ör. src/api/xApiCalls.ts:12 …)}. Tür, mesajdaki tırnaklı adlar ve ayrıntılar atılarak bulunur.
 */
final class IssueSummary {

    static final int MAX_KINDS = 5;

    private IssueSummary() {}

    static List<String> of(List<ParseIssue> issues) {
        Map<String, List<ParseIssue>> byKind = new LinkedHashMap<>();
        for (ParseIssue issue : issues) {
            byKind.computeIfAbsent(kind(issue.message()), k -> new ArrayList<>()).add(issue);
        }
        List<String> out = new ArrayList<>();
        byKind.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .limit(MAX_KINDS)
                .forEach(e -> {
                    ParseIssue first = e.getValue().get(0);
                    out.add(e.getValue().size() + "× " + e.getKey() + " (ör. " + first.file() + ":" + first.line()
                            + " " + shorten(first.message()) + ")");
                });
        if (byKind.size() > MAX_KINDS) {
            out.add("… ve " + (byKind.size() - MAX_KINDS) + " tür daha");
        }
        return out;
    }

    private static String shorten(String message) {
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    /** {@code ApiCall adayı 'x' içinde ...; gövdedeki çağrılar: ...} → {@code ApiCall adayı '…' içinde ...}. */
    static String kind(String message) {
        String kind = message.replaceAll("'[^']*'", "'…'");
        int cut = kind.indexOf(';');
        if (kind.startsWith("Sözdizimi hatası") || kind.startsWith("Dosya okunamadı") || kind.startsWith("Ayrıştırılamadı")) {
            cut = kind.indexOf(':');
        }
        return cut > 0 ? kind.substring(0, cut) : kind;
    }
}

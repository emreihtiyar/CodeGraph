package me.emreihtiyar.codegraph.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import me.emreihtiyar.codegraph.analysis.AnalysisReport;
import me.emreihtiyar.codegraph.analysis.AnalysisStatus;
import me.emreihtiyar.codegraph.query.AnalysisRunView;
import me.emreihtiyar.codegraph.query.CallTreeNode;
import me.emreihtiyar.codegraph.query.CodeBlock;
import me.emreihtiyar.codegraph.query.EndpointView;
import me.emreihtiyar.codegraph.query.ImpactResult;
import me.emreihtiyar.codegraph.query.NodeDetail;
import me.emreihtiyar.codegraph.query.NodeSummary;
import me.emreihtiyar.codegraph.query.Orchestration;
import me.emreihtiyar.codegraph.query.ScreenView;
import me.emreihtiyar.codegraph.query.SearchHit;
import me.emreihtiyar.codegraph.query.ServiceDependencies;
import me.emreihtiyar.codegraph.query.ServiceList;
import me.emreihtiyar.codegraph.query.TableUsages;
import me.emreihtiyar.codegraph.query.TraceResult;

/**
 * MCP araç sonuçlarının okunabilir özetleri (PRD §8.8). Düğümler {@code [id]} ile yazılır ki AI sonraki çağrıda
 * doğrudan kullanabilsin; uzun listeler kısaltılır, tam veri yapılandırılmış çıktıdadır.
 */
final class TextSummaries {

    static final int LIST_LIMIT = 50;

    private TextSummaries() {}

    static String services(ServiceList list) {
        StringBuilder sb = new StringBuilder(list.services().size() + " servis:\n");
        for (ServiceList.Service s : list.services()) {
            sb.append("- ").append(s.name()).append(" [").append(s.layer() == null ? "ANALİZ DIŞI" : s.layer()).append(']');
            if (s.endpointCount() > 0) {
                sb.append(", ").append(s.endpointCount()).append(" endpoint");
            }
            if (s.screenCount() > 0) {
                sb.append(", ").append(s.screenCount()).append(" ekran");
            }
            if (s.tableCount() > 0) {
                sb.append(", ").append(s.tableCount()).append(" tablo");
            }
            if (s.gatewayPrefix() != null) {
                sb.append(", gateway /").append(s.gatewayPrefix());
            }
            if (s.contextPath() != null) {
                sb.append(", context-path ").append(s.contextPath());
            }
            sb.append('\n');
        }
        if (!list.dependencies().isEmpty()) {
            sb.append("Bağımlılıklar:\n");
            list.dependencies().forEach(d -> sb.append("- ").append(d.from()).append(" → ").append(d.to())
                    .append(" (").append(d.callCount()).append(" çağrı)\n"));
        }
        return sb.toString();
    }

    static String serviceDependencies(ServiceDependencies deps) {
        StringBuilder sb = new StringBuilder(deps.service().name() + " [" + deps.service().layer() + "]\n");
        sb.append("Çağırdığı servisler (").append(deps.outgoing().size()).append("):\n");
        deps.outgoing().forEach(d -> dependency(sb, d, "→"));
        sb.append("Onu çağıran servisler (").append(deps.incoming().size()).append("):\n");
        deps.incoming().forEach(d -> dependency(sb, d, "←"));
        return sb.toString();
    }

    private static void dependency(StringBuilder sb, ServiceDependencies.Dependency d, String arrow) {
        sb.append(arrow).append(' ').append(d.service()).append(" [")
                .append(d.layer() == null ? (d.analyzed() ? "?" : "ANALİZ DIŞI") : d.layer()).append("], ")
                .append(d.callCount()).append(" çağrı\n");
        for (ServiceDependencies.Link l : d.links()) {
            sb.append("    ").append(l.sourceKind()).append(' ').append(l.source()).append(" [").append(l.sourceId())
                    .append("] → ").append(l.target() == null ? "?" : l.target() + " [" + l.targetId() + "]")
                    .append(' ').append(l.resolution());
            if (l.reason() != null) {
                sb.append(" — ").append(l.reason());
            }
            sb.append('\n');
        }
    }

    static String screens(List<ScreenView> screens) {
        return screens.size() + " ekran:\n" + screens.stream()
                .map(s -> "- " + s.routePath() + " → " + s.name() + " (" + s.service() + ") [" + s.id() + "]")
                .collect(Collectors.joining("\n"));
    }

    static String endpoints(List<EndpointView> endpoints) {
        return endpoints.size() + " endpoint:\n" + endpoints.stream()
                .map(e -> "- " + e.service() + " " + e.httpMethod() + " " + e.path() + " [" + e.id() + "]"
                        + (e.handler() == null ? "" : " → " + e.handler()))
                .collect(Collectors.joining("\n"));
    }

    static String trace(TraceResult t) {
        TraceResult.Summary s = t.summary();
        StringBuilder sb = new StringBuilder();
        sb.append("BACKWARD".equals(t.direction()) ? "Geri iz: " : "İleri iz: ").append(node(t.start())).append('\n');
        if (!s.screens().isEmpty()) {
            sb.append("Ekranlar: ").append(names(s.screens())).append('\n');
        }
        sb.append("Servisler (katmana göre):\n");
        s.services().forEach(l -> sb.append("  ").append(l.layer()).append(": ").append(String.join(", ", l.services()))
                .append('\n'));
        if (!s.endpoints().isEmpty()) {
            sb.append("Endpoint'ler (").append(s.endpoints().size()).append("):\n");
            limit(s.endpoints()).forEach(e -> sb.append("  ").append(e.service()).append(' ').append(e.name())
                    .append(" [").append(e.id()).append("]\n"));
            more(sb, s.endpoints());
        }
        if (!s.tables().isEmpty()) {
            sb.append("Tablolar:\n");
            s.tables().forEach(x -> sb.append("  ").append(x.name()).append(' ').append(x.access()).append(" [")
                    .append(x.id()).append("]\n"));
        }
        if (!s.unresolved().isEmpty()) {
            sb.append("Çözülemeyen çağrılar (zincir burada kopuyor):\n");
            s.unresolved().forEach(u -> sb.append("  ").append(node(u))
                    .append(u.properties().get("unresolvedReason") == null ? ""
                            : " — " + u.properties().get("unresolvedReason"))
                    .append('\n'));
        }
        sb.append("Alt graf: ").append(t.graph().nodes().size()).append(" düğüm, ").append(t.graph().edges().size())
                .append(" kenar.");
        if (t.graph().truncated()) {
            sb.append("\nUYARI: ").append(t.graph().note());
        }
        return sb.toString();
    }

    static String orchestration(Orchestration o) {
        StringBuilder sb = new StringBuilder();
        sb.append("Endpoint: ").append(node(o.endpoint())).append('\n');
        sb.append("Handler: ").append(node(o.handler())).append('\n');
        if (o.steps().isEmpty()) {
            sb.append("Feign çağrısı ya da veritabanı erişimi yok.\n");
        }
        for (Orchestration.Step s : o.steps()) {
            sb.append(s.order()).append(". ").append(s.kind()).append(' ').append(s.name()).append(" [").append(s.nodeId())
                    .append(']');
            if ("FEIGN".equals(s.kind())) {
                sb.append(" → ").append(s.targets().isEmpty()
                        ? s.targetService() + " (" + s.resolution() + ")" : String.join(" | ", s.targets()));
            } else {
                sb.append(' ').append(s.access()).append(' ').append(String.join(", ", s.targets()));
            }
            List<String> flags = new ArrayList<>();
            if (s.conditional()) {
                flags.add("koşullu");
            }
            if (s.inLoop()) {
                flags.add("döngüde");
            }
            if (s.async()) {
                flags.add("async");
            }
            if (!flags.isEmpty()) {
                sb.append(" {").append(String.join(", ", flags)).append('}');
            }
            if (!s.via().isEmpty()) {
                sb.append(" via ").append(String.join(" > ", s.via()));
            }
            sb.append('\n');
        }
        if (o.truncated()) {
            sb.append("UYARI: derinlik sınırına ulaşıldı; maxDepth'i artırın.\n");
        }
        return sb.toString();
    }

    static String tableUsages(TableUsages u) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tablo: ").append(names(u.tables())).append('\n');
        sb.append("Entity: ").append(names(u.entities())).append('\n');
        sb.append("Erişimler (").append(u.usages().size()).append("):\n");
        for (TableUsages.Usage x : limit(u.usages())) {
            sb.append("  ").append(x.access()).append(' ').append(x.repositoryMethod()).append(" [")
                    .append(x.repositoryMethodId()).append(']');
            if (x.caller() != null) {
                sb.append(" ← ").append(x.caller()).append(" (").append(x.service())
                        .append(x.line() == null ? "" : ":" + x.line()).append(") [").append(x.callerId()).append(']');
            } else {
                sb.append(" (çağıran yok)");
            }
            if (x.query() != null) {
                sb.append("\n      @Query: ").append(x.query());
            }
            sb.append('\n');
        }
        more(sb, u.usages());
        return sb.toString();
    }

    static String node(NodeDetail d) {
        StringBuilder sb = new StringBuilder(node(d.node())).append('\n');
        d.node().properties().forEach((k, v) -> {
            if (!"name".equals(k)) {
                sb.append("  ").append(k).append(": ").append(v).append('\n');
            }
        });
        sb.append(d.hasCode() ? "Kod bloğu var (get_code).\n" : "Kod bloğu yok.\n");
        sb.append("Giden kenarlar (").append(d.outgoingTotal()).append("):\n");
        neighbors(sb, d.outgoing(), "→");
        sb.append("Gelen kenarlar (").append(d.incomingTotal()).append("):\n");
        neighbors(sb, d.incoming(), "←");
        return sb.toString();
    }

    private static void neighbors(StringBuilder sb, List<NodeDetail.Neighbor> list, String arrow) {
        for (NodeDetail.Neighbor n : limit(list)) {
            sb.append("  ").append(arrow).append(' ').append(n.edge().type()).append(' ').append(node(n.node()));
            if (!n.edge().properties().isEmpty()) {
                sb.append(' ').append(n.edge().properties());
            }
            sb.append('\n');
        }
        more(sb, list);
    }

    static String code(CodeBlock c) {
        return c.name() + " (" + c.service() + ")\n" + c.repo() + ": " + c.file() + ":" + c.startLine() + "-"
                + c.endLine() + "\n```" + (c.language() == null ? "" : c.language()) + "\n" + c.code() + "\n```";
    }

    static String search(List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return "Sonuç yok. Daha kısa bir ifade ya da farklı bir ad deneyin.";
        }
        return hits.size() + " sonuç:\n" + hits.stream()
                .map(h -> "- " + node(h.node()) + " (" + h.matchedField() + ")")
                .collect(Collectors.joining("\n"));
    }

    static String impact(ImpactResult r, int shown) {
        StringBuilder sb = new StringBuilder("Etki analizi: ").append(node(r.target())).append('\n');
        sb.append(r.affected().size()).append(" düğüm etkileniyor: ").append(r.countsByLabel()).append('\n');
        sb.append("Etkilenen servisler:\n");
        r.services().forEach(l -> sb.append("  ").append(l.layer()).append(": ").append(String.join(", ", l.services()))
                .append('\n'));
        sb.append("Etkilenen ekranlar: ").append(r.screens().isEmpty() ? "yok" : String.join(", ", r.screens()))
                .append('\n');
        sb.append("En yakın ").append(Math.min(shown, r.affected().size())).append(" düğüm (uzaklık):\n");
        r.affected().stream().limit(shown).forEach(a -> sb.append("  ").append(a.distance()).append(' ')
                .append(node(a.node())).append('\n'));
        if (r.truncated()) {
            sb.append("UYARI: ").append(r.note()).append('\n');
        }
        return sb.toString();
    }

    static String callTree(CallTreeNode root) {
        StringBuilder sb = new StringBuilder();
        callTree(sb, root, 0);
        return sb.toString();
    }

    private static void callTree(StringBuilder sb, CallTreeNode n, int indent) {
        sb.append("  ".repeat(indent)).append(n.kind()).append(' ').append(n.name());
        if (n.service() != null) {
            sb.append(" (").append(n.service()).append(')');
        }
        sb.append(" [").append(n.nodeId()).append(']');
        List<String> badges = new ArrayList<>();
        if (n.call() != null) {
            Map<String, Object> c = n.call();
            if (c.get("line") != null) {
                badges.add("satır " + c.get("line"));
            }
            for (String flag : List.of("conditional", "inLoop", "async", "viaLambda")) {
                if (Boolean.TRUE.equals(c.get(flag))) {
                    badges.add(flag);
                }
            }
            if (c.get("resolution") != null && !"RESOLVED".equals(c.get("resolution"))) {
                badges.add(String.valueOf(c.get("resolution")));
            }
        }
        if (n.recursive()) {
            badges.add("özyinelemeli");
        }
        if (!n.details().isEmpty() && !"METHOD".equals(n.kind())) {
            badges.add(n.details().toString());
        }
        if (!badges.isEmpty()) {
            sb.append(" {").append(String.join(", ", badges)).append('}');
        }
        if (n.hasChildren() && n.children() == null) {
            sb.append(" …");
        }
        sb.append('\n');
        if (n.children() != null) {
            n.children().forEach(c -> callTree(sb, c, indent + 1));
        }
    }

    static String analysisRuns(List<AnalysisRunView> runs) {
        if (runs.isEmpty()) {
            return "Henüz analiz yapılmadı.";
        }
        return runs.stream().map(r -> "- " + r.runId() + " " + r.state() + " " + r.startedAt()
                        + (r.durationMs() == null ? "" : ", " + r.durationMs() + " ms")
                        + (r.issueCount() == null ? "" : ", " + r.issueCount() + " sorun")
                        + (r.error() == null ? "" : ", hata: " + r.error()))
                .collect(Collectors.joining("\n"));
    }

    static String analysisRun(AnalysisRunView r) {
        StringBuilder sb = new StringBuilder("Analiz ").append(r.runId()).append(": ").append(r.state()).append('\n');
        AnalysisStatus status = r.status();
        if (status != null) {
            sb.append("Aşama: ").append(status.phase()).append(' ').append(status.done()).append('/')
                    .append(status.total());
            if (status.current() != null) {
                sb.append(" (").append(status.current()).append(')');
            }
            return sb.append('\n').toString();
        }
        if (r.error() != null) {
            sb.append("Hata: ").append(r.error()).append('\n');
        }
        AnalysisReport rep = r.report();
        if (rep == null) {
            return sb.toString();
        }
        sb.append("Süre: ").append(rep.durationMs()).append(" ms, ").append(rep.issueCount()).append(" sorun\n");
        sb.append("Düğümler: ").append(rep.nodeCounts()).append('\n');
        sb.append("Repolar:\n");
        rep.repos().forEach(x -> sb.append("  ").append(x.name()).append(" [").append(x.layer()).append("] ")
                .append(x.files()).append(" dosya, ").append(x.methods()).append(" metot, ").append(x.endpoints())
                .append(" endpoint, ").append(x.feignMethods()).append(" Feign, ").append(x.apiCalls())
                .append(" ApiCall, ").append(x.issues()).append(" sorun\n"));
        linkIssues(sb, "Çözülemeyen bağlantılar", rep.unresolvedLinks());
        linkIssues(sb, "Belirsiz bağlantılar", rep.ambiguousLinks());
        callIssues(sb, "Belirsiz metot çağrıları", rep.ambiguousCalls());
        callIssues(sb, "Çözülemeyen metot çağrıları", rep.unresolvedCalls());
        if (!rep.parseIssues().isEmpty()) {
            sb.append("Parse sorunları (").append(rep.parseIssues().size()).append("):\n");
            limit(rep.parseIssues()).forEach(i -> sb.append("  ").append(i.repo()).append(' ').append(i.file())
                    .append(':').append(i.line()).append(' ').append(i.message()).append('\n'));
            more(sb, rep.parseIssues());
        }
        if (!rep.warnings().isEmpty()) {
            sb.append("Uyarılar:\n");
            limit(rep.warnings()).forEach(w -> sb.append("  ").append(w).append('\n'));
            more(sb, rep.warnings());
        }
        return sb.toString();
    }

    private static void linkIssues(StringBuilder sb, String title, List<AnalysisReport.LinkIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        sb.append(title).append(" (").append(issues.size()).append("):\n");
        for (AnalysisReport.LinkIssue i : limit(issues)) {
            sb.append("  ").append(i.kind()).append(' ').append(i.repo()).append(' ').append(i.source());
            if (i.targetService() != null) {
                sb.append(" → ").append(i.targetService());
            }
            if (i.reason() != null) {
                sb.append(" — ").append(i.reason());
            }
            if (!i.candidates().isEmpty()) {
                sb.append(" adaylar: ").append(String.join(" | ", i.candidates()));
            }
            sb.append('\n');
        }
        more(sb, issues);
    }

    private static void callIssues(StringBuilder sb, String title, List<AnalysisReport.CallIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        sb.append(title).append(" (").append(issues.size()).append("):\n");
        limit(issues).forEach(i -> sb.append("  ").append(i.repo()).append(' ').append(i.caller()).append(':')
                .append(i.line()).append(' ').append(i.expression())
                .append(i.candidates().isEmpty() ? "" : " adaylar: " + String.join(" | ", i.candidates()))
                .append('\n'));
        more(sb, issues);
    }

    // ---- yardımcılar ----

    static String node(NodeSummary n) {
        return n.label() + " " + n.name() + (n.service() == null ? "" : " (" + n.service() + ")") + " [" + n.id() + "]";
    }

    private static String names(List<NodeSummary> nodes) {
        return nodes.isEmpty() ? "yok" : nodes.stream().map(n -> n.name() + " [" + n.id() + "]")
                .collect(Collectors.joining(", "));
    }

    private static <T> List<T> limit(List<T> list) {
        return list.size() > LIST_LIMIT ? list.subList(0, LIST_LIMIT) : list;
    }

    private static void more(StringBuilder sb, List<?> list) {
        if (list.size() > LIST_LIMIT) {
            sb.append("  … ve ").append(list.size() - LIST_LIMIT).append(" tane daha (tamamı yapılandırılmış çıktıda)\n");
        }
    }
}

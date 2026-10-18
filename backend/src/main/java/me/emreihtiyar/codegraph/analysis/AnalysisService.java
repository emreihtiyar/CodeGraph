package me.emreihtiyar.codegraph.analysis;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.config.RepoDefinition;
import me.emreihtiyar.codegraph.config.ReposConfig;
import me.emreihtiyar.codegraph.config.ReposConfigProvider;
import me.emreihtiyar.codegraph.extract.java.JavaRepoAnalyzer;
import me.emreihtiyar.codegraph.extract.java.JavaSourceSet;
import me.emreihtiyar.codegraph.extract.java.TypeModel;
import me.emreihtiyar.codegraph.extract.model.CallFact;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.model.Resolution;
import me.emreihtiyar.codegraph.extract.model.ServiceFact;
import me.emreihtiyar.codegraph.extract.ts.FrontendRepoAnalyzer;
import me.emreihtiyar.codegraph.graph.GraphBatch;
import me.emreihtiyar.codegraph.graph.GraphNode;
import me.emreihtiyar.codegraph.graph.GraphStore;
import me.emreihtiyar.codegraph.graph.NodeLabel;
import me.emreihtiyar.codegraph.graph.NodeRecord;
import me.emreihtiyar.codegraph.graph.RelRecord;
import me.emreihtiyar.codegraph.graph.WriteResult;
import me.emreihtiyar.codegraph.link.LinkResult;
import me.emreihtiyar.codegraph.link.Linker;
import me.emreihtiyar.codegraph.load.AnalyzedRepo;
import me.emreihtiyar.codegraph.load.GraphLoader;
import me.emreihtiyar.codegraph.scan.RepoScanner;
import me.emreihtiyar.codegraph.scan.ScannedRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Analiz boru hattı (PRD §8.1): Scan → Parse/Extract → Link → Load → Report.
 *
 * <p>LIBRARY repoları önce analiz edilir; kaynak kökleri ve tip modelleri sonraki repoların tip çözümüne verilir.
 * Bir reponun analizi hata verirse analiz durmaz, hata rapora yazılır (NFR-06). Her analiz grafı sıfırdan üretir
 * (FR-10); yalnızca {@code AnalysisRun} geçmişi korunur.
 *
 * <p>Aynı anda tek analiz çalışır. {@link #start()} arka planda başlatır, {@link #run()} çağıran thread'de çalıştırır.
 */
@Service
public class AnalysisService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final ReposConfigProvider configProvider;
    private final RepoScanner scanner;
    private final JavaRepoAnalyzer javaAnalyzer;
    private final FrontendRepoAnalyzer frontendAnalyzer;
    private final Linker linker;
    private final GraphLoader loader;
    private final GraphStore store;

    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "codegraph-analysis");
        t.setDaemon(true);
        return t;
    });
    private volatile AnalysisStatus status = AnalysisStatus.idle();
    private volatile AnalysisReport lastReport;

    public AnalysisService(
            ReposConfigProvider configProvider,
            RepoScanner scanner,
            JavaRepoAnalyzer javaAnalyzer,
            FrontendRepoAnalyzer frontendAnalyzer,
            Linker linker,
            GraphLoader loader,
            GraphStore store) {
        this.configProvider = configProvider;
        this.scanner = scanner;
        this.javaAnalyzer = javaAnalyzer;
        this.frontendAnalyzer = frontendAnalyzer;
        this.linker = linker;
        this.loader = loader;
        this.store = store;
    }

    /**
     * Analizi arka planda başlatır.
     *
     * @return başlatılan analizin durumu (runId dahil)
     * @throws AnalysisInProgressException bir analiz zaten çalışıyorsa
     */
    public AnalysisStatus start() {
        if (!lock.tryLock()) {
            throw new AnalysisInProgressException(status.runId());
        }
        try {
            AnalysisStatus started = begin();
            executor.submit(() -> {
                lock.lock();
                try {
                    execute(started);
                } catch (RuntimeException e) {
                    log.error("Analiz başarısız", e);
                } finally {
                    lock.unlock();
                }
            });
            return started;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Analizi çağıran thread'de çalıştırır.
     *
     * @throws AnalysisInProgressException bir analiz zaten çalışıyorsa
     */
    public AnalysisReport run() {
        if (!lock.tryLock()) {
            throw new AnalysisInProgressException(status.runId());
        }
        try {
            return execute(begin());
        } finally {
            lock.unlock();
        }
    }

    public AnalysisStatus status() {
        return status;
    }

    /** Son başarılı analizin raporu; uygulama yeniden başladıysa graftaki son {@code AnalysisRun}'dan okunur. */
    public Optional<AnalysisReport> lastReport() {
        if (lastReport != null) {
            return Optional.of(lastReport);
        }
        return store.query("MATCH (r:AnalysisRun {status: 'SUCCEEDED'}) RETURN r ORDER BY r.startedAt DESC LIMIT 1",
                        Map.of())
                .stream()
                .findFirst()
                .map(row -> (GraphNode) row.get("r"))
                .map(n -> (String) n.properties().get("report"))
                .map(AnalysisReport::fromJson);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    // ---- boru hattı ----

    private AnalysisStatus begin() {
        if (status.state() == AnalysisStatus.State.RUNNING) {
            throw new AnalysisInProgressException(status.runId());
        }
        Instant now = Instant.now();
        status = new AnalysisStatus("run-" + now.toEpochMilli(), AnalysisStatus.State.RUNNING, "CONFIG", null, 0, 0,
                now, null, null);
        return status;
    }

    private AnalysisReport execute(AnalysisStatus started) {
        String runId = started.runId();
        Instant startedAt = started.startedAt();
        try {
            ReposConfig config = configProvider.load();
            List<RepoDefinition> ordered = new ArrayList<>(config.repos());
            ordered.sort(Comparator.comparing((RepoDefinition r) -> r.layer() != Layer.LIBRARY)); // stabil: LIBRARY'ler önce

            List<AnalyzedRepo> analyzed = new ArrayList<>();
            Map<String, Integer> fileCounts = new LinkedHashMap<>();
            Map<String, Long> durations = new LinkedHashMap<>();
            List<Path> libraryRoots = new ArrayList<>();
            TypeModel libraryModel = new TypeModel();
            for (int i = 0; i < ordered.size(); i++) {
                RepoDefinition repo = ordered.get(i);
                progress(runId, "ANALYZE", repo.name(), i, ordered.size());
                long t0 = System.nanoTime();
                RepoFacts facts = analyzeRepo(repo, config, libraryRoots, libraryModel, fileCounts);
                durations.put(repo.name(), Duration.ofNanos(System.nanoTime() - t0).toMillis());
                analyzed.add(new AnalyzedRepo(repo, facts, GitInfo.headCommit(repo.path()).orElse(null)));
                logRepo(repo, facts, fileCounts.getOrDefault(repo.name(), 0), durations.get(repo.name()));
            }

            progress(runId, "LINK", null, ordered.size(), ordered.size());
            Map<String, List<String>> aliases = config.repos().stream()
                    .collect(Collectors.toMap(RepoDefinition::name, RepoDefinition::aliases));
            LinkResult links = linker.link(analyzed.stream().map(AnalyzedRepo::facts).toList(), aliases);

            progress(runId, "LOAD", null, ordered.size(), ordered.size());
            GraphLoader.Result loaded = loader.build(analyzed, links, startedAt);
            store.ensureSchema();
            store.clearExcept(Set.of(NodeLabel.ANALYSIS_RUN));
            WriteResult written = store.write(loaded.batch());
            List<String> warnings = new ArrayList<>(loaded.warnings());
            for (RelRecord dangling : written.danglingRelationships()) {
                warnings.add("Uçları bulunamayan ilişki: " + dangling.type() + " " + dangling.fromId() + " → "
                        + dangling.toId());
            }

            progress(runId, "REPORT", null, ordered.size(), ordered.size());
            Instant finishedAt = Instant.now();
            AnalysisReport report = report(runId, startedAt, finishedAt, analyzed, links, loaded.batch(), fileCounts,
                    durations, warnings);
            writeRun(runId, startedAt, finishedAt, AnalysisStatus.State.SUCCEEDED, report, null);
            lastReport = report;
            status = new AnalysisStatus(runId, AnalysisStatus.State.SUCCEEDED, null, null, ordered.size(),
                    ordered.size(), startedAt, finishedAt, null);
            log.info("Analiz {} tamamlandı: {} düğüm, {} ilişki, {} sorun ({} ms)", runId,
                    loaded.batch().nodes().size(), loaded.batch().relationships().size(), report.issueCount(),
                    report.durationMs());
            return report;
        } catch (RuntimeException e) {
            Instant finishedAt = Instant.now();
            AnalysisStatus current = status;
            status = new AnalysisStatus(runId, AnalysisStatus.State.FAILED, current.phase(), current.current(),
                    current.done(), current.total(), startedAt, finishedAt, e.getMessage());
            try {
                writeRun(runId, startedAt, finishedAt, AnalysisStatus.State.FAILED, null, e.getMessage());
            } catch (RuntimeException writeError) {
                e.addSuppressed(writeError);
            }
            throw e;
        }
    }

    private RepoFacts analyzeRepo(RepoDefinition repo, ReposConfig config, List<Path> libraryRoots,
            TypeModel libraryModel, Map<String, Integer> fileCounts) {
        try {
            ScannedRepo scanned = scanner.scan(repo);
            fileCounts.put(repo.name(), scanned.fileCount());
            if (repo.layer() == Layer.FRONTEND) {
                return frontendAnalyzer.analyze(scanned);
            }
            JavaRepoAnalyzer.Result result =
                    javaAnalyzer.analyze(scanned, List.copyOf(libraryRoots), libraryModel, config.placeholders());
            if (repo.layer() == Layer.LIBRARY) {
                libraryRoots.addAll(JavaSourceSet.sourceRoots(repo.path(), scanned.javaSources()));
                libraryModel.addAll(result.model());
            }
            return result.facts();
        } catch (RuntimeException e) {
            log.error("{} analiz edilemedi", repo.name(), e);
            String name = repo.serviceName() != null ? repo.serviceName() : repo.name();
            RepoFacts empty = RepoFacts.empty(repo.name(), repo.layer(), new ServiceFact(name, repo.layer(), "",
                    repo.layer() == Layer.BFF ? (repo.gatewayPrefix() != null ? repo.gatewayPrefix() : name) : null));
            empty.issues().add(new ParseIssue("", 0, "Repo analiz edilemedi: " + e));
            return empty;
        }
    }

    private static void logRepo(RepoDefinition repo, RepoFacts facts, int files, long ms) {
        if (repo.layer() == Layer.FRONTEND) {
            log.info("{}: {} dosya, {} component, {} ekran, {} ApiCall, {} ApiCall kullanımı, {} sorun ({} ms)",
                    repo.name(), files, facts.components().size(), facts.screens().size(), facts.apiCalls().size(),
                    facts.apiCallUsages().size(), facts.issues().size(), ms);
        } else {
            log.info("{}: {} dosya, {} sınıf, {} metot, {} endpoint, {} Feign metodu, {} sorun ({} ms)", repo.name(),
                    files, facts.classes().size(), facts.methods().size(), facts.endpoints().size(),
                    facts.feignMethods().size(), facts.issues().size(), ms);
        }
        if (files == 0) {
            log.warn("{}: analiz edilecek dosya bulunamadı; repos dosyasındaki path ({}) ve layer ({}) doğru mu?",
                    repo.name(), repo.path(), repo.layer());
        }
        IssueSummary.of(facts.issues()).forEach(line -> log.info("{}: sorun özeti: {}", repo.name(), line));
        if (log.isDebugEnabled()) {
            facts.issues().forEach(i -> log.debug("{}: sorun {}:{} {}", repo.name(), i.file(), i.line(), i.message()));
        }
    }

    private void progress(String runId, String phase, String current, int done, int total) {
        AnalysisStatus s = status;
        status = new AnalysisStatus(runId, AnalysisStatus.State.RUNNING, phase, current, done, total, s.startedAt(),
                null, null);
    }

    // ---- rapor ----

    private static AnalysisReport report(String runId, Instant startedAt, Instant finishedAt,
            List<AnalyzedRepo> repos, LinkResult links, GraphBatch batch, Map<String, Integer> fileCounts,
            Map<String, Long> durations, List<String> warnings) {
        Map<String, Integer> nodeCounts = new LinkedHashMap<>();
        Map<NodeLabel, Integer> byLabel = new EnumMap<>(NodeLabel.class);
        for (NodeRecord n : batch.nodes()) {
            byLabel.merge(n.label(), 1, Integer::sum);
        }
        byLabel.forEach((label, count) -> nodeCounts.put(label.label(), count));
        Map<String, Integer> relCounts = new LinkedHashMap<>();
        batch.relationships().stream()
                .collect(Collectors.groupingBy(r -> r.type().name(), LinkedHashMap::new, Collectors.counting()))
                .forEach((type, count) -> relCounts.put(type, count.intValue()));

        List<AnalysisReport.RepoSummary> summaries = new ArrayList<>();
        List<AnalysisReport.CallIssue> ambiguousCalls = new ArrayList<>();
        List<AnalysisReport.CallIssue> unresolvedCalls = new ArrayList<>();
        List<AnalysisReport.FileIssue> parseIssues = new ArrayList<>();
        for (AnalyzedRepo repo : repos) {
            RepoFacts f = repo.facts();
            summaries.add(new AnalysisReport.RepoSummary(repo.name(), repo.definition().layer().name(),
                    repo.definition().layer() == Layer.LIBRARY ? null : f.service().name(), repo.commitHash(),
                    fileCounts.getOrDefault(repo.name(), 0), f.classes().size(), f.methods().size(), f.calls().size(),
                    f.endpoints().size(), f.feignMethods().size(), f.entities().size(), f.apiCalls().size(),
                    f.components().size(), f.screens().size(), f.issues().size(), durations.getOrDefault(repo.name(), 0L)));
            Map<String, List<CallFact>> ambiguous = new LinkedHashMap<>();
            for (CallFact call : f.calls()) {
                if (call.resolution() == Resolution.AMBIGUOUS) {
                    ambiguous.computeIfAbsent(call.callerFqn() + "#" + call.order(), k -> new ArrayList<>()).add(call);
                } else if (call.resolution() == Resolution.UNRESOLVED) {
                    unresolvedCalls.add(new AnalysisReport.CallIssue(repo.name(), call.callerFqn(), call.targetText(),
                            call.line(), List.of()));
                }
            }
            ambiguous.values().forEach(group -> ambiguousCalls.add(new AnalysisReport.CallIssue(repo.name(),
                    group.get(0).callerFqn(), group.get(0).targetText(), group.get(0).line(),
                    group.stream().map(CallFact::targetFqn).toList())));
            f.issues().forEach(i -> parseIssues.add(new AnalysisReport.FileIssue(repo.name(), i.file(), i.line(),
                    i.message())));
        }

        List<AnalysisReport.LinkIssue> unresolvedLinks = new ArrayList<>();
        List<AnalysisReport.LinkIssue> ambiguousLinks = new ArrayList<>();
        collectLinkIssues("API_CALL", links.apiCalls(), unresolvedLinks, ambiguousLinks);
        collectLinkIssues("FEIGN_METHOD", links.feignMethods(), unresolvedLinks, ambiguousLinks);

        return new AnalysisReport(runId, startedAt, finishedAt, Duration.between(startedAt, finishedAt).toMillis(),
                nodeCounts, relCounts, summaries, unresolvedLinks, ambiguousLinks, ambiguousCalls, unresolvedCalls,
                parseIssues, warnings);
    }

    private static void collectLinkIssues(String kind, List<LinkResult.Link> links,
            List<AnalysisReport.LinkIssue> unresolved, List<AnalysisReport.LinkIssue> ambiguous) {
        for (LinkResult.Link link : links) {
            if (link.resolution() == Resolution.UNRESOLVED) {
                unresolved.add(new AnalysisReport.LinkIssue(kind, link.repo(), link.sourceKey(), link.targetService(),
                        link.reason(), List.of()));
            } else if (link.resolution() == Resolution.AMBIGUOUS) {
                ambiguous.add(new AnalysisReport.LinkIssue(kind, link.repo(), link.sourceKey(), link.targetService(),
                        null, link.targets().stream()
                                .map(t -> t.service() + " " + t.httpMethod() + " " + t.normalizedPath())
                                .toList()));
            }
        }
    }

    private void writeRun(String runId, Instant startedAt, Instant finishedAt, AnalysisStatus.State state,
            AnalysisReport report, String error) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("runId", runId);
        props.put("startedAt", startedAt.toString());
        props.put("finishedAt", finishedAt.toString());
        props.put("durationMs", Duration.between(startedAt, finishedAt).toMillis());
        props.put("status", state);
        props.put("error", error);
        if (report != null) {
            props.put("stats", AnalysisReport.toJson(report.nodeCounts()));
            props.put("issueCount", report.issueCount());
            props.put("report", report.toJson());
        }
        store.write(new GraphBatch(List.of(NodeRecord.of(NodeLabel.ANALYSIS_RUN, runId, props)), List.of()));
    }
}

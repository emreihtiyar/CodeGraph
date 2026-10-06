package me.emreihtiyar.codegraph.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.emreihtiyar.codegraph.api.ToolNames;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.query.CallTreeOptions;
import me.emreihtiyar.codegraph.query.GraphQueryService;
import me.emreihtiyar.codegraph.query.ImpactResult;
import me.emreihtiyar.codegraph.query.NotFoundException;
import me.emreihtiyar.codegraph.query.TraceOptions;
import me.emreihtiyar.codegraph.query.TraceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * CodeGraph MCP araçları (PRD §8.8). Her araç REST controller'larıyla aynı {@link GraphQueryService} metodunu
 * çağırır; sonuç hem okunabilir özet metin ({@link TextSummaries}) hem yapılandırılmış JSON olarak döner. Tüm araçlar
 * salt-okunurdur.
 */
@Component
public class CodeGraphTools {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphTools.class);

    static final int IMPACT_LIST_DEFAULT = 100;

    private static final String REF = "Düğüm id'si (ör. method_1a2b3c4d5e6f7a8b) ya da doğal ad: ekran route'u "
            + "(/credit-application), tablo (offers, LEASING.CREDIT), endpoint ('GET /applications/{id}', başında servis "
            + "adıyla da: 'credit-api POST /credit-api/v1/credits/limit-checks'), servis adı, component/sınıf adı ya da "
            + "Sinif.metot (CreditServiceImpl.updateStatus). Ad birden çok düğüme uyarsa hata adayları id'leriyle listeler.";

    /** @param handler argümanlardan sonucu (yapılandırılmış) ve metin özetini üretir */
    record Tool(String name, String title, String description, Map<String, Object> inputSchema,
            Function<ToolArgs, Output> handler) {}

    record Output(Object structured, String text) {}

    private final GraphQueryService queries;
    private final JsonMapper json;
    private final List<Tool> tools;

    public CodeGraphTools(GraphQueryService queries, JsonMapper json) {
        this.queries = queries;
        this.json = json;
        this.tools = define();
    }

    public List<String> names() {
        return tools.stream().map(Tool::name).toList();
    }

    List<Tool> tools() {
        return tools;
    }

    public List<SyncToolSpecification> specifications() {
        return tools.stream().map(this::specification).toList();
    }

    private SyncToolSpecification specification(Tool tool) {
        McpSchema.Tool definition = McpSchema.Tool.builder()
                .name(tool.name())
                .title(tool.title())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .annotations(new McpSchema.ToolAnnotations(tool.title(), true, false, true, false, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(definition)
                .callHandler((exchange, request) -> call(tool, request.arguments()))
                .build();
    }

    /** Aracı çalıştırır; sorgu hataları MCP'de {@code isError} sonucu olarak döner (AI düzeltip tekrar deneyebilir). */
    McpSchema.CallToolResult call(Tool tool, Map<String, Object> arguments) {
        try {
            Output out = tool.handler().apply(new ToolArgs(arguments));
            return McpSchema.CallToolResult.builder()
                    .addTextContent(out.text())
                    .structuredContent(structured(out.structured()))
                    .isError(false)
                    .build();
        } catch (NotFoundException | IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("MCP aracı {} başarısız", tool.name(), e);
            return error("Beklenmeyen hata: " + e);
        }
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    /** MCP yapılandırılmış içeriği bir JSON nesnesi olmalı; listeler {@code items} altına sarılır. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> structured(Object value) {
        if (value instanceof List<?> list) {
            return Map.of("items", json.convertValue(list, List.class));
        }
        return json.convertValue(value, Map.class);
    }

    // ---- araç tanımları ----

    private List<Tool> define() {
        List<Tool> list = new ArrayList<>();
        list.add(new Tool(ToolNames.LIST_SERVICES, "Servisleri listele",
                "Analiz edilen sistemdeki tüm servisleri katmanlarıyla (FRONTEND, BFF, API, ADAPTER, LIBRARY) ve servisler "
                        + "arası DEPENDS_ON bağımlılıklarıyla listeler. Sistemi tanımak için ilk çağrılacak araç. Katmanı "
                        + "olmayan servisler Feign hedefi olup analize dahil edilmemiş dış servislerdir.",
                InputSchema.object()
                        .enumeration("layer", "Yalnızca bu katmandaki servisler", List.of("FRONTEND", "BFF", "API", "ADAPTER", "LIBRARY"))
                        .build(),
                a -> {
                    String layer = a.optionalString("layer");
                    var r = queries.listServices(layer == null ? null : Layer.parse(layer));
                    return new Output(r, TextSummaries.services(r));
                }));
        list.add(new Tool(ToolNames.GET_SERVICE_DEPENDENCIES, "Servis bağımlılıkları",
                "Bir servisin çağırdığı ve onu çağıran servisleri, bağımlılığı oluşturan somut ApiCall/Feign metotları ve "
                        + "hedef endpoint'leriyle döner. 'X servisi kimleri çağırıyor / X'i kim kullanıyor' soruları için.",
                InputSchema.object().string("service", "Servis adı (list_services çıktısındaki ad)", true).build(),
                a -> {
                    var r = queries.serviceDependencies(a.string("service"));
                    return new Output(r, TextSummaries.serviceDependencies(r));
                }));
        list.add(new Tool(ToolNames.LIST_SCREENS, "Ekranları listele",
                "Frontend ekranlarını (route → component) listeler. İleri iz için başlangıç noktası bulmakta kullanılır.",
                InputSchema.object().string("service", "Yalnızca bu frontend servisinin ekranları", false).build(),
                a -> {
                    var r = queries.listScreens(a.optionalString("service"));
                    return new Output(r, TextSummaries.screens(r));
                }));
        list.add(new Tool(ToolNames.LIST_ENDPOINTS, "Endpoint'leri listele",
                "Servislerin HTTP endpoint'lerini handler metotlarıyla listeler.",
                InputSchema.object().string("service", "Yalnızca bu servisin endpoint'leri", false).build(),
                a -> {
                    var r = queries.listEndpoints(a.optionalString("service"));
                    return new Output(r, TextSummaries.endpoints(r));
                }));
        list.add(new Tool(ToolNames.TRACE_FORWARD, "İleri iz (ekrandan tablolara)",
                "Bir ekran, component, ApiCall, endpoint, metot ya da servisten başlayıp çağrı zincirini BFF → API → "
                        + "adapter → veritabanı tablolarına kadar izler. 'Bu ekran hangi servislere ve tablolara dokunuyor?' "
                        + "sorusunun cevabı. Özet: katmana göre servisler, endpoint'ler, tablolar (READ/WRITE) ve zincirin "
                        + "koptuğu çözülemeyen çağrılar. Alt graf varsayılan olarak yalnızca sayılarla döner; düğüm ve kenarlar "
                        + "için includeGraph=true verin.",
                traceSchema(true),
                a -> {
                    TraceOptions o = new TraceOptions(a.integer("maxDepth", TraceOptions.DEFAULT_DEPTH),
                            a.bool("includeAccessors", false), a.bool("prune", true),
                            a.integer("maxNodes", TraceOptions.DEFAULT_MAX_NODES));
                    TraceResult r = queries.traceForward(a.string("from"), o);
                    return new Output(trace(r, a.bool("includeGraph", false)), TextSummaries.trace(r));
                }));
        list.add(new Tool(ToolNames.TRACE_BACKWARD, "Geri iz (tablodan ekranlara)",
                "Bir tablo, entity, repository metodu, endpoint ya da metottan geriye doğru gidip onu kullanan metotları, "
                        + "endpoint'leri, servisleri ve ekranları bulur. 'Bu tabloyu/endpoint'i hangi ekranlar kullanıyor?' "
                        + "sorusunun cevabı. Zamanlanmış işler gibi ekrana bağlı olmayan çağıranlar da sonuçta yer alır.",
                traceSchema(false),
                a -> {
                    TraceOptions o = new TraceOptions(a.integer("maxDepth", TraceOptions.DEFAULT_DEPTH),
                            a.bool("includeAccessors", false), false,
                            a.integer("maxNodes", TraceOptions.DEFAULT_MAX_NODES));
                    TraceResult r = queries.traceBackward(a.string("from"), o);
                    return new Output(trace(r, a.bool("includeGraph", false)), TextSummaries.trace(r));
                }));
        list.add(new Tool(ToolNames.GET_ENDPOINT_ORCHESTRATION, "Endpoint orkestrasyonu",
                "Bir endpoint'in handler'ından başlayarak yaptığı Feign çağrılarını ve veritabanı erişimlerini kod "
                        + "sırasıyla numaralı adımlar olarak listeler; via ara metot zincirini gösterir. Her adımda koşullu/döngüde/async bilgisi ve hedef "
                        + "endpoint ya da tablolar vardır. 'Bu BFF endpoint'i hangi sırayla neleri çağırıyor?' için.",
                InputSchema.object()
                        .string("endpoint", "Endpoint id'si ya da 'METOT /path' (başında servis adıyla da)", true)
                        .integer("maxDepth", "Handler'dan itibaren en fazla metot derinliği", 1, 50, 20)
                        .bool("includeAccessors", "Getter/setter gibi erişimcilere de girilsin mi", false)
                        .build(),
                a -> {
                    var r = queries.endpointOrchestration(a.string("endpoint"), a.integer("maxDepth", 20),
                            a.bool("includeAccessors", false));
                    return new Output(r, TextSummaries.orchestration(r));
                }));
        list.add(new Tool(ToolNames.FIND_TABLE_USAGES, "Tablo kullanımları",
                "Bir veritabanı tablosuna erişen repository metotlarını READ/WRITE ayrımıyla ve bu metotları çağıran "
                        + "servis metotlarını (satır numarasıyla) listeler. Ekranlara kadar gitmek için trace_backward kullanın.",
                InputSchema.object()
                        .string("table", "Tablo adı ya da şema.ad (büyük-küçük harf duyarsız), ör. offers, LEASING.CREDIT", true)
                        .build(),
                a -> {
                    var r = queries.tableUsages(a.string("table"));
                    return new Output(r, TextSummaries.tableUsages(r));
                }));
        list.add(new Tool(ToolNames.GET_NODE, "Düğüm detayı",
                "Tek bir düğümün özelliklerini ve komşularını (gelen/giden kenarlar, en fazla "
                        + GraphQueryService.NEIGHBOR_LIMIT + "'er) döner. Bir düğümü keşfetmek ve bağlantılarını görmek için.",
                InputSchema.object().string("ref", REF, true).build(),
                a -> {
                    var r = queries.getNode(a.string("ref"));
                    return new Output(r, TextSummaries.node(r));
                }));
        list.add(new Tool(ToolNames.GET_CODE, "Kod bloğu",
                "Bir metodun, component'in ya da ApiCall'un kaynak kodunu dosya ve satır aralığıyla döner. Endpoint için "
                        + "handler metodunun, ekran için component'in, tablo için entity sınıfının kodu gelir.",
                InputSchema.object().string("ref", REF, true).build(),
                a -> {
                    var r = queries.getCode(a.string("ref"));
                    return new Output(r, TextSummaries.code(r));
                }));
        list.add(new Tool(ToolNames.SEARCH, "Ara",
                "Ad, FQN, URL path, route, tablo adı ve URL şablonunda büyük-küçük harf duyarsız arar. Bir şeyin tam adını "
                        + "ya da id'sini bilmiyorsanız önce bunu kullanın. 'GET /orders/{id}' gibi metotlu path aramaları "
                        + "parametre adından bağımsız eşleşir.",
                InputSchema.object()
                        .string("query", "Aranacak metin", true)
                        .stringArray("labels", "Yalnızca bu düğüm tipleri: Service, Screen, Component, ApiCall, Endpoint, "
                                + "Class, Method, FeignClient, FeignMethod, JpaRepository, RepositoryMethod, Entity, Table")
                        .integer("limit", "En fazla sonuç", 1, GraphQueryService.SEARCH_LIMIT, 20)
                        .build(),
                a -> {
                    List<String> labels = a.strings("labels");
                    var r = queries.search(a.string("query"), labels.isEmpty() ? null : labels, a.integer("limit", 20));
                    return new Output(r, TextSummaries.search(r));
                }));
        list.add(new Tool(ToolNames.IMPACT_ANALYSIS, "Etki analizi",
                "Bir düğüm (tablo, entity, metot, endpoint, Feign metodu …) değişirse doğrudan ya da dolaylı etkilenecek "
                        + "tüm yukarı akış düğümlerini, servisleri ve ekranları uzaklığa göre döner. 'Bunu değiştirirsem ne "
                        + "kırılır?' için. Erişimci metotlar dahildir.",
                InputSchema.object()
                        .string("ref", REF, true)
                        .integer("maxDepth", "En fazla kenar sayısı", 1, 100, TraceOptions.DEFAULT_DEPTH)
                        .integer("maxNodes", "En fazla düğüm; aşılırsa sonuç kesilir", 1, 20000, TraceOptions.DEFAULT_MAX_NODES)
                        .integer("limit", "Listelenecek etkilenen düğüm sayısı (en yakınlar); sayımlar her zaman tamdır",
                                1, 5000, IMPACT_LIST_DEFAULT)
                        .build(),
                a -> {
                    ImpactResult r = queries.impact(a.string("ref"), a.integer("maxDepth", TraceOptions.DEFAULT_DEPTH),
                            a.integer("maxNodes", TraceOptions.DEFAULT_MAX_NODES));
                    int limit = a.integer("limit", IMPACT_LIST_DEFAULT);
                    return new Output(impact(r, limit), TextSummaries.impact(r, Math.min(limit, TextSummaries.LIST_LIMIT)));
                }));
        list.add(new Tool(ToolNames.GET_CALL_TREE, "Metot çağrı ağacı",
                "Bir metodun (ya da endpoint/Feign/repository metodunun) çağırdığı metotları kod sırasıyla ağaç olarak "
                        + "döner; koşul, döngü, async, lambda ve özyineleme bilgisiyle. Feign çağrılarında hedef servisin "
                        + "endpoint'ine ve handler'ına devam eder (crossService). '…' ile biten düğümlerin çocukları "
                        + "yüklenmemiştir; o düğümün id'siyle tekrar çağırın.",
                InputSchema.object()
                        .string("ref", REF, true)
                        .integer("depth", "Açılacak seviye sayısı", 1, 10, 3)
                        .bool("includeAccessors", "Getter/setter gibi erişimciler gösterilsin mi", false)
                        .bool("onlyExternal", "Yalnızca Feign çağrısına ya da veritabanına giden dallar", false)
                        .bool("crossService", "Feign çağrılarında hedef servise devam edilsin mi", true)
                        .stringArray("hidden", "Gizlenecek sınıf adları ya da paket önekleri (ör. ValidationUtils, com.bank.util)")
                        .build(),
                a -> {
                    var r = queries.callTree(a.string("ref"), new CallTreeOptions(a.integer("depth", 3),
                            a.bool("includeAccessors", false), a.bool("onlyExternal", false),
                            a.bool("crossService", true), a.strings("hidden"), List.of()));
                    return new Output(r, TextSummaries.callTree(r));
                }));
        list.add(new Tool(ToolNames.LIST_ANALYSIS_RUNS, "Analiz çalıştırmaları",
                "Yapılan analizleri en yenisi önce listeler (durum, süre, sorun sayısı). Grafın ne kadar güncel olduğunu "
                        + "anlamak için.",
                InputSchema.object().integer("limit", "En fazla kayıt", 1, 200, 20).build(),
                a -> {
                    var r = queries.listAnalysisRuns(a.integer("limit", 20));
                    return new Output(r, TextSummaries.analysisRuns(r));
                }));
        list.add(new Tool(ToolNames.GET_ANALYSIS_REPORT, "Analiz raporu",
                "Bir analizin raporunu döner: repo başına sayılar, çözülemeyen/belirsiz bağlantılar (zincirin koptuğu "
                        + "yerler ve nedenleri), belirsiz metot çağrıları, parse sorunları. Bir iz beklenmedik şekilde "
                        + "kesiliyorsa nedenini burada arayın. Analiz sürüyorsa aşama ve ilerleme döner.",
                InputSchema.object().string("runId", "Analiz id'si; verilmezse son analiz", false).build(),
                a -> {
                    String id = a.optionalString("runId");
                    var r = queries.analysisRun(id == null ? "latest" : id);
                    return new Output(r, TextSummaries.analysisRun(r));
                }));
        return List.copyOf(list);
    }

    private static Map<String, Object> traceSchema(boolean forward) {
        InputSchema schema = InputSchema.object()
                .string("from", forward
                        ? "Başlangıç: ekran route'u, component, ApiCall, endpoint, metot ya da servis. " + REF
                        : "Başlangıç: tablo, entity, repository metodu, endpoint ya da metot. " + REF, true)
                .integer("maxDepth", "En fazla kenar sayısı", 1, 100, TraceOptions.DEFAULT_DEPTH)
                .integer("maxNodes", "En fazla düğüm; aşılırsa sonuç kesilir ve belirtilir", 1, 20000,
                        TraceOptions.DEFAULT_MAX_NODES)
                .bool("includeAccessors", "Getter/setter gibi erişimcilere de girilsin mi", false);
        if (forward) {
            schema.bool("prune", "Tabloya, endpoint'e ya da dış çağrıya gitmeyen ara dallar atılsın mı", true);
        }
        return schema.bool("includeGraph", "Yapılandırılmış çıktıya alt grafın düğüm ve kenarları da eklensin mi", false)
                .build();
    }

    private static Map<String, Object> trace(TraceResult r, boolean includeGraph) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodeCount", r.graph().nodes().size());
        graph.put("edgeCount", r.graph().edges().size());
        graph.put("truncated", r.graph().truncated());
        graph.put("note", r.graph().note());
        if (includeGraph) {
            graph.put("nodes", r.graph().nodes());
            graph.put("edges", r.graph().edges());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("direction", r.direction());
        out.put("start", r.start());
        out.put("summary", r.summary());
        out.put("graph", graph);
        return out;
    }

    private static Map<String, Object> impact(ImpactResult r, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", r.target());
        out.put("affectedTotal", r.affected().size());
        out.put("countsByLabel", r.countsByLabel());
        out.put("services", r.services());
        out.put("screens", r.screens());
        out.put("affected", r.affected().size() > limit ? r.affected().subList(0, limit) : r.affected());
        out.put("truncated", r.truncated());
        out.put("note", r.note());
        return out;
    }
}

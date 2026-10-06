package me.emreihtiyar.codegraph.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * stdio ↔ Streamable HTTP köprüsü (PRD §8.8): yalnızca stdio destekleyen MCP istemcileri için, çalışan CodeGraph
 * sunucusuna bağlanan ince aktarıcı. Spring başlatmaz; stdin'den satır satır JSON-RPC mesajı okur, HTTP POST ile
 * sunucuya iletir, JSON ya da SSE yanıtlarını stdout'a satır olarak yazar. Oturum ({@code Mcp-Session-Id}) ve protokol
 * sürümü başlıklarını taşır. stdout yalnızca protokol mesajlarına ayrılmıştır; tanılama stderr'e yazılır.
 *
 * <pre>java -jar codegraph.jar --mcp-stdio-bridge [--url=http://localhost:8080/mcp]</pre>
 */
public final class StdioBridge {

    public static final String FLAG = "--mcp-stdio-bridge";
    static final String DEFAULT_URL = "http://localhost:8080/mcp";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI url;
    private final HttpClient http;
    private final PrintStream out;
    private final PrintStream err;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcp-bridge");
        t.setDaemon(true);
        return t;
    });
    private volatile String sessionId;
    private volatile String protocolVersion;

    StdioBridge(URI url, PrintStream out, PrintStream err) {
        this.url = url;
        this.out = out;
        this.err = err;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static void main(String[] args) {
        String url = System.getenv().getOrDefault("CODEGRAPH_MCP_URL", DEFAULT_URL);
        for (String arg : args) {
            if (arg.startsWith("--url=")) {
                url = arg.substring("--url=".length());
            }
        }
        // stdout'u protokol dışı yazımlardan korumak için gerçek akış saklanır, System.out stderr'e yönlendirilir.
        PrintStream protocolOut = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        new StdioBridge(URI.create(url), protocolOut, System.err).run(System.in);
    }

    /** stdin kapanana kadar mesajları aktarır; ardından oturumu kapatır. */
    void run(InputStream in) {
        err.println("[codegraph-bridge] " + url + " adresine aktarılıyor");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = JSON.readTree(line);
                } catch (JacksonException e) {
                    emit(errorResponse(null, -32700, "Geçersiz JSON: " + e.getOriginalMessage()));
                    continue;
                }
                // Sıra önemli olanlar senkron: initialize oturum id'sini belirler, bildirimler (ör. initialized) ve
                // istemci yanıtları kendinden sonraki isteklerden önce ulaşmalı. Diğer istekler paralel çalışabilir.
                boolean request = message.has("id") && message.has("method");
                if (!request || "initialize".equals(message.path("method").asString(null))) {
                    forward(message);
                } else {
                    workers.execute(() -> forward(message));
                }
            }
        } catch (IOException e) {
            err.println("[codegraph-bridge] stdin okunamadı: " + e);
        }
        workers.shutdown();
        try {
            workers.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeSession();
    }

    void forward(JsonNode message) {
        JsonNode id = message.get("id");
        boolean isRequest = id != null && message.has("method");
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(message), StandardCharsets.UTF_8));
            headers(request);
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
            int status = response.statusCode();
            if (status == 202 || status == 204) {
                response.body().close();
                return;
            }
            if (status == 404 && sessionId != null) {
                sessionId = null; // sunucu yeniden başladı; istemci yeniden initialize etmeli
            }
            if (status >= 400) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                err.println("[codegraph-bridge] HTTP " + status + ": " + body);
                if (isRequest) {
                    emit(errorResponse(id, -32000, "CodeGraph sunucusu HTTP " + status + " döndü: " + body));
                }
                return;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (contentType.startsWith("text/event-stream")) {
                readEvents(response.body());
            } else {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                if (!body.isBlank()) {
                    deliver(body);
                }
            }
        } catch (IOException e) {
            err.println("[codegraph-bridge] " + url + " erişilemedi: " + e);
            if (isRequest) {
                emit(errorResponse(id, -32000, "CodeGraph sunucusuna (" + url + ") bağlanılamadı; sunucu çalışıyor mu? "
                        + e.getMessage()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** SSE akışındaki her {@code message} olayının verisini ayrı satır olarak iletir. */
    private void readEvents(InputStream body) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            List<String> data = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        deliver(String.join("\n", data));
                        data.clear();
                    }
                } else if (line.startsWith("data:")) {
                    data.add(line.substring(line.startsWith("data: ") ? 6 : 5));
                }
            }
            if (!data.isEmpty()) {
                deliver(String.join("\n", data));
            }
        }
    }

    /** Sunucu mesajını tek satıra indirger; initialize yanıtından protokol sürümünü alır. */
    private void deliver(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            JsonNode version = node.path("result").path("protocolVersion");
            if (version.isString()) {
                protocolVersion = version.asString();
            }
            emit(node);
        } catch (JacksonException e) {
            err.println("[codegraph-bridge] sunucudan geçersiz JSON: " + json);
        }
    }

    private void headers(HttpRequest.Builder request) {
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            request.header("MCP-Protocol-Version", protocolVersion);
        }
    }

    private void closeSession() {
        if (sessionId == null) {
            return;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).DELETE();
        headers(request);
        try {
            http.send(request.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // sunucu kapanmış olabilir; oturum zaten geçersizdir
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void emit(JsonNode node) {
        out.println(JSON.writeValueAsString(node));
        out.flush();
    }

    private static JsonNode errorResponse(JsonNode id, int code, String message) {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}

# CodeGraph MCP sunucusu

CodeGraph, analiz edilen sistemin bağımlılık grafını MCP araçları olarak sunar. Bu araçlar REST API'nin
(`/api/...`) salt-okunur karşılıklarıdır.

## Bağlanma

Sunucu çalışırken (`java -jar CodeGraph.jar`, varsayılan port 8080), MCP uç noktası `http://localhost:8080/mcp`
adresindedir (Streamable HTTP).

**Claude Code:**

```bash
claude mcp add --transport http codegraph http://localhost:8080/mcp
```

**Yalnızca stdio destekleyen istemciler** aynı jar'ı köprü olarak çalıştırır. Köprü Spring başlatmaz, çalışan sunucuya
bağlanır:

```json
{
  "mcpServers": {
    "codegraph": {
      "command": "java",
      "args": ["-jar", "/yol/CodeGraph.jar", "--mcp-stdio-bridge", "--url=http://localhost:8080/mcp"]
    }
  }
}
```

`--url` verilmezse `CODEGRAPH_MCP_URL` ortam değişkeni, o da yoksa `http://localhost:8080/mcp` kullanılır.

## Ayarlar (`application.yaml`)

| Anahtar | Varsayılan | Açıklama |
|---|---|---|
| `codegraph.mcp.enabled` | `true` | MCP uç noktası açık mı |
| `codegraph.mcp.endpoint` | `/mcp` | Uç nokta yolu |
| `codegraph.mcp.allowed-origins` | `http://localhost:*`, `http://127.0.0.1:*` | Tarayıcı isteklerinde kabul edilen `Origin` değerleri. `Origin` başlığı olmayan istemciler her zaman kabul edilir. |

## Araçlar

| Araç | REST karşılığı | Ne için |
|---|---|---|
| `list_services` | `GET /api/services` | Servisler, katmanlar, servisler arası bağımlılıklar |
| `get_service_dependencies` | `GET /api/services/{name}/dependencies` | Bir servisin çağırdığı ve onu çağıran servisler |
| `list_screens` | `GET /api/screens` | Ekranlar (route → component) |
| `list_endpoints` | `GET /api/endpoints` | Endpoint'ler ve handler'ları |
| `trace_forward` | `GET /api/trace/forward` | Ekrandan tablolara uçtan uca iz |
| `trace_backward` | `GET /api/trace/backward` | Tablodan ekranlara geri iz |
| `get_endpoint_orchestration` | `GET /api/endpoints/{id}/orchestration` | Endpoint'in sıralı Feign/DB çağrıları |
| `find_table_usages` | `GET /api/tables/{name}/usages` | Tabloya erişenler (READ/WRITE) |
| `get_node` | `GET /api/nodes/{id}` | Düğüm detayı ve komşuları |
| `get_code` | `GET /api/nodes/{id}/code` | Kaynak kod bloğu |
| `search` | `GET /api/search` | Ad/path/route/tablo araması |
| `impact_analysis` | `GET /api/impact/{id}` | Değişikliğin etkileyeceği yukarı akış |
| `get_call_tree` | `GET /api/methods/{id}/call-tree` | Metot çağrı ağacı |
| `list_analysis_runs` | `GET /api/analysis` | Analiz geçmişi |
| `get_analysis_report` | `GET /api/analysis/{id}` | Analiz raporu, kopuk bağlantılar |

Düğüm parametreleri id (`method_…`) ya da doğal ad kabul eder: `/credit-application`, `offers`,
`GET /applications/{id}`, `CreditServiceImpl.updateStatus`. Analizi başlatma (`POST /api/analysis`) yazma işlemi
olduğu için MCP'de yoktur.

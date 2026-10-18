# Açık Sorular ve Varsayımlar

Geliştirme sırasında senin kararını gerektiren noktalar. Her maddede **şu an uygulanan varsayım** yazılı;
cevabın farklıysa ilgili kısmı güncelleyeceğim. Cevapları her maddenin altındaki `Cevap:` satırına yazabilirsin.

## Kurulum / Ortam

### Q1. Java çalışma ortamı
Makinedeki varsayılan `java` sürümü 27. PRD Java 17 istiyor, Neo4j 5.26 resmi olarak yalnızca 17 ve 21'i destekliyor.
Build ve testler `JAVA_HOME=$(/usr/libexec/java_home -v 17)` ile çalıştırılıyor.
- **Varsayım:** Uygulama Java 17 ile çalıştırılacak. Java 21'e geçmek de seçenek olabilir (Neo4j 2025.x'e geçişi açar).
- Cevap: Uygulama Java 17 ile çalıştırılacak.

### Q2. Spring Boot sürümü
PRD §8.2 "Spring Boot 3.x" diyor, proje 4.1.1 ile oluşturulmuş.
- **Varsayım:** 4.1.1'de kalınıyor (MCP SDK 2.x da Jackson 3 kullanıyor, Boot 4 ile uyumlu). PRD §8.2 güncellenmeli.
- Cevap: Bu şekilde ilerle gerekirse farklı bir ekleme yaparız

### Q3. Gerçek repolar (Faz 0)
Faz 0 ölçümleri (Symbol Solver çözme oranı, TSX grammar'ı) için gerçek repolara erişimim yok.
- **Varsayım:** `backend/src/test/resources/fixtures/mini-system` altındaki sentetik mini sistemle ilerleniyor (NFR-09).
  Gerçek repoların yerel yollarını verirsen `codegraph-repos.yml` ile ölçüm yapabilirim.
- Cevap: reporları şimdi veremiyorum ancak kodların konumları application.yml'dan değiştirilebilir olsun
- **Uygulama (M5):** Repolar doğrudan `application.yml` içinde `codegraph.repos` listesi olarak tanımlanabilecek
  (alanlar `codegraph-repos.yml` ile aynı). Alternatif olarak `codegraph.repos-file` ile ayrı bir dosya gösterilebilir.
  İkisi de verilirse `application.yml`'deki liste kullanılır. Ortam değişkeniyle de ezilebilir, örneğin
  `CODEGRAPH_REPOS_0_PATH=/repos/credit-api`.

### Q4. Commit'ler
Şu ana kadar hiçbir şey commit edilmedi; değişiklikler çalışma ağacında duruyor.
- **Varsayım:** Sen isteyene kadar commit atmıyorum. İstersen her milestone'u (M1, M2/M3, …) ayrı commit'e bölebilirim.
- Cevap: her milestone'da ayrı commit atabilirsin, ayrıca milestone içinde büyük bir iş bitince de commit atabilirsin

### Q5. Accessor çağrıları çağrı ağacında
Getter/setter çağrıları (Lombok ve record accessor'ları dahil) `CALLS` kenarı olarak kaydediliyor ve hedef metotta
`isAccessor=true` duruyor. PRD FR-04 "tam çağrı grafı" istediği için onları atmadım. UI ve sorgular varsayılan olarak
bunları gizleyecek.
- **Varsayım:** Kayıtta tutulup gösterimde filtrelenecek (`includeAccessors=false` varsayılanı).
- Cevap: varsayım ile ilerleyelim

## Frontend (M4)

### Q6. Gerçek ApiCalls dosyalarındaki HTTP istemcisi
Gerçek UI'larda tek kalıp var: ApiCalls dosyası (`ApiCalls.ts`, `XxxApiCalls.ts`) export edilen fonksiyonlardan oluşuyor
ve her biri ortak `callApi({ endpoint, method, body, notifyErrors })` yardımcısını çağırıyor. `endpoint` gateway'e
göreli: `getBaseUrl() + "/api/..."`, `getBaseUrl()` de BFF'in gateway prefix'ini (`"leasing-contract-bff"`) döndürüyor.
Arama yalnızca bu kalıba göre yapılıyor; `fetch`/`axios` desteği kaldırıldı. `callApi` çağırmayan export'lar
(`getBaseUrl` gibi) ApiCall sayılmıyor.
- Cevap: `callApi` kullanılıyor (örnek ekran görüntüleriyle iletildi).

### Q6b. `callApi`'de `method` verilmezse
Örneklerde `method` hep yazılmış. Verilmezse GET kabul ediliyor; `method` bir değişkense (statik bilinemiyorsa) ANY.
- **Varsayım:** `callApi`'nin varsayılanı GET. Farklıysa söyle, değiştireyim.
- Cevap:

### Q7. Gateway URL yapısı
`endpoint`'in ilk segmenti BFF'in gateway prefix'i, kalanı BFF'teki path kabul ediliyor
(`leasing-contract-bff/api/sub-contract-query/page-load` → `leasing-contract-bff` + `/api/sub-contract-query/page-load`).
Prefix BFF'e servis adı (`LEASING.NGLA.leasing-contract-bff` → son parça), repo adı, `aliases` ya da
`gateway-prefix` ile eşleniyor.
- **Varsayım:** `callApi` endpoint'in önüne yalnızca gateway taban adresini ekliyor; araya başka bir ek koymuyor.
- Cevap:

### Q8. Import takma adları
tsconfig/jsconfig `paths` + `baseUrl` (ve `extends`) okunuyor. `vite.config` / webpack `resolve.alias` okunmuyor.
tsconfig'te tanım yoksa `@/…` ve `~/…`, `src/…` kabul ediliyor.
- **Varsayım:** Bu yeterli. Gerçek projede farklı bir alias varsa repo yapılandırmasına `aliases` haritası eklerim.
- Cevap:

### Q8b. Ekranlar (route listesi)
Gerçek UI'larda ekranlar `src/routes.ts`'teki
`{ path, processId, component: asyncComponent(() => import("./features/.../XPage")), i18n }` nesnelerinden geliyor.
- **Arama:** route dizisi yapısına göre aranıyor (`path` ile birlikte `component`/`element`); dizinin kime verildiği
  önemsiz. `asyncComponent` gibi dinamik import'u saran her çağrı tanınıyor.
- **Ad:** ekranın adı import edilen dosyanın adı (`SellerContributionPage`).
- **Component:** dosyanın varsayılan export'u. `withX(Content)` gibi sarmalanmışsa içteki component alınıyor.
- **Varsayım:** sayfa dosyaları `export default` ile dışa aktarılıyor. Ekran sayısı route listesindekinden azsa Analiz
  ekranındaki "Route '…': component bulunamadı" sorunları nedenini gösterir.
- **Path:** string ya da sabitten (`RoutePaths.SELLER`) okunuyor. Statik bilinemiyorsa ekran yine çıkıyor; path
  ifadenin adıyla yazılıyor (`/{config.routes.x}`).
- Cevap:

### Q8c. `CopyContractForm.tsx` sözdizimi hatası
purchasing-ui'da `182:9`, contract-ui'da `1556:11` satırında `no viable alternative at input '('` hatası vardı.
Hata, yerel taramada bulunan eksiklerden biri olabilir; bu durumda düzelmiş olmalı. Hata sürüyorsa Analiz ekranındaki
sorun artık satırın kodunu da gösteriyor (`⟨…⟩`).
- **Soru:** Güncel sürümle hata sürüyor mu? Sürüyorsa sorundaki `⟨…⟩` kısmını (ya da satırın ±3 satırını) gönder.
- Cevap:

## Bağlama ve yükleme (M5)

### Q9. Analiz sırasında sorgular
Her analiz grafı sıfırdan üretiyor (FR-10): önce `AnalysisRun` dışındaki her şey siliniyor, sonra yeni graf yazılıyor.
Bu arada gelen REST/MCP sorguları boş ya da yarım graf görebilir. Mini sistemde bu süre bir saniyenin altında; büyük
repolarda yazma birkaç saniye sürebilir. Analiz aşamaları (parse vb.) graf silinmeden önce bittiği için bu pencere
yalnızca Load aşaması kadar.
- **Varsayım:** MVP'de bu kabul edilebilir. Analiz durumu (`RUNNING`, aşama `LOAD`) API'den görülebiliyor ve UI bu sırada
  uyarı gösterecek. Kesintisiz olması gerekirse ikinci bir veritabanına yazıp yer değiştirme (blue/green) eklenebilir.
- Cevap:

## Sorgu API'si ve MCP (M6)

### Q10. MCP araç açıklamalarının dili
Araç açıklamaları, sunucu talimatları ve sonuçlardaki özet metinler Türkçe (hata mesajları ve kodun geri kalanıyla
aynı). AI istemcileri Türkçeyi sorunsuz anlıyor, ancak MCP ekosisteminde açıklamalar genelde İngilizce.
- **Varsayım:** Türkçe kalsın. İngilizce istenirse metinler tek sınıfta (`CodeGraphTools`, `TextSummaries`).
- Cevap:

### Q11. Sunucunun ağa açılması ve kimlik doğrulama
Uygulama varsayılan olarak tüm ağ arayüzlerinde dinliyor; REST ve `/mcp` kimlik doğrulamasız. MCP için tarayıcıdan
gelen yabancı `Origin`'ler reddediliyor (DNS rebinding koruması, `codegraph.mcp.allowed-origins`), ama aynı ağdaki
herkes API'yi çağırabilir. Kaynak kodu (`get_code`) da bu yolla okunabiliyor.
- **Varsayım:** MVP her geliştiricinin kendi makinesinde ya da güvenilir iç ağda çalışıyor. Yalnızca yerel kullanım
  için `server.address=127.0.0.1` önerilir. Ortak sunucuya kurulacaksa token/SSO eklenmeli.
- Cevap:

## Arayüz (M7)

### Q12. Arayüzün sunulması
UI şu an ayrı bir Vite uygulaması: geliştirmede `npm run dev` (5173, `/api` istekleri 8080'e proxy'leniyor), üretimde
`npm run build` çıktısı `frontend/dist`. Jar UI'ı içermiyor.
- **Varsayım:** MVP için iki süreç yeterli. İstenirse Maven build'i `frontend/dist`'i `static/` altına kopyalayıp tek jar
  hâline getirebilir (Node'un build makinesinde olmasını gerektirir).
- Cevap:

### Q13. Frontend geliştirme bağımlılıkları (NFR-07)
Çalışma zamanı bağımlılıkları PRD'deki liste: React, Cytoscape, cytoscape-dagre, Prism. Geliştirme tarafında Vite,
TypeScript, `@vitejs/plugin-react`, `@types/*` ve testler için **Vitest** var. Vitest PRD'de geçmiyor ama Vite ile aynı
yapılandırmayı kullanıyor ve pakete girmiyor.
- **Varsayım:** Vitest kalsın. Yalnızca saf modüller test ediliyor (yönlendirme, graf elemanları, kod satırları, ağaç
  güncelleme); bileşen testi ve tarayıcı test aracı eklenmedi.
- Cevap:

### Q14. Kod panelinde çağrı yeri mi, çağrılan mı?
FR-21 kod panelinin "ilgili satır vurgulu" açılmasını istiyor. Bir kenar (çağrı) için ilgili satır iki yerde olabilir:
çağıran metottaki çağrı satırı ve çağrılan metodun kendisi.
- **Varsayım:** İkisi de sekme olarak açılıyor. İlk sekme çağrı yeri (satır vurgulu, görünür alana kaydırılmış), ikinci
  sekme çağrılan metot. Çağrı ağacında satıra tıklayınca sıra ters: önce metot, sonra çağrı yeri.
- Cevap:

## Tasarım kararları (bilgi amaçlı)

- **D1. Java düğümlerinin anahtarı `repo + fqn`:** PRD §8.6 `Method.fqn` ve `Class.fqn` için global benzersizlik istiyordu.
  Aynı FQN farklı repolarda bulunabildiği için anahtar repo adını da içeriyor. `fqn` alanı indeksli ama benzersiz değil.
- **D2. Metot FQN'i imzayı da içeriyor:** `paket.Sinif#ad(ParamTip1,ParamTip2)`. Böylece overload'lar ayrışıyor.
  Parametre tipleri **basit ad** ile yazılıyor: generic'ler atılıyor, dizi `[]`, varargs `...` oluyor. Örnek:
  `CreditServiceImpl#updateStatus(Long,String)`. Spring Data'dan miras alınan repository metotlarında
  (`save`, `findById` …) kaynak olmadığı için anahtar parantezsiz: `CreditRepository#save`.
- **D3. Kararlı düğüm id'si:** `<önek>_<sha256(etiket+anahtar)[:16]>`, örneğin `method_3f9a1c0be2d47a55`. Bu id analizler arasında değişmiyor.
- **D4. Tablo anahtarı:** `schema.name` (schema yoksa yalnızca `name`), büyük-küçük harf duyarsız.
- **D5.** Neo4j'nin `neo4j-slf4j-provider` bağımlılığı exclude edildi (Logback ile çakışıyordu). Bolt kapalı.
- **D6. JVM locale'i:** JavaParser birçok yerde locale'siz `toLowerCase()` kullanıyor. Makinenin locale'i `tr_TR`
  olduğunda `int` tipi `ınt`'e dönüşüyor ve symbol solver çöküyordu. Varsayılan locale Türkçe/Azerice ise analiz
  başlamadan `Locale.ROOT`'a çekiliyor (`ParserLocale`).
- **D7. Constructor'lar `Method` düğümü değil.** Constructor içindeki çağrılar (nadiren iş mantığı) şimdilik çağrı
  grafına girmiyor. Gerekirse `<init>` adıyla eklenebilir.
- **D8. LIBRARY repoları önce analiz ediliyor.** Tip modelleri ve kaynak kökleri sonraki repolara aktarılıyor; ortak
  kütüphanedeki DTO, Feign arayüzü ve repository'ler böylece çözülüyor. Graf yüklemesinde bir FQN önce aynı repoda,
  sonra LIBRARY repolarında aranıyor.
- **D9. Çağrı sırası çalışma sırası:** Argümandaki çağrı dıştaki çağrıdan önce geliyor
  (`checkLimit(toLimitRequest(r))` → 1 `toLimitRequest`, 2 `checkLimit`). JDK ve kütüphane çağrıları kaydedilmiyor.
  `AMBIGUOUS` çağrıda her aday için ayrı bir kenar var ve hepsi aynı `order` değerini taşıyor.
- **D10. `@Primary` / `@Qualifier`:** Birden çok implementasyon varsa bu ipuçlarıyla daraltılıyor. Daraltılamazsa sonuç
  `AMBIGUOUS` kalıyor.
- **D11. TSX grameri:** grammars-v4'te TSX grameri yok. TypeScript lexer/parser'ına JSX modları eklenerek tek
  bir `Tsx` grameri yazıldı (MIT, kaynak notu dosya başında). Gerçek kodda çıkan eksikler giderildi: `import type`,
  indexed access ve tuple tipleri, `as const`, `satisfies`, `?.()`, generic ok fonksiyonu. Ayrıca gramerdeki aşırı
  serbest kurallar daraltıldı: anahtar sözcüksüz değişken bildirimi, parantezsiz tipli ok fonksiyonu ve
  `f<A<B>, C>(x)` belirsizliği. Böylece hızlı SLL modu, gerçek dosyalarda yavaş LL moduna düşmüyor.
  12 bin satırlık bir TSX dosyası JVM'deki ilk ayrıştırmada ~18 sn sürüyor (ANTLR DFA önbelleğinin ısınması).
  Sonraki dosyalarda aynı dosya ~50 ms. Önbellek JVM boyunca paylaşılıyor; yani bu maliyet analiz başına bir kez
  ödeniyor.
  - **Yerel React kodu taraması (2.300 TS/TSX/JS dosyası):** eksik bulunan yapılar eklendi. Bunlar:
    - sınıfta noktalı virgülsüz alanlar, `#özel` alanlar, her sırada değiştiriciler (`static override`,
      `private static async`), gövdesiz aşırı yükleme imzaları, `constructor(private readonly x)`;
    - varsayılanlı tip parametreleri (`<T extends A = B>`, `<const T>`) ve generic `function <T>()`;
    - `||=` ve `&&=`, dönüş tipli nesne metotları (`{ set(x): void {} }`), `declare function/class/global/module`;
    - generic JSX component'leri (`<Table<Row> />`), `import x from "./a.json" with { type: "json" }`.
    Sonuç: 70 hatalı dosya → 0.
  - **Satır sonu yüklemi:** `return`/`break`/`throw` sonrasındaki satır sonu, araya girinti boşluğu girince
    görülmüyordu. Noktalı virgülsüz kodda `if (x) return` ile sonraki girintili satır tek ifade sanılıyordu. Artık
    aradaki tüm gizli token'lara bakılıyor.
  - **Hatalı dosyalar:** ANTLR'ın hata toparlaması eksik çocuklu düğümler bırakıyor. Eskiden bu, indirgemede NPE'ye
    yol açıyor ve dosyanın tamamı atılıyordu ("Ayrıştırılamadı: NullPointerException …"). Artık yalnızca bozuk alt
    ağaç atlanıyor, dosyanın geri kalanındaki component ve kullanımlar korunuyor. Sözdizimi hatası sorununa hatalı
    satırın kodu da ekleniyor (`⟨…⟩`).
- **D12. ApiCall URL'i:** `urlTemplate`, `callApi` endpoint'inin değerlendirilmiş hali; dinamik kısımlar `{*}`
  (ör. `leasing-contract-bff/api/sub-contract-view/{*}/payment-plan`). İlk segment `targetPrefix` (BFF'in gateway
  prefix'i); kalan kısım `normalizedPath` oluyor. İlk segment statik değilse ikisi de `null` kalıyor ve
  bağlantı `UNRESOLVED` sayılıyor. Kaynaktaki asıl ifade `code` alanında duruyor.
- **D13. Component ve kullanımlar:**
  - Component olarak sayılanlar:
    - büyük harfle başlayan ve JSX içeren üst düzey fonksiyon veya sınıf;
    - `memo`/`forwardRef` vb. sarmalayıcıya verilen satır içi fonksiyon;
    - adsız varsayılan export (adı dosyadan geliyor).
  - Component anahtarı `dosya#bildirimAdı`.
  - Kullanım iki biçimde: doğrudan çağrı (`await searchSubContracts(req)`, via `DIRECT`) ya da React Query hook'u
    içinden (via `REACT_QUERY`).
  - Projedeki `useX` hook'ları (ör. `src/shared/hooks` altındaki `useQuery` sarmalayıcıları) çağrıldıkları yerde
    component'e açılıyor. Kullanımın `hook` alanı zinciri tutuyor (`useSubContractPaymentPlan > useQuery`); satırı
    component'teki çağrı satırı.
  - Aynı ApiCall aynı yolla (via/hook) birden çok kez kullanılırsa tek kullanım sayılıyor.
  - Ekranlar route dizilerinden (bkz. Q8b) ve JSX `<Route>` ağaçlarından çıkıyor.
  - Route `element`'i sarmalayıcı içeriyorsa (`<Suspense>`, `<RequireAuth>`) en içteki proje component'i ekran oluyor.
  - Path'siz layout route'ları ekran sayılmıyor; `index: true` route'u üst route'un path'ini alıyor.
- **D14. Linker eşleştirme kuralları:**
  - **ApiCall → BFF:** `targetPrefix`, BFF'in gateway prefix'iyle eşleşir (büyük-küçük harf duyarsız; prefix
    verilmezse servis adı). Kalan path eşleşmezse prefix'li hali de deneniyor (gateway prefix'i kesmiyorsa diye).
  - **Feign → servis:** sırasıyla servis adı (`spring.application.name`), repo adı ve `aliases` ile eşleşiyor.
    Path eşleşmezse hedefin context-path'i eklenerek tekrar deneniyor.
  - **HTTP metodu:** aynı olmalı; `ANY` her metotla eşleşiyor.
  - **Birden çok aday:** Spring'e benzer şekilde segmentleri birebir tutan (`/a/me` ↔ `/a/me`, `/a/{*}` ↔ `/a/{*}`)
    ve metodu açıkça aynı olan endpoint tercih ediliyor. Yine birden fazla kalırsa `AMBIGUOUS` ve her adaya kenar.
  - **Çözümlenemeyen bağ:** kaynak düğümde `resolution=UNRESOLVED` ve `unresolvedReason` olarak duruyor, kenar yok.
    Endpoint bulunamasa da hedef servis biliniyorsa (`targetService`) `DEPENDS_ON` yine çıkıyor.
- **D15. Düğüm anahtarları:**
  - **Repoya ait düğümler:** `repo|fqn` (frontend'de `repo|dosya#ad`). Aynı FQN farklı repolarda çakışmıyor.
  - **Diğerleri:** Service adıyla, Endpoint `servis|METOT|normalizePath` ile, Table `şema|ad` (küçük harf) ile.
    Aynı tabloyu kullanan servisler tek Table düğümünde buluşuyor.
  - **Repolar arası hedefler** (çağrı, override, entity): önce aynı repoda, sonra LIBRARY repolarında aranıyor.
  - **Hedefi çözülemeyen çağrılar** kenar olmuyor: ifadeleri çağıran metodun `unresolvedCalls` listesinde ve raporda.
  - **Ek özellik:** tüm repo düğümlerinde `repo` ve `service` var.
- **D16. `DEPENDS_ON` ve dış servisler:**
  - **`callCount`:** frontend için hedef BFF'e giden ApiCall sayısı, Java servisleri için Feign çağrı noktası sayısı.
    Tanımlı ama hiç çağrılmayan Feign istemcisi `callCount=0` ile görünüyor.
  - **Analizde olmayan Feign hedefleri** (ör. `legacy-score-service`): `analyzed=false` bir Service düğümü
    oluşturuluyor, sistem görünümünde gösteriliyor.
- **D17. Analiz çalıştırma:**
  - **Sıra:** LIBRARY repoları önce analiz ediliyor; kaynak kökleri ve tip modelleri sonraki repolara veriliyor.
  - **Hata dayanıklılığı:** bir repo hata verirse analiz durmuyor, hata rapora yazılıyor.
  - **Rapor (FR-12):** `AnalysisRun` düğümünde JSON olarak saklanıyor (`report`, `stats`), başarısız analizler de
    kaydediliyor. Analizler arası geçmiş korunuyor, aynı anda tek analiz çalışıyor.
  - **`commitHash`:** `.git` dizininden git çalıştırılmadan okunuyor.
- **D18. Düğüm referansları:** REST'teki `{id}` ve MCP'deki `ref` parametreleri düğüm id'si ya da doğal ad kabul
  ediyor: route (`/credit-application`), tablo (`offers`, `LEASING.CREDIT`), endpoint (`GET /applications/{x}`, başında
  servis adıyla da; parametre adı fark etmez), servis, component/sınıf adı, `Sinif.metot`.
  - **Birden çok eşleşme:** Ad birden çok düğüme uyarsa adaylar id'leriyle birlikte hata olarak dönüyor (400 /
    `isError`). İstisna: tek bir ekran, servis ya da tablo varsa o tercih ediliyor.
  - **İçinde `/` olan referanslar:** URL path'inde kullanılamadığı için REST'te id ya da sorgu parametresi (`from`, `q`)
    kullanılıyor.
- **D19. İz anlamı:**
  - **İleri iz:** yalnızca tabloya, entity'ye, endpoint'e, ApiCall'a ya da Feign/repository metoduna giden dallar
    tutuluyor (`prune=false` ile tamamı).
  - **Geri iz:** budanmıyor, çünkü zamanlanmış işler gibi ekrana bağlı olmayan çağıranlar da önemli.
  - **Ekranlar:** izde ekranın component ağacı gösterilmiyor. Ekran, isteklerine (ApiCall) doğrudan bağlanıyor; kenarda
    isteğin çıktığı component, satır ve hook zinciri yazıyor. Component'ten başlanan izde ağaç olduğu gibi kalıyor.
  - **Ekrandan çağrı ağacı:** ekran → istekleri (önce üst component'ler, her component'te kod sırasıyla; aynı istek bir
    kez) → BFF endpoint'i → handler → …
  - **Erişimciler:** getter/setter izlerde varsayılan olarak yok, etki analizinde var.
  - **Sınırlar:** derinlik ve düğüm sınırı (varsayılan 40 / 2000) aşılırsa sonuç `truncated` işaretleniyor ve daraltma
    öneriliyor.
  - **Orkestrasyon:** adımlar kod sırasıyla 1, 2, 3… numaralanıyor; ara metot zinciri `via` alanında.
- **D20. REST eklemeleri:** PRD tablosuna `GET /api/analysis` (analiz listesi), `GET /api/endpoints` (endpoint listesi)
  ve karşılıkları `list_analysis_runs`, `list_endpoints` eklendi. Hatalar RFC 9457 problem detayı olarak dönüyor: 404
  bulunamadı, 400 geçersiz parametre ya da belirsiz referans, 409 analiz zaten çalışıyor.
- **D21. MCP sunucusu:**
  - **Taşıma:** resmî Java SDK (2.0.1), Streamable HTTP, `/mcp` yolunda ayrı servlet. stdio için
    `java -jar … --mcp-stdio-bridge` (ayrıntılar `docs/mcp.md`'de).
  - **Sonuçlar:** her araç özet metin ve yapılandırılmış JSON döndürüyor. Token tasarrufu için iz araçlarında alt graf
    yalnızca `includeGraph=true` ile geliyor; etki analizinde en yakın 100 düğüm listeleniyor (sayımlar her zaman tam).
  - **Hatalar:** sorgu hataları protokol hatası değil, araç hatası (`isError`) olarak dönüyor; AI düzeltip tekrar
    deneyebiliyor.
  - **Parite:** her `/api` uç noktası `@McpEquivalent("araç")` ya da `@RestOnly("gerekçe")` taşımak zorunda.
    `McpParityTest` iki yönlü eşleşmeyi kontrol ediyor; eşleşmezse build kırılıyor.
- **D22. Arayüz yapısı:**
  - **Yönlendirme:** hash tabanlı (`#/trace?from=…&dir=backward`). Her görünüm paylaşılabilir bir bağlantı;
    yönlendirme kütüphanesi kullanılmadı.
  - **Sistem görünümü:** sütunlar katman sırasında (Frontend → BFF → API → Adaptör → Kütüphane → Analiz dışı). Tabloları
    olan servisler tek bir "Veritabanı" düğümüne bağlanıyor; kenarda tablo sayısı yazıyor (`/api/services`'e
    `tableCount` eklendi). Tablolar sistem görünümünde tek tek çizilmiyor, ayrıntı servis panelinde ve izde.
  - **İz görünümü:** düğümler servis kutuları (compound) içinde, dagre ile soldan sağa yerleşiyor. Çağrı kenarlarında
    çağıran başına 1..n sıra numarası ve koşul/döngü/async/lambda işaretleri var. Geniş graflarda yazılar okunur
    kalsın diye tamamı sığdırılmıyor, başlangıç düğümünden başlanıyor; ⤢ düğmesi tamamını gösteriyor.
  - **Çağrı ağacı:** ilk istekte seçilen derinlik geliyor, sonraki seviyeler tıklayınca tek seviye yükleniyor. Atalar
    backend'e gönderildiği için özyineleme tembel yüklemede de doğru işaretleniyor.
  - **Analiz bitişi:** RUNNING iken saniyede bir yoklanıyor. Analiz bitince açık görünümler kendiliğinden yeniden
    yükleniyor.

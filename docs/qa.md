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
Şu kalıplar tanınıyor:
- `fetch(url, { method })`;
- `axios(config)`, `axios.get(url)` vb.;
- `axios.create({ baseURL })` ile oluşturulmuş istemciler (taban adres URL'nin önüne ekleniyor);
- path'e benzeyen URL ile çağrılan genel istemciler (`http.get('/x')`);
- tek `return`'lü yardımcı fonksiyonlar (`buildUrl(...)`) ve içinde HTTP çağrısı olan yardımcılar (`request('POST', url)`), en fazla 3 seviye.

Sınıf tabanlı servisler (`class ScoringApi { getScore() {...} }` + `export default new ScoringApi()`) ve
interceptor'la değişen URL'ler tanınmıyor. HTTP çağrısı bulunamayan export'lar sorun olarak raporlanıyor.
- **Varsayım:** ApiCalls dosyaları export edilen fonksiyonlardan (veya fonksiyon içeren export edilmiş nesne
  literallerinden) oluşuyor ve yukarıdaki istemcilerden birini kullanıyor. Farklıysa bir örnek dosya iletirsen
  ona göre genişletirim.
- Cevap:

### Q7. Gateway URL yapısı
Frontend URL'i `<gateway taban adresi>/<BFF gateway prefix'i>/<BFF path'i>` kabul ediliyor. Gateway taban adresi bir
ortam değişkeni olabilir (`import.meta.env.X`, `process.env.X`, `window._env_.X`), mutlak bir adresin host'u olabilir
ya da `/` ile başlayan göreli URL'lerde proxy olabilir. Gateway ile BFF prefix'i arasında ortak bir ek varsa
(ör. `/api/leasing-credit-bff/...`), ilk segment yanlışlıkla `api` olur.
- **Varsayım:** Ortak ek yok. Varsa repo yapılandırmasına `gatewayBasePath` (ör. `/api`) eklerim.
- Cevap:

### Q8. Import takma adları
tsconfig/jsconfig `paths` + `baseUrl` (ve `extends`) okunuyor. `vite.config` / webpack `resolve.alias` okunmuyor.
tsconfig'te tanım yoksa `@/…` ve `~/…`, `src/…` kabul ediliyor.
- **Varsayım:** Bu yeterli. Gerçek projede farklı bir alias varsa repo yapılandırmasına `aliases` haritası eklerim.
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
- **D12. ApiCall URL'i:** `urlTemplate` değerlendirilmiş biçimde tutuluyor: gateway için `{gateway}`, dinamik kısımlar
  için `{*}` (ör. `{gateway}/leasing-credit-bff/applications/{*}`). Gateway sonrası ilk segment `targetPrefix` (BFF'in
  gateway prefix'i); kalan kısım `normalizedPath` oluyor. İlk segment statik değilse ikisi de `null` kalıyor ve
  bağlantı `UNRESOLVED` sayılıyor. Kaynaktaki asıl ifade `code` alanında duruyor.
- **D13. Component ve kullanımlar:**
  - Component olarak sayılanlar:
    - büyük harfle başlayan ve JSX içeren üst düzey fonksiyon veya sınıf;
    - `memo`/`forwardRef` vb. sarmalayıcıya verilen satır içi fonksiyon;
    - adsız varsayılan export (adı dosyadan geliyor).
  - Component anahtarı `dosya#bildirimAdı`.
  - Projedeki `useX` hook'ları çağrıldıkları yerde component'e açılıyor. Hook'tan gelen kullanımın satırı, component'teki çağrı satırı.
  - Aynı ApiCall aynı yolla (via/hook) birden çok kez kullanılırsa tek kullanım sayılıyor.
  - Route `element`'i sarmalayıcı içeriyorsa (`<Suspense>`, `<RequireAuth>`) en içteki proje component'i ekran oluyor.
  - Path'siz layout route'ları ekran sayılmıyor.

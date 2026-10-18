# Loglar ve debug

Her analizde INFO seviyesinde repo başına bir özet satırı, sorunların türe göre özeti ve bağlama sonuçları yazılır:

```
purchasing-ui: 312 dosya, 140 component, 38 ekran, 285 ApiCall, 190 ApiCall kullanımı, 2 sorun (36930 ms)
purchasing-ui: sorun özeti: 2× ApiCall '…': callApi çağrısında '…' bulunamadı (ör. src/features/x/api/ApiCalls.ts:12 …)
ApiCall bağları: 270 çözüldü, 3 belirsiz, 12 çözülemedi
```

Sorunların tam listesi arayüzde **Analiz** ekranındadır.

## Debug'ı açmak

Paket bazında seviye verilir; `DEBUG` yeterli, `TRACE` dosya dosya ayrıntı da yazar.

| Logger | Ne yazar |
|---|---|
| `me.emreihtiyar.codegraph` | her şey |
| `me.emreihtiyar.codegraph.extract.ts` | UI analizi: taranan dosyalar, tsconfig takma adları, çözülemeyen import'lar, her ApiCall (endpoint, metot, hedef BFF), `callApi` çağırmadığı için atlanan export'lar, route dizileri, component/ekran sayıları, her ekranın route'u ve component'i bulunamayan route'lar |
| `me.emreihtiyar.codegraph.link` | BFF gateway prefix tablosu, her ApiCall/Feign bağının sonucu ve nedeni |
| `me.emreihtiyar.codegraph.analysis` | her sorunun tek tek listesi |

**IntelliJ:** Run → Edit Configurations → CodeGraphApplication → *Program arguments*:

```
--logging.level.me.emreihtiyar.codegraph=DEBUG
```

(Spring Boot run configuration'ında *Logging* altındaki tablodan da eklenebilir.)

**Komut satırı:**

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--logging.level.me.emreihtiyar.codegraph=DEBUG"
java -jar target\CodeGraph-0.0.1-SNAPSHOT.jar --logging.level.me.emreihtiyar.codegraph=DEBUG
```

**Ortam değişkeni** (PowerShell; noktalar `_`, harfler büyük):

```powershell
$env:LOGGING_LEVEL_ME_EMREIHTIYAR_CODEGRAPH = "DEBUG"
```

**Kalıcı:** `backend/src/main/resources/application.yaml` sonundaki `logging` bloğunun yorumunu kaldır.

Çıktıyı dosyaya da almak için `--logging.file.name=./.codegraph/codegraph.log` eklenebilir; uzun DEBUG
çıktısını paylaşmak için en kolayı budur.

## UI hiç ApiCall bulamıyorsa

ApiCall, ApiCalls dosyasının export ettiği ve `callApi({ endpoint, method })` çağıran fonksiyondur.

1. `adı 'ApiCalls' içeren ... dosyası yok` uyarısı varsa dosya adları farklıdır.
2. `... callApi çağırmıyor, ApiCall sayılmadı; çağrılar: ...` DEBUG satırları atlanan export'ları ve gövdelerindeki
   çağrıları gösterir (`getBaseUrl` gibi yardımcılar için normaldir).
3. `callApi çağrısında 'endpoint' bulunamadı` sorunu, `callApi`'ye nesne literali yerine çözülemeyen bir değişken
   verildiğini gösterir.
4. ApiCall'lar bulunuyor ama bağlanmıyorsa `me.emreihtiyar.codegraph.link` DEBUG'ı hedef prefix'i ve BFF
   tablosunu yan yana gösterir; uyuşmuyorsa repos dosyasında BFF'e `gateway-prefix` verin.

## UI'da ekran yoksa ya da eksikse

Ekranlar route dizilerinden çıkar: `path` ile birlikte `component`/`element` içeren nesneler, ör.
`{ path: "/seller-contribution", component: asyncComponent(() => import("./features/.../SellerContributionPage")) }`.

1. `hiç ekran bulunamadı` uyarısı varsa route dizisi tanınmamıştır; `me.emreihtiyar.codegraph.extract.ts` DEBUG'ı
   bulunan her route dizisini (`… route dizisi`) ve her ekranı (`ekran /path 'Ad' → dosya#component`) yazar.
2. `Route '…': component bulunamadı` sorunu, import edilen dosyanın projede olmadığını ya da varsayılan export'unun
   component olarak tanınmadığını gösterir.
3. Ekran var ama isteği yoksa ekranın component'lerinde ApiCall kullanımı bulunamamıştır; TRACE seviyesi her
   kullanımı (`component → ApiCall`) listeler.

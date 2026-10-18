# Loglar ve debug

Her analizde INFO seviyesinde repo başına bir özet satırı, sorunların türe göre özeti ve bağlama sonuçları yazılır:

```
purchasing-ui: 312 dosya, 140 component, 38 ekran, 0 ApiCall, 0 ApiCall kullanımı, 285 sorun (36930 ms)
purchasing-ui: sorun özeti: 285× ApiCall adayı '…' içinde HTTP çağrısı bulunamadı (ör. src/api/xApiCalls.ts:12 …; gövdedeki çağrılar: ServiceCaller.call({serviceName, path}))
ApiCall bağları: 120 çözüldü, 3 belirsiz, 14 çözülemedi
```

Sorunların tam listesi arayüzde **Analiz** ekranındadır.

## Debug'ı açmak

Paket bazında seviye verilir; `DEBUG` yeterli, `TRACE` dosya dosya ayrıntı da yazar.

| Logger | Ne yazar |
|---|---|
| `me.emreihtiyar.codegraph` | her şey |
| `me.emreihtiyar.codegraph.extract.ts` | UI analizi: taranan dosyalar, tsconfig takma adları, çözülemeyen import'lar, her ApiCall adayı (bulunan URL ve hedef BFF ya da neden bulunamadığı), component/ekran sayıları, her ekranın route'u |
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

1. Özet satırında `0 ApiCall` ve `HTTP çağrısı bulunamadı` sorunları varsa sorundaki **gövdedeki çağrılar**
   kısmı ApiCalls dosyalarının hangi istemciyi kullandığını gösterir. Desteklenenler: `fetch`, `axios` ve
   `axios.create` örnekleri, `x.get('/yol')` gibi istemciler, proje içi yardımcı fonksiyonlar (3 seviyeye kadar
   içine girilir) ve son çare olarak URL'ye benzeyen (`/` ya da `http` ile başlayan, ortam değişkeniyle başlayan)
   argüman. Bunların dışındaki bir kalıp için o çağrının bir örneğini paylaşın.
2. `adı 'ApiCalls' içeren ... dosyası yok` uyarısı varsa dosya adları farklıdır.
3. `export ... ApiCall adayı sayılmadı` DEBUG satırları export'un fonksiyon, nesne veya sınıf olmadığını gösterir.
4. ApiCall'lar bulunuyor ama bağlanmıyorsa `me.emreihtiyar.codegraph.link` DEBUG'ı hedef prefix'i ve BFF
   tablosunu yan yana gösterir; uyuşmuyorsa repos dosyasında BFF'e `gateway-prefix` verin.

# Windows'ta çalıştırma

## İlk kurulum

Java 17 gerekir. PowerShell'de:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"   # kendi JDK yolun
cd backend
.\mvnw.cmd generate-sources   # ANTLR TSX ayrıştırıcısını üretir (ts.parser.* sınıfları)
.\mvnw.cmd spring-boot:run
```

IntelliJ'de ilk açılışta Maven panelinden **Reload All Maven Projects** yapılmalı; `target/generated-sources/antlr4`
böylece kaynak klasörü olarak işaretlenir. Arayüz için `frontend` klasöründe `npm install` ve `npm run dev`.

## Repo yolları

YAML'da Windows yolları ya `/` ile ya da tek tırnak içinde yazılmalı. Çift tırnak içinde `\` kaçış karakteridir.

```yaml
codegraph:
  repos:
    - name: purchasing-ui
      path: C:/repos/purchasing-ui          # ya da 'C:\repos\purchasing-ui'
      layer: FRONTEND
```

## MCP stdio köprüsü

JSON'da `\` iki kez yazılmalı:

```json
{
  "mcpServers": {
    "codegraph": {
      "command": "java",
      "args": ["-jar", "C:\\tools\\CodeGraph.jar", "--mcp-stdio-bridge"]
    }
  }
}
```

## Analiz edilen dosyalar

- **Satır sonları:** CRLF ve LF ikisi de desteklenir; satır numaraları ikisinde de aynıdır.
- **BOM:** Visual Studio/Notepad'in eklediği UTF-8 BOM atılır, UTF-16 BOM'lu dosyalar UTF-16 olarak okunur.
- **Kodlama:** UTF-8 olarak çözülemeyen dosyalar (eski "ANSI" kayıtlar) windows-1254 olarak okunur.
- **Büyük-küçük harf:** Windows'ta çalışan `import Button from './button'` gibi harf farkı olan import'lar, birebir
  eşleşme yoksa harf duyarsız olarak çözülür.

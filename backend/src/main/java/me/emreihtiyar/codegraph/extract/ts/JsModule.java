package me.emreihtiyar.codegraph.extract.ts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.emreihtiyar.codegraph.extract.ts.Js.ClassNode;
import me.emreihtiyar.codegraph.extract.ts.Js.Export;
import me.emreihtiyar.codegraph.extract.ts.Js.ExportFrom;
import me.emreihtiyar.codegraph.extract.ts.Js.ExportSpec;
import me.emreihtiyar.codegraph.extract.ts.Js.Func;
import me.emreihtiyar.codegraph.extract.ts.Js.Ident;
import me.emreihtiyar.codegraph.extract.ts.Js.Import;
import me.emreihtiyar.codegraph.extract.ts.Js.ImportBinding;
import me.emreihtiyar.codegraph.extract.ts.Js.Node;
import me.emreihtiyar.codegraph.extract.ts.Js.VarDecl;

/**
 * Tek bir kaynak dosyanın modül görünümü: üst düzey bildirimler, import'lar ve export'lar.
 *
 * <p>Varsayılan export bir bildirime bağlıysa o bildirimin adıyla, değilse (anonim fonksiyon, {@code memo(X)} gibi
 * ifade) {@value #DEFAULT} adlı sanal bir bildirim olarak tutulur.
 */
public final class JsModule {

    public static final String DEFAULT = "default";

    /** @param imported dışa aktarılan ad, {@code default} veya ad alanı için {@code *} */
    public record ImportRef(String from, String imported) {}

    /**
     * @param local    bu modüldeki bildirim adı; yeniden export'ta null
     * @param from     yeniden export'ta kaynak modül belirteci
     * @param imported yeniden export'ta kaynak modüldeki ad ({@code *}: ad alanı)
     */
    public record ExportRef(String local, String from, String imported) {}

    private final String path;
    private final List<String> lines;
    private final List<Node> body;
    private final Map<String, ImportRef> imports = new LinkedHashMap<>();
    private final Map<String, Node> declarations = new LinkedHashMap<>();
    private final Map<String, ExportRef> exports = new LinkedHashMap<>();
    private final List<String> starExports = new ArrayList<>();

    public JsModule(String path, String source, List<Node> body) {
        this.path = path;
        this.lines = source.lines().toList();
        this.body = List.copyOf(body);
        for (Node node : body) {
            index(node);
        }
    }

    private void index(Node node) {
        if (node instanceof Import i) {
            for (ImportBinding b : i.bindings()) {
                imports.put(b.local(), new ImportRef(i.from(), b.imported()));
            }
        } else if (node instanceof Export e) {
            indexExport(e);
        } else if (node instanceof ExportFrom e) {
            if (e.all()) {
                starExports.add(e.from());
            }
            for (ExportSpec spec : e.specs()) {
                exports.put(spec.exported(), e.from() == null
                        ? new ExportRef(spec.local(), null, null)
                        : new ExportRef(null, e.from(), spec.local()));
            }
        } else {
            declare(node);
        }
    }

    private void indexExport(Export e) {
        if (e.isDefault()) {
            Node value = e.declarations().get(0);
            String name = declare(value);
            if (name == null && value instanceof Ident i) {
                name = i.name();
            }
            if (name == null) {
                declarations.put(DEFAULT, value);
                name = DEFAULT;
            }
            exports.put(DEFAULT, new ExportRef(name, null, null));
            return;
        }
        for (Node decl : e.declarations()) {
            String name = declare(decl);
            if (name != null) {
                exports.put(name, new ExportRef(name, null, null));
            } else if (decl instanceof VarDecl v) {
                v.target().names().forEach(n -> exports.put(n, new ExportRef(n, null, null)));
            }
        }
    }

    /** Bildirimi kaydeder; adı döner (adsızsa null). */
    private String declare(Node node) {
        String name = null;
        if (node instanceof Func f) {
            name = f.name();
        } else if (node instanceof ClassNode c) {
            name = c.name();
        } else if (node instanceof VarDecl v) {
            if (v.target().name() != null) {
                name = v.target().name();
            } else {
                v.target().names().forEach(n -> declarations.putIfAbsent(n, v));
            }
        }
        if (name != null) {
            declarations.putIfAbsent(name, node);
        }
        return name;
    }

    /** Repo köküne göreli, '/' ayraçlı yol. */
    public String path() {
        return path;
    }

    public List<Node> body() {
        return body;
    }

    public Map<String, ImportRef> imports() {
        return imports;
    }

    public Map<String, Node> declarations() {
        return declarations;
    }

    public Map<String, ExportRef> exports() {
        return exports;
    }

    public List<String> starExports() {
        return starExports;
    }

    /** Dosya adı uzantısız; {@code index} dosyalarında üst dizinin adı. */
    public String baseName() {
        String[] parts = path.split("/");
        String file = parts[parts.length - 1];
        int dot = file.indexOf('.');
        String name = dot > 0 ? file.substring(0, dot) : file;
        if (name.equals("index") && parts.length > 1) {
            return parts[parts.length - 2];
        }
        return name;
    }

    /** 1 tabanlı, dahil satır aralığındaki kaynak kod. */
    public String code(int startLine, int endLine) {
        int from = Math.max(1, startLine);
        int to = Math.min(lines.size(), endLine);
        return from > to ? "" : String.join("\n", lines.subList(from - 1, to));
    }

    @Override
    public String toString() {
        return path;
    }
}

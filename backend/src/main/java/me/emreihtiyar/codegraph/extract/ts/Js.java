package me.emreihtiyar.codegraph.extract.ts;

import java.util.List;
import java.util.function.Consumer;

/**
 * ANTLR ayrıştırma ağacından indirgenmiş küçük JS/TS sözdizimi ağacı. Yalnızca çıkarıcıların ihtiyaç duyduğu
 * yapılar ayrı düğümlerdir (çağrılar, üye erişimleri, metinler, fonksiyonlar, JSX, import/export); geri kalan her şey
 * çocuklarını koruyan {@link Other} düğümüne iner, böylece ağaç dolaşımı hiçbir çağrıyı kaçırmaz. Tip bilgisi atılır.
 */
public final class Js {

    private Js() {}

    public sealed interface Node
            permits Ident, Str, Template, Literal, Binary, Unary, Cond, Call, New, Member, Index, Obj, Arr, Spread,
                    Func, ClassNode, Jsx, VarDecl, Return, Import, Export, ExportFrom, Other {
        /** 1 tabanlı başlangıç satırı. */
        int line();
    }

    /** Tanımlayıcı; {@code this}, {@code super} ve {@code import} (dinamik import, {@code import.meta}) dahil. */
    public record Ident(String name, int line) implements Node {}

    /** Metin literali veya yer tutucusuz template literal; değer kaçış dizileri çözülmüş hâldedir. */
    public record Str(String value, int line) implements Node {}

    /** {@code quasis.size() == exprs.size() + 1}. */
    public record Template(List<String> quasis, List<Node> exprs, int line) implements Node {}

    /** Sayı, boolean, null, regex. */
    public record Literal(String text, int line) implements Node {}

    /** İkili, mantıksal ve atama işlemleri; {@code op} kaynaktaki operatördür ({@code +}, {@code ||}, {@code =}...). */
    public record Binary(String op, Node left, Node right, int line) implements Node {}

    /** Tekli işlemler; {@code await}, {@code typeof}, {@code !} vb. */
    public record Unary(String op, Node operand, int line) implements Node {}

    public record Cond(Node test, Node then, Node otherwise, int line) implements Node {}

    public record Call(Node callee, List<Node> args, int line) implements Node {}

    public record New(Node callee, List<Node> args, int line) implements Node {}

    /** {@code a.b}, {@code a?.b}, {@code a.#b}. */
    public record Member(Node object, String property, int line) implements Node {}

    /** {@code a[b]}. */
    public record Index(Node object, Node index, int line) implements Node {}

    public record Obj(List<Prop> props, int line) implements Node {}

    /**
     * @param key    özellik adı; hesaplanan anahtar ve yayılımda null
     * @param value  kısa yazımda ({@code { a }}) {@link Ident}; metotlarda {@link Func}
     * @param spread {@code ...x}
     */
    public record Prop(String key, Node value, boolean spread) {}

    public record Arr(List<Node> elements, int line) implements Node {}

    public record Spread(Node expr, int line) implements Node {}

    /**
     * Fonksiyon bildirimi, ifadesi, ok fonksiyonu veya metot. Kısa gövdeli ok fonksiyonunun gövdesi tek bir
     * {@link Return}'dür.
     *
     * @param name bildirim veya metot adı; anonimse null
     */
    public record Func(String name, List<Pattern> params, List<Node> body, boolean async, int line, int endLine)
            implements Node {}

    /** @param superClass {@code extends} ifadesi (tip argümanları atılmış), yoksa null */
    public record ClassNode(String name, Node superClass, List<Node> members, int line, int endLine) implements Node {}

    /**
     * @param name   etiket adı ({@code div}, {@code ScoreBadge}, {@code Foo.Bar}); fragment'ta null
     * @param attrs  öznitelikler, kaynak sırasıyla
     */
    public record Jsx(String name, List<JsxAttr> attrs, List<Node> children, int line) implements Node {}

    /**
     * @param name  öznitelik adı; yayılımda ({@code {...props}}) null
     * @param value metin değerde {@link Str}, değersiz öznitelikte null
     */
    public record JsxAttr(String name, Node value) {}

    /**
     * Değişken bildirimi ({@code const/let/var}); bir bildirim listesi her değişken için ayrı düğüme iner.
     *
     * @param init başlangıç değeri, yoksa null
     */
    public record VarDecl(Pattern target, Node init, int line, int endLine) implements Node {}

    /**
     * Bağlama hedefi: tanımlayıcı veya yapı bozma kalıbı.
     *
     * @param name  tek tanımlayıcıysa adı, kalıpsa null
     * @param names kalıbın bağladığı tüm adlar
     */
    public record Pattern(String name, List<String> names) {}

    /** @param value dönüş değeri, yoksa null */
    public record Return(Node value, int line) implements Node {}

    /**
     * @param from     modül belirteci
     * @param bindings yerel ad → içe aktarılan ad ({@code default}, {@code *} veya dışa aktarılan ad)
     */
    public record Import(String from, List<ImportBinding> bindings, int line) implements Node {}

    public record ImportBinding(String local, String imported) {}

    /**
     * Dışa aktarılan bildirimler ({@code export const/function/class}) veya {@code export default ...}.
     *
     * @param declarations bildirim düğümleri; varsayılan dışa aktarımda tek bir ifade veya bildirim
     */
    public record Export(List<Node> declarations, boolean isDefault, int line) implements Node {}

    /**
     * {@code export { a as b }}, {@code export { a } from './x'}, {@code export * from './x'},
     * {@code export * as ns from './x'}.
     *
     * @param from  yeniden dışa aktarımda modül belirteci, yerel dışa aktarımda null
     * @param specs ad eşlemeleri; {@code export * from} için boş liste ve {@code all == true}
     */
    public record ExportFrom(String from, List<ExportSpec> specs, boolean all, int line) implements Node {}

    /** @param local yerel veya kaynak modüldeki ad ({@code *} ad alanı için) */
    public record ExportSpec(String local, String exported) {}

    /** Ayrı modellenmeyen yapı; çocukları kaynak sırasıyla korunur. */
    public record Other(List<Node> children, int line) implements Node {}

    /** Doğrudan alt düğümler, kaynak sırasıyla. */
    public static List<Node> children(Node node) {
        java.util.ArrayList<Node> out = new java.util.ArrayList<>();
        forEachChild(node, out::add);
        return out;
    }

    public static void forEachChild(Node node, Consumer<Node> action) {
        if (node instanceof Template t) {
            t.exprs().forEach(action);
        } else if (node instanceof Binary b) {
            action.accept(b.left());
            action.accept(b.right());
        } else if (node instanceof Unary u) {
            action.accept(u.operand());
        } else if (node instanceof Cond c) {
            action.accept(c.test());
            action.accept(c.then());
            action.accept(c.otherwise());
        } else if (node instanceof Call c) {
            action.accept(c.callee());
            c.args().forEach(action);
        } else if (node instanceof New n) {
            action.accept(n.callee());
            n.args().forEach(action);
        } else if (node instanceof Member m) {
            action.accept(m.object());
        } else if (node instanceof Index i) {
            action.accept(i.object());
            action.accept(i.index());
        } else if (node instanceof Obj o) {
            o.props().forEach(p -> action.accept(p.value()));
        } else if (node instanceof Arr a) {
            a.elements().forEach(action);
        } else if (node instanceof Spread s) {
            action.accept(s.expr());
        } else if (node instanceof Func f) {
            f.body().forEach(action);
        } else if (node instanceof ClassNode c) {
            if (c.superClass() != null) {
                action.accept(c.superClass());
            }
            c.members().forEach(action);
        } else if (node instanceof Jsx j) {
            j.attrs().stream().filter(a -> a.value() != null).forEach(a -> action.accept(a.value()));
            j.children().forEach(action);
        } else if (node instanceof VarDecl v) {
            if (v.init() != null) {
                action.accept(v.init());
            }
        } else if (node instanceof Return r) {
            if (r.value() != null) {
                action.accept(r.value());
            }
        } else if (node instanceof Export e) {
            e.declarations().forEach(action);
        } else if (node instanceof Other o) {
            o.children().forEach(action);
        }
    }

    /** Ön sıralı (kaynak sırası) tüm alt ağaç, düğümün kendisi dahil. */
    public static void walk(Node node, Consumer<Node> action) {
        action.accept(node);
        forEachChild(node, child -> walk(child, action));
    }

    /** Alt ağaçta JSX var mı? */
    public static boolean containsJsx(Node node) {
        boolean[] found = {false};
        walk(node, n -> found[0] |= n instanceof Jsx);
        return found[0];
    }

    /** {@code a.b.c} biçimindeki üye zincirinin metni; zincir değilse null. */
    public static String dottedName(Node node) {
        if (node instanceof Ident i) {
            return i.name();
        }
        if (node instanceof Member m && m.property() != null) {
            String object = dottedName(m.object());
            return object == null ? null : object + "." + m.property();
        }
        return null;
    }

    /** Çağrılan fonksiyonun basit adı: {@code f(...)} → f, {@code a.b.f(...)} → f. */
    public static String calleeName(Call call) {
        if (call.callee() instanceof Ident i) {
            return i.name();
        }
        if (call.callee() instanceof Member m) {
            return m.property();
        }
        return null;
    }
}

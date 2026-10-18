package me.emreihtiyar.codegraph.extract.ts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import me.emreihtiyar.codegraph.Fixtures;
import me.emreihtiyar.codegraph.config.Layer;
import me.emreihtiyar.codegraph.config.RepoDefinition;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.scan.RepoScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Birleştirilmiş TSX grameri: güncel TS/TSX sözdizimi ve fixture dosyaları hatasız ayrışmalı.
 *
 * <p>Gerçek dünya dosyalarıyla duman testi için: {@code -Drealworld=<dizin>} (dizindeki tüm TS/JS dosyaları
 * frontend reposu olarak analiz edilir; süre ve sorunlar yazdırılır).
 */
class TsSourceParserTest {

    static List<String> snippets() {
        return List.of(
            "const f = <T,>(x: T) => x;",
            "function A() { return <><B a=\"1\" {...p} c={x > 1 ? <C/> : null}>{/* c */}text {y}</B></>; }",
            "const s = `a ${b ? `c${d}` : '}'} e`; const o = {a: {b: 1}};",
            "export default function X({ a }: { a: number }) { useEffect(() => {}, []); return a < 3 ? <div/> : <span>{a}</span>; }",
            "const r = list.map((i) => <Li key={i.id} {...i} />); function noop() {/* x */}",
            "const m = new Map<string, Array<number>>(); const n = useState<string | null>(null);",
            "export const api = { get: async (id: string): Promise<Foo> => (await fetch(`/a/${id}`)).json() };",
            "if (a) { x(); } else if (b) { y() } label: for (const k of ks) { continue label }",
            "import type { A, B } from './t'; import { type C, d } from './u'; export type { A }; import React, { useState } from 'react'; import * as api from './api';",
            "type K = keyof typeof obj; type V = T[K]; type R = T extends (infer U)[] ? U : never; type M = { readonly [P in keyof T]?: T[P] }; type L = `a-${string}`;",
            "type Tup = [a: string, b?: number, ...rest: boolean[]]; let e = new Emitter<[elements: readonly El[], s: S]>();",
            "const k = ['a', id] as const; const c = { a: 1 } satisfies Cfg; const v = useState<string | null>(null); const q = useQuery<Foo, Error>({ queryKey: k });",
            "interface P { a?: string\n b: () => void\n c(x: number): string; [key: string]: unknown; get d(): number }",
            "function isFoo(x: unknown): x is Foo { return true } function assert(x: unknown): asserts x is string {}",
            "type I = import('./mod').Foo<string>; const n = -1; type Neg = -1 | 2n;",
            "export default class X extends React.Component<Props, State> { state: State = { a: 1 }; private readonly f = () => this.setState({}); render() { return <div onClick={this.f} />; } }",
            "const x = a < b && c > d; const y = f<T>(z); const g = a?.b?.(c)?.[d];",
            "export const Comp: React.FC<Props> = ({ a, ...rest }) => { const ref = useRef<HTMLDivElement>(null!); return <Ctx.Provider value={a}><div ref={ref} {...rest} /></Ctx.Provider>; };",
            // noktalı virgülsüz sınıf üyeleri, #alanlar, değiştirici sırası, aşırı yüklemeler, parametre özellikleri
            "class Q {\n private max: number\n #vfs\n #path = 1\n static override getType(): string { return 'x' }\n"
                    + " private static async open(url: string) {}\n start(): void\n start(p?: number) {}\n"
                    + " constructor(private readonly url: string) {}\n name!: string\n static get instance() { return 1 }\n"
                    + " async = false\n static() {}\n}",
            "export function f(id: string): boolean\nexport function f(id: any) { return true }\n"
                    + "declare function g(): void\ndeclare global { interface Window { x: number } }\nexport declare class Z {}",
            "type S<T, K extends keyof T = keyof T> = T[K]; const M = <T extends E = 'div'>({ as }: P<T>) => <div/>;"
                    + " function c<const T extends readonly unknown[]>(x: T) { return x }",
            "window.router ||= {}; a &&= b; c ??= d;",
            "const o = { set(light: string): void { d?.('x') }, get<T>(k: string): T { return null as T } };",
            "function w<T>() { return function <P extends { a: T }>(C: ComponentType<P>): ComponentType<P> { return C } }",
            // girintili sonraki satır: `return` ifadesiz biter
            "function r() {\n  if (x) return\n    \n  try {\n    a()\n  } catch {}\n  if (y) return\n  const z = 1\n}",
            "export const t = {} as T\n;(['a', 'b'] as const).forEach((type) => { t[type] = 1 })",
            "const a = <Table<Row> dataSource={rows} />; const b = <Select<Map<string, number>> value={v}><Option value='a'>A</Option></Select>;",
            "import json from './data.json' with { type: 'json' };"
        );
    }

    @ParameterizedTest
    @MethodSource("snippets")
    void parsesModernSyntax(String source) {
        var result = TsSourceParser.parse(source, true);
        assertThat(result.errors()).isEmpty();
        JsLowering.program(result.program()); // indirgeme hata vermemeli (tip bildirimleri boş liste üretir)
    }

    @Test
    void parsesFixtureFiles() throws Exception {
        Path web = Fixtures.miniSystem().resolve("leasing-web");
        try (Stream<Path> files = Files.walk(web.resolve("src"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                var result = TsSourceParser.parse(Files.readString(f), TsSourceParser.jsxEnabledFor(f.toString()));
                assertThat(result.errors()).as(f.toString()).isEmpty();
            }
        }
    }

    @Test
    void typeArgumentsAreNotComparisons() {
        var body = JsLowering.program(TsSourceParser.parse("const m = c<B<X, S>>(1); const n = a < b && c > d;", false).program());
        assertThat(((Js.VarDecl) body.get(0)).init()).isInstanceOf(Js.Call.class);
        assertThat(((Js.VarDecl) body.get(1)).init()).isInstanceOf(Js.Binary.class);
    }

    @Test
    void classMembersWithoutSemicolons() {
        var body = JsLowering.program(TsSourceParser.parse("""
                export class Api {
                  private client = createClient()
                  static override async load(id: string): Promise<void> {}
                  #secret = 1
                  fetch(): void
                  fetch(id?: string) { return this.client.get(id) }
                }
                """, false).program());
        var api = (Js.ClassNode) ((Js.Export) body.get(0)).declarations().get(0);
        assertThat(api.members()).extracting(m -> m instanceof Js.Func f ? f.name() + (f.async() ? " async" : "")
                        : ((Js.VarDecl) m).target().name())
                .containsExactly("client", "load async", "secret", "fetch");
    }

    /** Sözdizimi hatası ANTLR'ın kısmi ağacında eksik çocuklar bırakır; indirgeme çökmemeli, geri kalan korunmalı. */
    @Test
    void brokenSourceKeepsTheRest() {
        var broken = TsSourceParser.parse("""
                export function Broken() {
                  return <div>{items.map((i) => <Item key={i} </div>
                }
                """, true);
        assertThat(broken.errors()).isNotEmpty();
        JsLowering.program(broken.program());

        var partial = TsSourceParser.parse("""
                const A = () => {
                  @@@
                }
                export default function Page() { return <Form/> }
                """, true);
        assertThat(partial.errors()).isNotEmpty();
        assertThat(JsLowering.program(partial.program())).anySatisfy(n ->
                assertThat(n).isInstanceOfSatisfying(Js.Export.class, e -> assertThat(e.isDefault()).isTrue()));
    }

    @Test
    void shebangLineIsIgnored() {
        assertThat(TsSourceParser.parse("#!/usr/bin/env node\nconst a = 1;\n", false).errors()).isEmpty();
    }

    @Test
    void realWorldSmoke() {
        String dir = System.getProperty("realworld");
        if (dir == null) {
            return;
        }
        var repo = new RepoDefinition("realworld", Path.of(dir).toAbsolutePath(), Layer.FRONTEND, null, null, List.of());
        long start = System.nanoTime();
        RepoFacts facts = new FrontendRepoAnalyzer().analyze(new RepoScanner().scan(repo));
        System.out.printf("REALWORLD %dms components=%d renders=%d issues=%s%n", (System.nanoTime() - start) / 1_000_000,
                facts.components().size(), facts.renders().size(), facts.issues());
    }
}

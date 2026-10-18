package me.emreihtiyar.codegraph.extract.ts.parser;

import java.util.ArrayDeque;
import java.util.Deque;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;

/**
 * {@link TsxLexer}'ın temel sınıfı (grammars-v4 {@code TypeScriptLexerBase} + {@code JavaScriptLexerBase} uyarlaması).
 *
 * <p>Her {@code '{'} hem mod yığınına hem de burada tutulan "parantez türü" yığınına bir girdi ekler. Eşleşen
 * {@code '}'} template ifadesini kapatıyorsa {@code TemplateCloseBrace} olarak işaretlenir; JSX içindeki
 * ifadelerden sonra lexer otomatik olarak JSX moduna döner.
 */
public abstract class TsxLexerBase extends Lexer {

    private static final int BRACE = 0;
    private static final int TEMPLATE = 1;

    private final Deque<Boolean> scopeStrictModes = new ArrayDeque<>();
    private final Deque<Integer> braceKinds = new ArrayDeque<>();
    private Token lastToken;
    private Token tokenBeforeLast;
    private boolean useStrictDefault;
    private boolean useStrictCurrent;
    private int templateDepth;
    private boolean jsxEnabled = true;

    protected TsxLexerBase(CharStream input) {
        super(input);
    }

    /** {@code .ts} dosyalarında JSX kapalıdır ({@code <T>x} tip dönüşümü geçerlidir). */
    public void setJsxEnabled(boolean jsxEnabled) {
        this.jsxEnabled = jsxEnabled;
    }

    public void setUseStrictDefault(boolean value) {
        useStrictDefault = value;
        useStrictCurrent = value;
    }

    public boolean IsStrictMode() {
        return useStrictCurrent;
    }

    public boolean IsInTemplateString() {
        return templateDepth > 0;
    }

    @Override
    public Token nextToken() {
        Token next = super.nextToken();
        if (next.getChannel() == Token.DEFAULT_CHANNEL) {
            tokenBeforeLast = lastToken;
            lastToken = next;
        }
        return next;
    }

    /** Dengesiz '}' içeren (hatalı) kaynakta istisna yerine mevcut modda kalınır. */
    @Override
    public int popMode() {
        if (_modeStack.isEmpty()) {
            return _mode;
        }
        return super.popMode();
    }

    protected void ProcessOpenBrace() {
        braceKinds.push(BRACE);
        useStrictCurrent = !scopeStrictModes.isEmpty() && scopeStrictModes.peek() || useStrictDefault;
        scopeStrictModes.push(useStrictCurrent);
    }

    protected void StartTemplateExpression() {
        braceKinds.push(TEMPLATE);
    }

    protected void ProcessCloseBrace() {
        Integer kind = braceKinds.poll();
        if (kind != null && kind == TEMPLATE) {
            setType(TsxLexer.TemplateCloseBrace);
            return;
        }
        useStrictCurrent = !scopeStrictModes.isEmpty() ? scopeStrictModes.pop() : useStrictDefault;
    }

    protected void ProcessStringLiteral() {
        if (lastToken == null || lastToken.getType() == TsxLexer.OpenBrace) {
            String text = getText();
            if (text.equals("\"use strict\"") || text.equals("'use strict'")) {
                if (!scopeStrictModes.isEmpty()) {
                    scopeStrictModes.pop();
                }
                useStrictCurrent = true;
                scopeStrictModes.push(true);
            }
        }
    }

    protected void IncreaseTemplateDepth() {
        templateDepth++;
    }

    protected void DecreaseTemplateDepth() {
        templateDepth--;
    }

    protected boolean IsRegexPossible() {
        if (lastToken == null) {
            return true;
        }
        return switch (lastToken.getType()) {
            case TsxLexer.Identifier, TsxLexer.NullLiteral, TsxLexer.BooleanLiteral, TsxLexer.This,
                    TsxLexer.CloseBracket, TsxLexer.CloseParen, TsxLexer.OctalIntegerLiteral,
                    TsxLexer.DecimalLiteral, TsxLexer.HexIntegerLiteral, TsxLexer.StringLiteral,
                    TsxLexer.PlusPlus, TsxLexer.MinusMinus -> false;
            default -> true;
        };
    }

    /**
     * {@code '<'} bir JSX elemanı başlatabilir mi? Yalnızca ifade beklenen bir tokenden sonra. TSX'teki generic ok
     * fonksiyonları ({@code <T,>(x) => x}, {@code <T extends U>(x) => x}) JSX sayılmaz.
     */
    protected boolean IsJsxPossible() {
        if (!jsxEnabled || lastToken == null) {
            return false;
        }
        boolean afterExpressionStart = switch (lastToken.getType()) {
            case TsxLexer.Assign, TsxLexer.Colon, TsxLexer.Comma, TsxLexer.Default, TsxLexer.QuestionMark,
                    TsxLexer.Return, TsxLexer.OpenParen, TsxLexer.OpenBracket, TsxLexer.JsxOpeningElementOpenBrace,
                    TsxLexer.JsxChildrenOpenBrace, TsxLexer.Yield, TsxLexer.ARROW, TsxLexer.And, TsxLexer.Or,
                    TsxLexer.NullCoalesce, TsxLexer.Not, TsxLexer.Await, TsxLexer.TemplateStringStartExpression -> true;
            default -> false;
        };
        return afterExpressionStart && !looksLikeTypeParameters();
    }

    /** JSX açılış etiketinde ada hemen bitişik {@code '<'}: generic component'in tip argümanları. */
    protected boolean IsJsxTypeArgumentsPossible() {
        return lastToken != null && lastToken.getType() == TsxLexer.JsxOpeningElementId && tokenBeforeLast != null
                && switch (tokenBeforeLast.getType()) {
                    case TsxLexer.JsxElementBegin, TsxLexer.JsxOpeningElementBegin,
                            TsxLexer.JsxChildrenOpeningElementBegin -> true;
                    default -> false;
                };
    }

    private boolean looksLikeTypeParameters() {
        // _input konumu '<' karakterinden hemen sonradır
        int i = 1;
        while (Character.isWhitespace(_input.LA(i))) {
            i++;
        }
        if (!Character.isJavaIdentifierStart(_input.LA(i))) {
            return false;
        }
        StringBuilder name = new StringBuilder();
        while (Character.isJavaIdentifierPart(_input.LA(i))) {
            name.append((char) _input.LA(i));
            i++;
        }
        while (Character.isWhitespace(_input.LA(i))) {
            i++;
        }
        int next = _input.LA(i);
        if (next == ',') {
            return true;
        }
        if (next == 'e') {
            StringBuilder word = new StringBuilder();
            for (int j = i; Character.isJavaIdentifierPart(_input.LA(j)); j++) {
                word.append((char) _input.LA(j));
            }
            return word.toString().equals("extends");
        }
        return false;
    }
}

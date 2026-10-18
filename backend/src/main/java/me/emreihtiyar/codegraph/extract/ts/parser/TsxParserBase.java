package me.emreihtiyar.codegraph.extract.ts.parser;

import java.util.Set;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

/** {@link TsxParser}'ın temel sınıfı (grammars-v4 {@code TypeScriptParserBase} uyarlaması). */
public abstract class TsxParserBase extends Parser {

    protected TsxParserBase(TokenStream input) {
        super(input);
    }

    protected boolean p(String str) {
        return prev(str);
    }

    protected boolean prev(String str) {
        return _input.LT(-1).getText().equals(str);
    }

    protected boolean n(String str) {
        return next(str);
    }

    protected boolean next(String str) {
        return _input.LT(1).getText().equals(str);
    }

    protected boolean notLineTerminator() {
        return !lineTerminatorAhead();
    }

    protected boolean notOpenBraceAndNotFunctionAndNotInterface() {
        int nextTokenType = _input.LT(1).getType();
        return nextTokenType != TsxParser.OpenBrace && nextTokenType != TsxParser.Function_
                && nextTokenType != TsxParser.Interface;
    }

    /**
     * Sıradaki {@code <} bir çağrının tip argümanlarını mı başlatıyor ({@code f<A<B>, C>(x)})? TypeScript
     * derleyicisinin kuralı: dengeli bir tip argüman listesi kapanmalı ve ardından {@code (} veya template gelmelidir.
     * Doğruysa {@code <} karşılaştırma operatörü olarak ayrıştırılmaz.
     */
    protected boolean typeArgumentsAhead() {
        if (_input.LT(1).getType() != TsxParser.LessThan) {
            return false;
        }
        int depth = 0;
        int braces = 0;
        for (int i = 1; i < 256; i++) {
            int type = _input.LT(i).getType();
            switch (type) {
                case TsxParser.LessThan -> depth++;
                case TsxParser.MoreThan -> {
                    depth--;
                    if (depth == 0) {
                        int next = _input.LT(i + 1).getType();
                        return next == TsxParser.OpenParen || next == TsxParser.BackTick
                                || next == TsxParser.QuestionMarkDot && _input.LT(i + 2).getType() == TsxParser.OpenParen;
                    }
                }
                case TsxParser.OpenBrace -> braces++;
                case TsxParser.CloseBrace -> {
                    if (--braces < 0) {
                        return false;
                    }
                }
                case Token.EOF, TsxParser.SemiColon, TsxParser.Assign, TsxParser.And, TsxParser.Or, TsxParser.Not,
                        TsxParser.Plus, TsxParser.Multiply, TsxParser.Divide, TsxParser.Modulus, TsxParser.PlusPlus,
                        TsxParser.MinusMinus, TsxParser.NullCoalesce, TsxParser.Equals_, TsxParser.NotEquals,
                        TsxParser.IdentityEquals, TsxParser.IdentityNotEquals, TsxParser.LessThanEquals,
                        TsxParser.GreaterThanEquals, TsxParser.LeftShiftArithmetic, TsxParser.Return,
                        TsxParser.JsxElementBegin -> {
                    return false;
                }
                default -> {
                    // tip içinde geçerli olabilecek token
                }
            }
        }
        return false;
    }

    private static final Set<String> MODIFIERS = Set.of("public", "private", "protected", "static", "readonly",
            "declare", "abstract", "override", "accessor", "async");

    /**
     * Sınıf üyesinde sıradaki sözcük bir değiştirici mi? TypeScript derleyicisinin kuralı: değiştirici sözcüğünü
     * aynı satırda bir üye adı (ya da {@code *}, {@code [}, {@code #}) izlemelidir; {@code static()},
     * {@code readonly: boolean}, {@code async = false} gibi durumlarda sözcük üyenin adıdır.
     */
    protected boolean modifierAhead() {
        Token word = _input.LT(1);
        if (!MODIFIERS.contains(word.getText())) {
            return false;
        }
        Token next = _input.LT(2);
        for (int i = word.getTokenIndex() + 1; i < next.getTokenIndex(); i++) {
            String text = _input.get(i).getText();
            if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                return false;
            }
        }
        return switch (next.getType()) {
            case TsxParser.OpenParen, TsxParser.Colon, TsxParser.Assign, TsxParser.SemiColon, TsxParser.QuestionMark,
                    TsxParser.Not, TsxParser.CloseBrace, TsxParser.OpenBrace, TsxParser.Comma, TsxParser.LessThan,
                    Token.EOF -> false;
            default -> true;
        };
    }

    protected boolean closeBrace() {
        return _input.LT(1).getType() == TsxParser.CloseBrace;
    }

    /**
     * Önceki görünür token ile sıradaki token arasında satır sonu var mı? Aradaki tüm gizli token'lara (boşluk,
     * yorum) bakılır; grammars-v4'teki gibi yalnızca bir öncekine bakmak {@code return} ile girintili sonraki satır
     * arasındaki satır sonunu kaçırır.
     */
    protected boolean lineTerminatorAhead() {
        for (int index = getCurrentToken().getTokenIndex() - 1; index >= 0; index--) {
            Token token = _input.get(index);
            if (token.getChannel() != Lexer.HIDDEN) {
                return false;
            }
            String text = token.getText();
            if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\u2028') >= 0
                    || text.indexOf('\u2029') >= 0) {
                return true;
            }
        }
        return false;
    }
}

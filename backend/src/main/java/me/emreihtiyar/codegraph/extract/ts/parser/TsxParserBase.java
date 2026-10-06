package me.emreihtiyar.codegraph.extract.ts.parser;

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
        return !here(TsxParser.LineTerminator);
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

    protected boolean closeBrace() {
        return _input.LT(1).getType() == TsxParser.CloseBrace;
    }

    private boolean here(int type) {
        int index = getCurrentToken().getTokenIndex() - 1;
        if (index < 0) {
            return false;
        }
        Token ahead = _input.get(index);
        return ahead.getChannel() == Lexer.HIDDEN && ahead.getType() == type;
    }

    protected boolean lineTerminatorAhead() {
        int index = getCurrentToken().getTokenIndex() - 1;
        if (index < 0) {
            return false;
        }
        Token ahead = _input.get(index);
        if (ahead.getChannel() != Lexer.HIDDEN) {
            return false;
        }
        if (ahead.getType() == TsxParser.LineTerminator) {
            return true;
        }
        if (ahead.getType() == TsxParser.WhiteSpaces && index > 0) {
            ahead = _input.get(index - 1);
        }
        String text = ahead.getText();
        int type = ahead.getType();
        return type == TsxParser.MultiLineComment && (text.contains("\r") || text.contains("\n"))
                || type == TsxParser.LineTerminator;
    }
}

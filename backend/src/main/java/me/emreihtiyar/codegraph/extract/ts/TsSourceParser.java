package me.emreihtiyar.codegraph.extract.ts;

import java.util.ArrayList;
import java.util.List;
import me.emreihtiyar.codegraph.extract.ts.parser.TsxLexer;
import me.emreihtiyar.codegraph.extract.ts.parser.TsxParser;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;

/**
 * TS/JS/TSX/JSX kaynağını ANTLR4 ile ayrıştırır (PRD FR-07/08). Önce hızlı SLL modu denenir, sözdizimi hatasında
 * tam LL moduyla yeniden ayrıştırılır. Hatalı dosyalarda ANTLR'ın hata toparlamasıyla elde edilen kısmi ağaç
 * döner; hatalar ayrıca raporlanır.
 */
public final class TsSourceParser {

    /** @param errors "satır:sütun mesaj" biçiminde sözdizimi hataları (en çok 5) */
    public record Result(TsxParser.ProgramContext program, CommonTokenStream tokens, List<String> errors) {}

    private TsSourceParser() {}

    /** @param jsx JSX etkin mi ({@code .tsx}, {@code .jsx}, {@code .js}); {@code .ts}'de kapalı */
    public static Result parse(String source, boolean jsx) {
        try {
            return parse(source, jsx, PredictionMode.SLL, true);
        } catch (ParseCancellationException e) {
            return parse(source, jsx, PredictionMode.LL, false);
        }
    }

    public static boolean jsxEnabledFor(String fileName) {
        return !fileName.endsWith(".ts");
    }

    private static Result parse(String source, boolean jsx, PredictionMode mode, boolean bail) {
        List<String> errors = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) {
                if (errors.size() < 5) {
                    errors.add(line + ":" + charPositionInLine + " " + msg);
                }
            }
        };
        TsxLexer lexer = new TsxLexer(CharStreams.fromString(source));
        lexer.setJsxEnabled(jsx);
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        TsxParser parser = new TsxParser(tokens);
        parser.removeErrorListeners();
        parser.getInterpreter().setPredictionMode(mode);
        parser.setErrorHandler(bail ? new BailErrorStrategy() : new DefaultErrorStrategy());
        if (!bail) {
            parser.addErrorListener(listener);
        }
        TsxParser.ProgramContext program = parser.program();
        return new Result(program, tokens, errors);
    }
}

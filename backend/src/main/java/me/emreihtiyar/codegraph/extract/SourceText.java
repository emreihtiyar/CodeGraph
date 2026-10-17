package me.emreihtiyar.codegraph.extract;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Analiz edilen repolardaki metin dosyalarının (kaynak kod, tsconfig, application.yml) okunması. Windows'ta yazılmış
 * dosyalarda sık görülen iki durum ele alınır:
 * <ul>
 *   <li>BOM (Visual Studio, Notepad): atılır; UTF-16 BOM'u varsa ona göre çözülür;</li>
 *   <li>UTF-8 olmayan dosyalar (Türkçe Windows'ta ANSI = windows-1254): geçersiz UTF-8'de windows-1254'e düşülür.</li>
 * </ul>
 * Satır sonları olduğu gibi bırakılır; {@link String#lines()} ve ayrıştırıcılar CRLF'yi zaten tanıyor.
 */
public final class SourceText {

    /** UTF-8 olarak çözülemeyen dosyalar için yedek kodlama. */
    public static final Charset FALLBACK = Charset.forName("windows-1254");

    private SourceText() {
    }

    public static String read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    static String decode(byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, FALLBACK);
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}

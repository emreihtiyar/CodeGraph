package me.emreihtiyar.codegraph.extract.java;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaParser birçok yerde locale'siz {@code toLowerCase()} kullanır ({@code PrimitiveType.Primitive},
 * {@code ResolvedPrimitiveType}, symbol solver). Türkçe/Azerice locale'de {@code "INT".toLowerCase()} {@code "ınt"}
 * olduğundan {@code int} tipi tanınmaz ve symbol solver {@code Name ınt} hatası verir. Bu yüzden JVM varsayılan
 * locale'i, JavaParser sınıfları yüklenmeden önce {@link Locale#ROOT}'a çekilir. CodeGraph'ın kendi metinleri
 * locale'den bağımsızdır.
 */
public final class ParserLocale {

    private static final Logger log = LoggerFactory.getLogger(ParserLocale.class);

    private ParserLocale() {}

    /** Varsayılan locale büyük/küçük harf dönüşümünü bozuyorsa {@link Locale#ROOT} yapar. Tekrar çağrılabilir. */
    public static synchronized void ensureSafeDefault() {
        Locale current = Locale.getDefault();
        if (!"I".toLowerCase().equals("i") || !"i".toUpperCase().equals("I")) {
            Locale.setDefault(Locale.ROOT);
            log.info("JVM varsayılan locale'i {} → ROOT yapıldı (JavaParser büyük/küçük harf uyumluluğu)", current);
        }
    }
}

package me.emreihtiyar.codegraph.config;

import java.util.Arrays;
import java.util.Locale;

/** Bir reponun mimarideki katmanı (PRD FR-01). */
public enum Layer {
    FRONTEND,
    BFF,
    API,
    ADAPTER,
    LIBRARY;

    /** Büyük-küçük harf duyarsız; geçersiz değerde geçerli değerleri listeleyen {@link IllegalArgumentException}. */
    public static Layer parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Geçersiz katman: " + value + " (geçerli: " + Arrays.toString(values()) + ")", e);
        }
    }

    public boolean isJava() {
        return this != FRONTEND;
    }
}

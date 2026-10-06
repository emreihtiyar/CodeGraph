package me.emreihtiyar.codegraph.query;

/** İstenen düğüm, servis, tablo vb. grafta yok. REST'te 404, MCP'de hata sonucu. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}

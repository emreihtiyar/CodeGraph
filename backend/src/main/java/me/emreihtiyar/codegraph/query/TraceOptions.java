package me.emreihtiyar.codegraph.query;

/**
 * İz seçenekleri (FR-16, FR-17).
 *
 * @param maxDepth         en fazla kaç kenar ilerlenir (1–100). Ekrandan tabloya bir iz, metot çağrıları dahil
 *                         genellikle 15–30 kenardır
 * @param includeAccessors getter/setter düğümleri dahil edilsin mi
 * @param prune            ileri izde yalnızca anlamlı uçlara (tablo, entity, endpoint, ApiCall, Feign/Repository
 *                         metodu) giden dallar tutulsun mu; ara metotların dış çağrıya/DB'ye gitmeyen dalları atılır.
 *                         Geri izde etkisizdir (zamanlanmış işler gibi endpoint'e bağlı olmayan çağıranlar da önemlidir)
 * @param maxNodes         düğüm sınırı; aşılırsa sonuç kesilir ve belirtilir
 */
public record TraceOptions(int maxDepth, boolean includeAccessors, boolean prune, int maxNodes) {

    public static final int DEFAULT_DEPTH = 40;
    public static final int DEFAULT_MAX_NODES = 2000;

    public TraceOptions {
        if (maxDepth < 1 || maxDepth > 100) {
            throw new IllegalArgumentException("maxDepth 1 ile 100 arasında olmalı");
        }
        if (maxNodes < 1 || maxNodes > 20000) {
            throw new IllegalArgumentException("maxNodes 1 ile 20000 arasında olmalı");
        }
    }

    public static TraceOptions defaults() {
        return new TraceOptions(DEFAULT_DEPTH, false, true, DEFAULT_MAX_NODES);
    }
}

package me.emreihtiyar.codegraph.query;

import java.util.List;

/**
 * Metot çağrı ağacı seçenekleri (FR-22, FR-23).
 *
 * @param depth            kaç seviye açılacağı (1–10); ağaç tembel yüklenir, alt seviyeler ayrı çağrıyla açılır
 * @param includeAccessors getter/setter ve basit erişim metotları gösterilsin mi (varsayılan gizli)
 * @param onlyExternal     yalnızca dış çağrıya (Feign) ya da DB'ye giden dallar
 * @param crossService     Feign düğümünden hedef servisin endpoint'ine ve handler'ına geçilsin mi
 * @param hidden           gizlenecek sınıf/paket önekleri (ör. {@code com.x.util}, {@code com.x.Mapper})
 * @param ancestors        tembel yüklemede kökün üstündeki metot id'leri; özyineleme tespiti için
 */
public record CallTreeOptions(
        int depth,
        boolean includeAccessors,
        boolean onlyExternal,
        boolean crossService,
        List<String> hidden,
        List<String> ancestors) {

    public CallTreeOptions {
        if (depth < 1 || depth > 10) {
            throw new IllegalArgumentException("depth 1 ile 10 arasında olmalı");
        }
        hidden = hidden == null ? List.of() : List.copyOf(hidden);
        ancestors = ancestors == null ? List.of() : List.copyOf(ancestors);
    }

    public static CallTreeOptions defaults() {
        return new CallTreeOptions(1, false, false, true, List.of(), List.of());
    }
}

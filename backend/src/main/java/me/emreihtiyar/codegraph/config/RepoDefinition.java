package me.emreihtiyar.codegraph.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Analiz edilecek tek bir repo (PRD FR-01).
 *
 * @param name          yapılandırma içinde benzersiz repo adı
 * @param path          reponun mutlak, normalize edilmiş yerel yolu
 * @param layer         mimari katman
 * @param serviceName   opsiyonel; verilmezse {@code spring.application.name} (frontend için repo adı) kullanılır
 * @param gatewayPrefix opsiyonel; yalnızca BFF'ler için anlamlı. Gateway sonrası ilk path segmenti
 *                      (baştaki/sondaki '/' olmadan). Verilmezse servis adıyla aynı kabul edilir.
 * @param aliases       Feign {@code name/value} değerlerinin bu servise eşlenmesi için ek isimler
 */
public record RepoDefinition(
        String name, Path path, Layer layer, String serviceName, String gatewayPrefix, List<String> aliases) {

    public RepoDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(layer, "layer");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}

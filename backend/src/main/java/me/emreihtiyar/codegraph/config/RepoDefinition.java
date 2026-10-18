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
 * @param feignName     opsiyonel; diğer servislerin {@code @FeignClient(name/value)} ile bu servisi çağırdığı ad.
 *                      Verilirse Feign hedefi önce bununla eşlenir ve başka bir reponun adıyla çakışsa da kazanır;
 *                      verilmezse servis adı ve repo adı kullanılır
 * @param aliases       Feign {@code name/value} değerlerinin bu servise eşlenmesi için ek isimler
 */
public record RepoDefinition(String name, Path path, Layer layer, String serviceName, String gatewayPrefix,
        String feignName, List<String> aliases) {

    public RepoDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(layer, "layer");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}

package me.emreihtiyar.codegraph.query;

import java.util.List;

/** Servisler ve aralarındaki {@code DEPENDS_ON} kenarları (sistem görünümü, FR-15). */
public record ServiceList(List<Service> services, List<Dependency> dependencies) {

    /**
     * @param analyzed false ise servis analizde yok, yalnızca bir Feign hedefi olarak biliniyor
     */
    public record Service(
            String id,
            String name,
            String layer,
            String repo,
            String contextPath,
            String gatewayPrefix,
            boolean analyzed,
            long endpointCount,
            long screenCount) {}

    public record Dependency(String from, String to, long callCount) {}
}

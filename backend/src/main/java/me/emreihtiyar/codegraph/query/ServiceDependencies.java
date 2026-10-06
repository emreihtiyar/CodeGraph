package me.emreihtiyar.codegraph.query;

import java.util.List;

/** Bir servisin çağırdığı ve onu çağıran servisler, somut bağlarla (ApiCall/Feign metodu → endpoint). */
public record ServiceDependencies(ServiceList.Service service, List<Dependency> outgoing, List<Dependency> incoming) {

    public record Dependency(String service, String layer, boolean analyzed, long callCount, List<Link> links) {}

    /**
     * @param sourceKind {@code ApiCall} ya da {@code FeignMethod}
     * @param target     hedef endpoint ({@code GET /path}); çözülemediyse null
     */
    public record Link(
            String sourceId,
            String source,
            String sourceKind,
            String sourceService,
            String targetId,
            String target,
            String resolution,
            String reason) {}
}

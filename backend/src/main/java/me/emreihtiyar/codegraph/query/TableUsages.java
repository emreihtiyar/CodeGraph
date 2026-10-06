package me.emreihtiyar.codegraph.query;

import java.util.List;

/** Bir tabloya erişen repository metotları ve onları çağıran metotlar, READ/WRITE ayrımıyla. */
public record TableUsages(List<NodeSummary> tables, List<NodeSummary> entities, List<Usage> usages) {

    /** @param caller repository metodunu çağıran metot ({@code Sinif.metot}); çağıranı yoksa null */
    public record Usage(
            String table,
            String entity,
            String repositoryMethodId,
            String repositoryMethod,
            String access,
            String query,
            String callerId,
            String caller,
            String service,
            Integer line) {}
}

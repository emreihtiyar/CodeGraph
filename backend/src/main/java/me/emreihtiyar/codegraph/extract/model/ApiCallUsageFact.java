package me.emreihtiyar.codegraph.extract.model;

/** @param hook {@code useQuery}, {@code useMutation}, {@code useQueries}; doğrudan çağrıda null */
public record ApiCallUsageFact(
        String componentKey, String apiCallKey, int order, int line, ApiCallVia via, String hook) {}

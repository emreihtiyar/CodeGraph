package me.emreihtiyar.codegraph.extract.model;

/**
 * Component'in ApiCall'ı nasıl kullandığı: {@code REACT_QUERY} bir React Query hook'unun ({@code useQuery},
 * {@code useMutation}...) içinden, {@code DIRECT} doğrudan çağrı (ör. bir olay işleyicisinde {@code await getX()}).
 */
public enum ApiCallVia {
    REACT_QUERY,
    DIRECT
}

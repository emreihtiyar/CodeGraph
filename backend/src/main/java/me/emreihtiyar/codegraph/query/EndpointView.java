package me.emreihtiyar.codegraph.query;

/** @param handler karşılayan controller metodu ({@code Sinif.metot}) */
public record EndpointView(
        String id, String service, String httpMethod, String path, String handlerId, String handler) {}

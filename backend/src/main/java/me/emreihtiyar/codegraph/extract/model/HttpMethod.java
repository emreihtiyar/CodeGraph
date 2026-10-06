package me.emreihtiyar.codegraph.extract.model;

/** {@code ANY}: metot belirtilmemiş {@code @RequestMapping}; her HTTP metoduyla eşleşir. */
public enum HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH,
    HEAD,
    OPTIONS,
    ANY
}

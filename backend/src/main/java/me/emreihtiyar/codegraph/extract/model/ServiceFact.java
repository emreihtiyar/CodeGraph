package me.emreihtiyar.codegraph.extract.model;

import me.emreihtiyar.codegraph.config.Layer;

/**
 * @param name          {@code spring.application.name}; frontend için yapılandırmadaki servis/repo adı
 * @param contextPath   {@code server.servlet.context-path}; yoksa boş metin
 * @param gatewayPrefix yalnızca BFF'ler için; gateway sonrası ilk path segmenti
 */
public record ServiceFact(String name, Layer layer, String contextPath, String gatewayPrefix) {}

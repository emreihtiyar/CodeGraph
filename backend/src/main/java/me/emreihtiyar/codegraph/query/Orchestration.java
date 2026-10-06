package me.emreihtiyar.codegraph.query;

import java.util.List;

/**
 * Bir endpoint'in sıralı dış çağrıları ve DB erişimleri (Hikaye 2): handler metodundan başlayıp çalışma sırasıyla.
 *
 * @param truncated derinlik sınırına ulaşıldı
 */
public record Orchestration(NodeSummary endpoint, NodeSummary handler, List<Step> steps, boolean truncated) {

    /**
     * @param path        hiyerarşik sıra, ör. {@code 2.1}: handler'ın 2. çağrısının içindeki 1. çağrı
     * @param kind        {@code FEIGN} ya da {@code REPOSITORY}
     * @param via         handler'dan bu çağrıya kadar geçilen metotlar
     * @param conditional bu çağrı ya da yolu üzerindeki bir çağrı koşullu
     * @param inLoop      bu çağrı ya da yolu üzerindeki bir çağrı döngüde
     * @param async       bu çağrı ya da yolu üzerindeki bir çağrı asenkron
     * @param targets     Feign için hedef endpoint'ler ({@code servis GET /path}), repository için tablolar
     * @param access      repository için {@code READ}/{@code WRITE}
     */
    public record Step(
            String path,
            String kind,
            String nodeId,
            String name,
            String callerId,
            int line,
            List<String> via,
            boolean conditional,
            boolean inLoop,
            boolean async,
            String resolution,
            String targetService,
            List<String> targets,
            String access) {}
}

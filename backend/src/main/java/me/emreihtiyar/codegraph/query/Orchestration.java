package me.emreihtiyar.codegraph.query;

import java.util.List;

/**
 * Bir endpoint'in sıralı dış çağrıları ve DB erişimleri (Hikaye 2): handler metodundan başlayıp çalışma sırasıyla.
 *
 * @param truncated derinlik sınırına ulaşıldı
 */
public record Orchestration(NodeSummary endpoint, NodeSummary handler, List<Step> steps, boolean truncated) {

    /**
     * @param order       kod sırasına göre 1'den başlayan adım numarası (yalnızca Feign/DB adımları sayılır)
     * @param kind        {@code FEIGN} ya da {@code REPOSITORY}
     * @param via         handler'dan bu çağrıya kadar geçilen metotlar
     * @param conditional bu çağrı ya da yolu üzerindeki bir çağrı koşullu
     * @param inLoop      bu çağrı ya da yolu üzerindeki bir çağrı döngüde
     * @param async       bu çağrı ya da yolu üzerindeki bir çağrı asenkron
     * @param targets     Feign için hedef endpoint'ler ({@code servis GET /path}), repository için tablolar
     * @param access      repository için {@code READ}/{@code WRITE}
     */
    public record Step(
            int order,
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

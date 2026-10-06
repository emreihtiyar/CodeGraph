package me.emreihtiyar.codegraph.graph;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graf veritabanı erişim katmanı (PRD NFR-08). Embedded Neo4j'den Neo4j sunucusuna geçiş yalnızca bu arayüzün
 * implementasyonu değiştirilerek yapılır; üst katmanlar Neo4j tiplerini görmez.
 */
public interface GraphStore {

    /** {@link GraphSchema} kısıt ve indekslerini oluşturur ve çevrimiçi olmalarını bekler. İdempotenttir. */
    void ensureSchema();

    /**
     * Verilen etiketler dışındaki tüm düğümleri ve ilişkilerini siler. Tam yeniden üretim (FR-10) öncesinde
     * örn. {@code AnalysisRun} geçmişini korumak için kullanılır.
     *
     * @return silinen düğüm sayısı
     */
    long clearExcept(Set<NodeLabel> preserved);

    /**
     * Düğümleri id üzerinden birleştirerek (MERGE), ardından ilişkileri oluşturarak (CREATE) yazar. Düğümler
     * ilişkilerden önce yazılır; aynı id'li düğümün özellikleri birleştirilir.
     */
    WriteResult write(GraphBatch batch);

    /**
     * Salt-okunur Cypher sorgusu (NFR-04). Yazma içeren sorgular reddedilir ve hiçbir değişiklik kalıcı olmaz.
     * Değerler {@link GraphNode}, {@link GraphRelationship}, {@link GraphPath}, liste, map veya skalerdir.
     *
     * @throws IllegalArgumentException sorgu salt-okunur değilse
     */
    List<Map<String, Object>> query(String cypher, Map<String, Object> parameters);
}

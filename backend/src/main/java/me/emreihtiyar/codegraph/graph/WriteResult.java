package me.emreihtiyar.codegraph.graph;

import java.util.List;

/**
 * @param danglingRelationships uçlarından biri grafta bulunamadığı için yazılamayan ilişkiler; Load aşamasında
 *                              bir hataya işaret eder ve raporlanmalıdır
 */
public record WriteResult(int nodesWritten, int relationshipsWritten, List<RelRecord> danglingRelationships) {

    public WriteResult {
        danglingRelationships = List.copyOf(danglingRelationships);
    }
}

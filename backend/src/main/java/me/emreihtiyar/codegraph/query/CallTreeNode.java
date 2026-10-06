package me.emreihtiyar.codegraph.query;

import java.util.List;
import java.util.Map;

/**
 * Çağrı ağacının bir satırı (FR-22).
 *
 * @param kind        {@code METHOD}, {@code FEIGN_METHOD}, {@code REPOSITORY_METHOD} ya da {@code ENDPOINT}
 *                    (servisler arası geçişte Feign'in hedefi)
 * @param call        bu satıra gelen çağrı kenarının özellikleri (order, line, conditional, conditionText, inLoop,
 *                    async, viaLambda, recursive, resolution); kökte null
 * @param recursive   düğüm üst satırlardan birinde zaten var; açılmaz
 * @param hasChildren açılabilir alt satırı var
 * @param children    açılmış alt satırlar; açılmadıysa null
 * @param details     türe özgü bilgi: Feign için targetService/httpMethod/path/resolution, repository için
 *                    accessType/tables, endpoint için httpMethod/path
 */
public record CallTreeNode(
        String nodeId,
        String kind,
        String name,
        String service,
        String visibility,
        boolean isStatic,
        String file,
        Integer startLine,
        Integer endLine,
        Map<String, Object> call,
        boolean recursive,
        boolean hasChildren,
        List<CallTreeNode> children,
        Map<String, Object> details) {}

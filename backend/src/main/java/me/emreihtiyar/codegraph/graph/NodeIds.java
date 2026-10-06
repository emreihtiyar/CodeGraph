package me.emreihtiyar.codegraph.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Doğal anahtardan deterministik düğüm id'si üretir. Her analiz grafı sıfırdan ürettiği için Neo4j'nin iç
 * id'leri kararlı değildir; REST/MCP'de dışarı verilen id'ler analizler arasında aynı kalmalıdır.
 *
 * <p>Biçim: {@code <önek>_<16 hex>}, ör. {@code method_3f9a1c0be2d47a55}. URL path segmentinde kaçışsız kullanılabilir.
 */
public final class NodeIds {

    private static final HexFormat HEX = HexFormat.of();

    private NodeIds() {}

    /**
     * @param naturalKey etiket içinde benzersiz anahtar. Repo'ya ait düğümlerde repo adını içermelidir, ör.
     *                   {@code credit-api|com.x.CreditService#check(java.lang.String)}
     */
    public static String of(NodeLabel label, String naturalKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(label.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] hash = digest.digest(naturalKey.getBytes(StandardCharsets.UTF_8));
            return label.idPrefix() + "_" + HEX.formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 desteklenmiyor", e);
        }
    }

    /** Birden çok parçadan doğal anahtar: parçalar {@code |} ile birleştirilir. */
    public static String key(String... parts) {
        return String.join("|", parts);
    }
}

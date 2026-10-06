package me.emreihtiyar.codegraph.extract.model;

/**
 * Projedeki her Java metodu, görünürlükten bağımsız (PRD FR-04).
 *
 * @param fqn       {@code paket.Sinif#ad(ParamTip1,ParamTip2)}; overload'ları ayırt eder
 * @param signature okunabilir imza, ör. {@code BigDecimal calc(String, int)}
 * @param isAccessor getter/setter ve basit erişim metotları (PRD FR-23 filtresi)
 * @param synthetic  kaynakta olmayan, ör. Lombok ile üretilen metot; {@code source} ve {@code code} null olabilir
 */
public record MethodFact(
        String fqn,
        String classFqn,
        String name,
        String signature,
        Visibility visibility,
        boolean isStatic,
        boolean isAbstract,
        boolean isAccessor,
        boolean synthetic,
        SourceRef source,
        String code) {}

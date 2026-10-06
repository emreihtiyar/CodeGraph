package me.emreihtiyar.codegraph.extract.java;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Çağrı çözümlemesi için proje tiplerinin hafif modeli: hiyerarşi, metotlar (Lombok/record accessor'ları dahil),
 * dönüş ve alan tipleri. LIBRARY repolarının modelleri sonraki repolara aktarılır; böylece ortak kütüphanedeki
 * Feign arayüzleri, repository'ler ve DTO'lar da çözülebilir.
 */
public final class TypeModel {

    public enum Kind {
        CLASS,
        ABSTRACT_CLASS,
        INTERFACE,
        ENUM,
        RECORD,
        FEIGN_CLIENT,
        REPOSITORY
    }

    /**
     * @param paramCount     varargs'ta son parametre dahil sayı; -1: herhangi bir sayı (miras alınan repository metotları)
     * @param returnType     çözülebildiyse dönüş tipinin FQN'i (proje dışı tipler dahil), aksi halde basit adı
     * @param key            graftaki hedef anahtar: metot FQN'i, Feign metodu FQN'i veya repository metodu FQN'i
     */
    public record MethodInfo(
            String key,
            String declaringType,
            String name,
            String parameterTypes,
            int paramCount,
            boolean varArgs,
            String returnType,
            boolean isAbstract,
            boolean isStatic,
            boolean async) {

        public boolean accepts(int argCount) {
            if (paramCount < 0) {
                return true;
            }
            return varArgs ? argCount >= paramCount - 1 : argCount == paramCount;
        }

        /** Override eşleştirmesi için ad + parametre tipleri. */
        public String signatureKey() {
            return name + "(" + parameterTypes + ")";
        }
    }

    /**
     * @param superTypes      çözülebilen üst tip FQN'leri (proje dışı olanlar dahil)
     * @param hasExternalSuper projede bulunmayan bir üst tipi var mı (metot oradan miras alınmış olabilir)
     * @param fieldTypes      alan adı → tip FQN'i (record bileşenleri dahil)
     * @param beanName        Spring bean adı (bean değilse null)
     */
    public record TypeInfo(
            String repo,
            String fqn,
            Kind kind,
            List<String> superTypes,
            boolean hasExternalSuper,
            List<MethodInfo> methods,
            Map<String, String> fieldTypes,
            String beanName,
            boolean primary) {}

    private final Map<String, TypeInfo> types = new LinkedHashMap<>();
    private final Map<String, List<String>> subTypes = new HashMap<>();

    public void add(TypeInfo type) {
        types.putIfAbsent(type.fqn(), type);
        for (String parent : type.superTypes()) {
            subTypes.computeIfAbsent(parent, k -> new ArrayList<>()).add(type.fqn());
        }
    }

    public void addAll(TypeModel other) {
        other.types.values().forEach(this::add);
    }

    public Optional<TypeInfo> type(String fqn) {
        return Optional.ofNullable(fqn).map(types::get);
    }

    public boolean contains(String fqn) {
        return fqn != null && types.containsKey(fqn);
    }

    public Collection<TypeInfo> types() {
        return types.values();
    }

    /** Tipin kendisi ve proje içindeki tüm üst tipleri, yakından uzağa (genişlik öncelikli). */
    public List<TypeInfo> hierarchy(String fqn) {
        List<TypeInfo> result = new ArrayList<>();
        List<String> queue = new ArrayList<>(List.of(fqn));
        for (int i = 0; i < queue.size(); i++) {
            Optional<TypeInfo> type = type(queue.get(i));
            if (type.isPresent() && !result.contains(type.get())) {
                result.add(type.get());
                queue.addAll(type.get().superTypes());
            }
        }
        return result;
    }

    /** Tipi (dolaylı olarak) genişleten/uygulayan tüm proje tipleri. */
    public List<TypeInfo> allSubTypes(String fqn) {
        List<TypeInfo> result = new ArrayList<>();
        List<String> queue = new ArrayList<>(subTypes.getOrDefault(fqn, List.of()));
        for (int i = 0; i < queue.size(); i++) {
            Optional<TypeInfo> type = type(queue.get(i));
            if (type.isPresent() && !result.contains(type.get())) {
                result.add(type.get());
                queue.addAll(subTypes.getOrDefault(queue.get(i), List.of()));
            }
        }
        return result;
    }

    /** Hiyerarşide proje dışı bir üst tip var mı? Varsa bulunamayan metot oradan miras alınmış olabilir. */
    public boolean hasExternalAncestor(String fqn) {
        return hierarchy(fqn).stream().anyMatch(TypeInfo::hasExternalSuper);
    }

    /** Hiyerarşideki ada göre metotlar; alt tipte tanımlı olan üsttekini gölgeler. */
    public List<MethodInfo> findMethods(String typeFqn, String name) {
        List<MethodInfo> result = new ArrayList<>();
        List<String> seenSignatures = new ArrayList<>();
        for (TypeInfo type : hierarchy(typeFqn)) {
            for (MethodInfo m : type.methods()) {
                if (m.name().equals(name) && !seenSignatures.contains(m.signatureKey())) {
                    seenSignatures.add(m.signatureKey());
                    result.add(m);
                }
            }
        }
        return result;
    }

    /** Bir alanın tipi; tipin kendisinde yoksa proje üst tiplerinde aranır. */
    public Optional<String> fieldType(String typeFqn, String field) {
        for (TypeInfo type : hierarchy(typeFqn)) {
            String fieldType = type.fieldTypes().get(field);
            if (fieldType != null) {
                return Optional.of(fieldType);
            }
        }
        return Optional.empty();
    }
}

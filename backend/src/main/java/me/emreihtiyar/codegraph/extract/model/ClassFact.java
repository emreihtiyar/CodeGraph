package me.emreihtiyar.codegraph.extract.model;

import java.util.List;

/**
 * @param superClass  çözülebildiyse üst sınıfın FQN'i
 * @param interfaces  çözülebilen arayüz FQN'leri
 */
public record ClassFact(
        String fqn,
        ClassKind kind,
        boolean isInterface,
        boolean isAbstract,
        String superClass,
        List<String> interfaces,
        SourceRef source) {}

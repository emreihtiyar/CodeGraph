package me.emreihtiyar.codegraph.extract.model;

/** Alt sınıf/implementasyon metodu → arayüz/üst sınıf metodu (PRD §8.6 OVERRIDES). */
public record OverrideFact(String methodFqn, String overriddenFqn) {}

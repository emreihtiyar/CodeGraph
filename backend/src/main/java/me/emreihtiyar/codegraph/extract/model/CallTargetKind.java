package me.emreihtiyar.codegraph.extract.model;

/** Çağrının hedef tipi; grafta sırasıyla {@code CALLS}, {@code INVOKES}, {@code USES_REPOSITORY} kenarına dönüşür. */
public enum CallTargetKind {
    METHOD,
    FEIGN_METHOD,
    REPOSITORY_METHOD
}

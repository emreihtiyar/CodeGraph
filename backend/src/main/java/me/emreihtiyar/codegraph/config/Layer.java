package me.emreihtiyar.codegraph.config;

/** Bir reponun mimarideki katmanı (PRD FR-01). */
public enum Layer {
    FRONTEND,
    BFF,
    API,
    ADAPTER,
    LIBRARY;

    public boolean isJava() {
        return this != FRONTEND;
    }
}

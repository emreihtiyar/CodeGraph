package me.emreihtiyar.codegraph.extract.model;

/** @param key repo içinde benzersiz anahtar: {@code dosya#ad} */
public record ComponentFact(String key, String name, SourceRef source, String code) {}

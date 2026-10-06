package me.emreihtiyar.codegraph.extract.model;

/** Analizi durdurmayan, raporlanan sorun (PRD FR-12, NFR-06). {@code line} bilinmiyorsa 0. */
public record ParseIssue(String file, int line, String message) {}

package me.emreihtiyar.codegraph.extract.model;

/**
 * Bir kod bloğunun konumu (PRD FR-11).
 *
 * @param file      repo köküne göreli yol, '/' ayraçlı
 * @param startLine 1 tabanlı, dahil
 * @param endLine   1 tabanlı, dahil
 */
public record SourceRef(String file, int startLine, int endLine) {}

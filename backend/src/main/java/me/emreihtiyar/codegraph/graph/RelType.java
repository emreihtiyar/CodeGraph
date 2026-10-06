package me.emreihtiyar.codegraph.graph;

/** Graf ilişki tipleri (PRD §8.6). */
public enum RelType {
    CONTAINS,
    HAS_SCREEN,
    RENDERS,
    USES_API_CALL,
    REQUESTS,
    EXPOSES,
    HANDLED_BY,
    DECLARES,
    CALLS,
    OVERRIDES,
    INVOKES,
    TARGETS,
    RESOLVES_TO,
    USES_REPOSITORY,
    READS,
    WRITES,
    MAPPED_TO,
    RELATES_TO,
    DEPENDS_ON
}

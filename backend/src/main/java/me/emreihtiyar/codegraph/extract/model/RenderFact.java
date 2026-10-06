package me.emreihtiyar.codegraph.extract.model;

/** Component → alt component (PRD FR-08 RENDERS). */
public record RenderFact(String parentComponentKey, String childComponentKey) {}

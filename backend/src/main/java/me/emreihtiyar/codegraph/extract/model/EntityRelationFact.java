package me.emreihtiyar.codegraph.extract.model;

public record EntityRelationFact(String fromEntityFqn, String toEntityFqn, EntityRelationType type, String field) {}

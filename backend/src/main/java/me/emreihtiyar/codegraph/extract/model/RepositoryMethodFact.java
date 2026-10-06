package me.emreihtiyar.codegraph.extract.model;

/**
 * @param query     {@code @Query} değeri, yoksa null
 * @param inherited {@code save}, {@code findById} gibi Spring Data'dan miras alınan metot
 */
public record RepositoryMethodFact(
        String fqn, String repositoryFqn, String name, AccessType accessType, String query, boolean inherited) {}

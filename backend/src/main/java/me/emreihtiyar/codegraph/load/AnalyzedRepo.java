package me.emreihtiyar.codegraph.load;

import me.emreihtiyar.codegraph.config.RepoDefinition;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;

/**
 * Load aşamasına giren tek repo.
 *
 * @param commitHash reponun {@code HEAD} commit'i; git reposu değilse null
 */
public record AnalyzedRepo(RepoDefinition definition, RepoFacts facts, String commitHash) {

    public String name() {
        return definition.name();
    }
}

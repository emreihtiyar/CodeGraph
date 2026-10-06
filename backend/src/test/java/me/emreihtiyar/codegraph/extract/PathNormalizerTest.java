package me.emreihtiyar.codegraph.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PathNormalizerTest {

    @Test
    void joinsPartsWithSingleSlashes() {
        assertThat(PathNormalizer.join("/credit-api/", "", null, "/v1/credits", "limit-checks/"))
                .isEqualTo("/credit-api/v1/credits/limit-checks");
        assertThat(PathNormalizer.join()).isEqualTo("/");
    }

    @Test
    void cleanDropsQueryAndFragmentOutsideVariables() {
        assertThat(PathNormalizer.clean("a//b/?x=1")).isEqualTo("/a/b");
        assertThat(PathNormalizer.clean("/a/{id:\\d{3}}#top")).isEqualTo("/a/{id:\\d{3}}");
    }

    @Test
    void normalizesAllVariableStyles() {
        assertThat(PathNormalizer.normalize("/customers/{customerId}/offers")).isEqualTo("/customers/{*}/offers");
        assertThat(PathNormalizer.normalize("/items/{id:\\d+}")).isEqualTo("/items/{*}");
        assertThat(PathNormalizer.normalize("/customers/:customerId")).isEqualTo("/customers/{*}");
        assertThat(PathNormalizer.normalize("/scores/${customer.id}/x")).isEqualTo("/scores/{*}/x");
        assertThat(PathNormalizer.normalize("/time/12:30")).isEqualTo("/time/12:30");
    }

    @Test
    void matchesSegmentWiseWithWildcards() {
        assertThat(PathNormalizer.matches("/a/{*}/c", "/a/b/c")).isTrue();
        assertThat(PathNormalizer.matches("/a/{*}", "/a/b/c")).isFalse();
        assertThat(PathNormalizer.matches("/a/b", "/a/c")).isFalse();
    }
}

package me.emreihtiyar.codegraph.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import org.junit.jupiter.api.Test;

class IssueSummaryTest {

    @Test
    void groupsByKindMostFrequentFirst() {
        List<String> lines = IssueSummary.of(List.of(
                new ParseIssue("a.ts", 3, "Sözdizimi hatası: 3:1 extraneous input"),
                new ParseIssue("ApiCalls.ts", 5, "ApiCall 'getA': callApi çağrısında 'endpoint' bulunamadı"),
                new ParseIssue("ApiCalls.ts", 9, "ApiCall 'getB': callApi çağrısında 'endpoint' bulunamadı")));

        assertThat(lines).containsExactly(
                "2× ApiCall '…': callApi çağrısında '…' bulunamadı (ör. ApiCalls.ts:5 ApiCall 'getA': callApi "
                        + "çağrısında 'endpoint' bulunamadı)",
                "1× Sözdizimi hatası (ör. a.ts:3 Sözdizimi hatası: 3:1 extraneous input)");
    }
}

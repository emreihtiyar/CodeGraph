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
                new ParseIssue("xApiCalls.ts", 5, "ApiCall adayı 'getA' içinde HTTP çağrısı bulunamadı; gövdedeki çağrılar: f()"),
                new ParseIssue("xApiCalls.ts", 9, "ApiCall adayı 'getB' içinde HTTP çağrısı bulunamadı; gövdedeki çağrılar: g()")));

        assertThat(lines).containsExactly(
                "2× ApiCall adayı '…' içinde HTTP çağrısı bulunamadı (ör. xApiCalls.ts:5 ApiCall adayı 'getA' içinde "
                        + "HTTP çağrısı bulunamadı; gövdedeki çağrılar: f())",
                "1× Sözdizimi hatası (ör. a.ts:3 Sözdizimi hatası: 3:1 extraneous input)");
    }
}

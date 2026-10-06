package com.bank.scoring.service;

import com.bank.scoring.client.ExposureClient;
import com.bank.scoring.client.LegacyScoreClient;
import com.bank.scoring.client.SalesClient;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ScoreService {

    private final ExposureClient exposureClient;
    private final SalesClient salesClient;
    private final LegacyScoreClient legacyScoreClient;
    /** İki implementasyonu var: çağrı AMBIGUOUS olmalı. */
    private final RiskCalculator riskCalculator;

    public ScoreService(
            ExposureClient exposureClient,
            SalesClient salesClient,
            LegacyScoreClient legacyScoreClient,
            RiskCalculator riskCalculator) {
        this.exposureClient = exposureClient;
        this.salesClient = salesClient;
        this.legacyScoreClient = legacyScoreClient;
        this.riskCalculator = riskCalculator;
    }

    public int score(Long customerId) {
        BigDecimal exposure = exposureClient.getExposure(customerId);
        return riskCalculator.calculate(exposure);
    }

    public ScoreSummary summary(Long customerId) {
        int score = score(customerId);
        List<Object> offers = salesClient.listOffers(customerId);
        int legacy = legacyScoreClient.getLegacyScore(customerId);
        return new ScoreSummary(score, legacy, offers.size());
    }
}

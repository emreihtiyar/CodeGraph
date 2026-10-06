package com.bank.scoring.service;

import java.math.BigDecimal;

/** Miras alınan metot çağrısı örneği: alt sınıflar {@link #normalize} çağırır. */
public abstract class BaseRiskCalculator implements RiskCalculator {

    protected int normalize(BigDecimal value) {
        return Math.min(1000, value.intValue());
    }
}

package com.bank.scoring.service;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class DefaultRiskCalculator extends BaseRiskCalculator {

    @Override
    public int calculate(BigDecimal exposure) {
        return 1000 - normalize(exposure);
    }
}

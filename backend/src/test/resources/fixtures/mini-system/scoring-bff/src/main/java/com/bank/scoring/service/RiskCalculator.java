package com.bank.scoring.service;

import java.math.BigDecimal;

public interface RiskCalculator {

    int calculate(BigDecimal exposure);
}

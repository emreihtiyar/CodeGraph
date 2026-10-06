package com.bank.credit.service;

import java.math.BigDecimal;

public record LimitCheckResult(boolean approved, BigDecimal available) {

    public static LimitCheckResult rejected() {
        return new LimitCheckResult(false, BigDecimal.ZERO);
    }
}

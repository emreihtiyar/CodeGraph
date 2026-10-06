package com.bank.leasing.dto;

import java.math.BigDecimal;

public record LimitCheckResult(boolean approved, BigDecimal available) {}

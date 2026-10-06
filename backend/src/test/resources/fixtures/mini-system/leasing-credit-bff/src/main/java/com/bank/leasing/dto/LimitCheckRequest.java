package com.bank.leasing.dto;

import java.math.BigDecimal;

public record LimitCheckRequest(Long customerId, String customerNo, BigDecimal amount) {}

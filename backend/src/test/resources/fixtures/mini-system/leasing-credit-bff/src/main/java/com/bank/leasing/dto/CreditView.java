package com.bank.leasing.dto;

import java.math.BigDecimal;

public record CreditView(Long id, Long customerId, BigDecimal amount, String status) {}

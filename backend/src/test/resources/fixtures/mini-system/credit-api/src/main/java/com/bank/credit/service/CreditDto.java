package com.bank.credit.service;

import java.math.BigDecimal;

public record CreditDto(Long id, Long customerId, BigDecimal amount, String status) {}

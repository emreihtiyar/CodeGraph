package com.bank.sales.service;

import java.math.BigDecimal;

public record OfferRequest(Long customerId, String customerNo, Long dealerId, BigDecimal amount) {}

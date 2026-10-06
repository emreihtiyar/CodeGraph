package com.bank.sales.service;

import java.math.BigDecimal;
import java.util.UUID;

public record OfferDto(UUID id, Long customerId, BigDecimal amount) {}

package com.bank.leasing.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OfferView(UUID id, Long customerId, BigDecimal amount) {}

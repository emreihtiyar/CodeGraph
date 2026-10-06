package com.bank.leasing.dto;

import java.util.List;

public record ApplicationView(CreditView credit, List<OfferView> offers) {}

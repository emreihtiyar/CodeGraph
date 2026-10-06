package com.bank.credit.service;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class LimitCheckRequest {

    private Long customerId;
    private String customerNo;
    private BigDecimal amount;
}

package com.bank.leasing.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class CreditApplicationRequest {

    private Long customerId;
    private String customerNo;
    private BigDecimal amount;
}

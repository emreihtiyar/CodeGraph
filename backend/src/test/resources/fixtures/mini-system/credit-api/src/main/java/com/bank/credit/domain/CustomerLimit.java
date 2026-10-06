package com.bank.credit.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Entity
@Table(name = "customer_limit")
public class CustomerLimit {

    @Id
    private Long id;

    private Long customerId;

    private BigDecimal available;
}

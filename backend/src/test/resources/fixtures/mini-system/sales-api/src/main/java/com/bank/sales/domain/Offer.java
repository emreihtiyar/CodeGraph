package com.bank.sales.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "offers")
public class Offer {

    @Id
    private UUID id;

    private Long customerId;

    private BigDecimal amount;

    @ManyToOne
    private Dealer dealer;

    protected Offer() {}

    public Offer(Long customerId, Dealer dealer, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.dealer = dealer;
        this.amount = amount;
    }

    public UUID getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}

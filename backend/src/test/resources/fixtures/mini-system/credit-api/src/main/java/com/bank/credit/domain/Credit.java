package com.bank.credit.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "CREDIT", schema = "LEASING")
public class Credit {

    @Id
    private Long id;

    private Long customerId;

    private BigDecimal amount;

    private String status;

    @OneToMany(mappedBy = "credit")
    private List<CreditInstallment> installments = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<CreditInstallment> getInstallments() {
        return installments;
    }
}

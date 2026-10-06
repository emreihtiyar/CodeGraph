package com.bank.credit.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** {@code @Table} yok: tablo adı varsayılan stratejiyle {@code credit_installment} olmalı. */
@Entity
@Getter
@Setter
public class CreditInstallment {

    @Id
    private Long id;

    private BigDecimal amount;

    @ManyToOne
    private Credit credit;
}

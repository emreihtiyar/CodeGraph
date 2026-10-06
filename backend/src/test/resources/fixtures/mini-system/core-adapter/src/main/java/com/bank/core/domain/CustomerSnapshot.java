package com.bank.core.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "CUSTOMER_SNAPSHOT", schema = "CORE")
public class CustomerSnapshot {

    @Id
    private Long id;

    private String customerNo;

    private String fullName;

    private boolean blacklisted;
}

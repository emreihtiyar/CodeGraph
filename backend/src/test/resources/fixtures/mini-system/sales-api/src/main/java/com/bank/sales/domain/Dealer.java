package com.bank.sales.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import java.util.Set;

/** {@code @Table} yok: tablo adı {@code dealer}. */
@Entity
public class Dealer {

    @Id
    private Long id;

    private String name;

    @ManyToMany
    private Set<Region> regions;
}

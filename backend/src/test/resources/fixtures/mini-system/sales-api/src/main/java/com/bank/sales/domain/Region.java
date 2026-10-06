package com.bank.sales.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Adı birden çok kelimeli değil ama sınıf adından türetilen tablo: {@code region}. */
@Entity
public class Region {

    @Id
    private Long id;

    private String code;
}

package com.bank.sales.repository;

import com.bank.sales.domain.Dealer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealerRepository extends JpaRepository<Dealer, Long> {}

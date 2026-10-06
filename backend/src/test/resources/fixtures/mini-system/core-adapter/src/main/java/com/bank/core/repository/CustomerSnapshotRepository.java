package com.bank.core.repository;

import com.bank.core.domain.CustomerSnapshot;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerSnapshotRepository extends JpaRepository<CustomerSnapshot, Long> {

    Optional<CustomerSnapshot> findByCustomerNo(String customerNo);
}

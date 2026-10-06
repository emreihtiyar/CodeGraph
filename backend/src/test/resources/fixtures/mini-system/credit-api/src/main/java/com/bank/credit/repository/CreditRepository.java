package com.bank.credit.repository;

import com.bank.credit.domain.Credit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditRepository extends JpaRepository<Credit, Long> {

    List<Credit> findByCustomerId(Long customerId);

    @Query("select c from Credit c where c.status = :status")
    List<Credit> findAllWithStatus(@Param("status") String status);

    @Modifying
    @Query("update Credit c set c.status = 'REJECTED' where c.customerId = :customerId")
    int markRejected(@Param("customerId") Long customerId);
}

package com.bank.credit.repository;

import com.bank.credit.domain.CustomerLimit;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface CustomerLimitRepository extends CrudRepository<CustomerLimit, Long> {

    Optional<CustomerLimit> findByCustomerId(Long customerId);

    boolean existsByCustomerId(Long customerId);
}

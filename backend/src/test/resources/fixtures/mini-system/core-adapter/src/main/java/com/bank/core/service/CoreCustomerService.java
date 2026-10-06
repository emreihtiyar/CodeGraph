package com.bank.core.service;

import com.bank.core.domain.CustomerSnapshot;
import com.bank.core.repository.CustomerSnapshotRepository;
import org.springframework.stereotype.Service;

@Service
public class CoreCustomerService {

    private final CustomerSnapshotRepository customerSnapshotRepository;

    public CoreCustomerService(CustomerSnapshotRepository customerSnapshotRepository) {
        this.customerSnapshotRepository = customerSnapshotRepository;
    }

    public CustomerDto findCustomer(String customerNo) {
        return customerSnapshotRepository.findByCustomerNo(customerNo)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Müşteri yok: " + customerNo));
    }

    private CustomerDto toDto(CustomerSnapshot snapshot) {
        return new CustomerDto(snapshot.getCustomerNo(), snapshot.getFullName(), snapshot.isBlacklisted());
    }
}

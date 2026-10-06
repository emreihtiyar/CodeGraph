package com.bank.sales.repository;

import com.bank.sales.domain.Offer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface OfferRepository extends CrudRepository<Offer, UUID> {

    List<Offer> findAllByCustomerId(Long customerId);
}

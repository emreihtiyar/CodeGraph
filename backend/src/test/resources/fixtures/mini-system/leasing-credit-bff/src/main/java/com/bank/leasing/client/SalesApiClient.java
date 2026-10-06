package com.bank.leasing.client;

import com.bank.leasing.dto.OfferView;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "sales-api")
public interface SalesApiClient {

    @PostMapping("/api/sales/offers")
    OfferView createOffer(@RequestParam("customerId") Long customerId, @RequestParam("amount") BigDecimal amount);

    @GetMapping("/api/sales/customers/{customerId}/offers")
    List<OfferView> listOffers(@PathVariable("customerId") Long customerId);
}

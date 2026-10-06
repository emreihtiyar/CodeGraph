package com.bank.scoring.client;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient("sales-api")
public interface SalesClient {

    @GetMapping("/api/sales/customers/{id}/offers")
    List<Object> listOffers(@PathVariable("id") Long id);
}

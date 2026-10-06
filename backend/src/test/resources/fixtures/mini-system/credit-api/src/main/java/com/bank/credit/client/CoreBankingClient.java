package com.bank.credit.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "core-adapter")
public interface CoreBankingClient {

    @GetMapping("/core/customers/{customerNo}")
    CoreCustomer getCustomer(@PathVariable("customerNo") String customerNo);
}

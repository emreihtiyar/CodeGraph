package com.bank.sales.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(value = "core-adapter", path = "/core")
public interface CoreClient {

    @GetMapping("/customers/{no}")
    Object getCustomer(@PathVariable("no") String no);
}

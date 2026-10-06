package com.bank.scoring.client;

import java.math.BigDecimal;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "credit-api", path = "/credit-api/v1/customers")
public interface ExposureClient {

    @GetMapping("/{customerId}/exposure")
    BigDecimal getExposure(@PathVariable("customerId") Long customerId);
}

package com.bank.credit.api;

import com.bank.credit.service.ExposureService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/v1/customers")
public class CustomerExposureController {

    private final ExposureService exposureService;

    public CustomerExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    @GetMapping(value = "/{customerId}/exposure")
    public BigDecimal getExposure(@PathVariable Long customerId) {
        return exposureService.calculate(customerId);
    }
}

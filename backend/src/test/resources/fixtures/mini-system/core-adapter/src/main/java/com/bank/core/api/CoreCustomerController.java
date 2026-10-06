package com.bank.core.api;

import com.bank.core.service.CoreCustomerService;
import com.bank.core.service.CustomerDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/core")
public class CoreCustomerController {

    private final CoreCustomerService coreCustomerService;

    public CoreCustomerController(CoreCustomerService coreCustomerService) {
        this.coreCustomerService = coreCustomerService;
    }

    @GetMapping("/customers/{customerNo}")
    public CustomerDto getCustomer(@PathVariable String customerNo) {
        return coreCustomerService.findCustomer(customerNo);
    }
}

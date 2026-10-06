package com.bank.leasing.client;

import com.bank.leasing.dto.CreditView;
import com.bank.leasing.dto.LimitCheckRequest;
import com.bank.leasing.dto.LimitCheckResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Hedef servis adı placeholder ile verilir; application.yml'den çözülmeli. */
@FeignClient(name = "${clients.credit.name}", path = "/credit-api/v1/credits")
public interface CreditApiClient {

    @PostMapping("/limit-checks")
    LimitCheckResult checkLimit(@RequestBody LimitCheckRequest request);

    @GetMapping("/{id}")
    CreditView getCredit(@PathVariable("id") Long id);
}

package com.bank.credit.api;

import com.bank.credit.service.CreditDto;
import com.bank.credit.service.CreditService;
import com.bank.credit.service.LimitCheckRequest;
import com.bank.credit.service.LimitCheckResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/credits")
public class CreditController {

    private final CreditService creditService;

    public CreditController(CreditService creditService) {
        this.creditService = creditService;
    }

    @PostMapping("/limit-checks")
    public LimitCheckResult checkLimit(@RequestBody LimitCheckRequest request) {
        return creditService.checkLimit(request);
    }

    @GetMapping("/{creditId}")
    public CreditDto getCredit(@PathVariable("creditId") Long creditId) {
        return creditService.getCredit(creditId);
    }

    @PutMapping(path = "/{creditId}/status")
    public void updateStatus(@PathVariable Long creditId, @RequestParam String status) {
        creditService.updateStatus(creditId, status);
    }
}

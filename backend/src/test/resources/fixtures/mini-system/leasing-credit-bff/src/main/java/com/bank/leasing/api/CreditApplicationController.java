package com.bank.leasing.api;

import com.bank.leasing.dto.ApplicationView;
import com.bank.leasing.dto.CreditApplicationRequest;
import com.bank.leasing.service.ApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/applications")
public class CreditApplicationController {

    private final ApplicationService applicationService;

    public CreditApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping("/{applicationId}")
    public ApplicationView getApplication(@PathVariable Long applicationId) {
        return applicationService.getApplication(applicationId);
    }

    @PostMapping
    public ApplicationView createApplication(@RequestBody CreditApplicationRequest request) {
        return applicationService.createApplication(request);
    }
}

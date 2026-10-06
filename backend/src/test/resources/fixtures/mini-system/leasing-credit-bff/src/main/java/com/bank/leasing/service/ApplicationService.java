package com.bank.leasing.service;

import com.bank.leasing.dto.ApplicationView;
import com.bank.leasing.dto.CreditApplicationRequest;

public interface ApplicationService {

    ApplicationView getApplication(Long applicationId);

    ApplicationView createApplication(CreditApplicationRequest request);
}

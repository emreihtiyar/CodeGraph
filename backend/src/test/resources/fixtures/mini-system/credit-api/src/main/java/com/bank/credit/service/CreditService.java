package com.bank.credit.service;

public interface CreditService {

    LimitCheckResult checkLimit(LimitCheckRequest request);

    CreditDto getCredit(Long creditId);

    void updateStatus(Long creditId, String status);
}

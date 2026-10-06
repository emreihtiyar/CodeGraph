package com.bank.credit.service;

import com.bank.credit.client.CoreBankingClient;
import com.bank.credit.client.CoreCustomer;
import com.bank.credit.domain.Credit;
import com.bank.credit.domain.CreditInstallment;
import com.bank.credit.domain.CustomerLimit;
import com.bank.credit.repository.CreditRepository;
import com.bank.credit.repository.CustomerLimitRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final CreditRepository creditRepository;
    private final CustomerLimitRepository customerLimitRepository;
    private final CoreBankingClient coreBankingClient;

    @Override
    public LimitCheckResult checkLimit(LimitCheckRequest request) {
        validate(request);
        CoreCustomer customer = coreBankingClient.getCustomer(request.getCustomerNo());
        CustomerLimit limit = customerLimitRepository.findByCustomerId(request.getCustomerId())
                .orElseThrow();
        if (customer.blacklisted() || limit.getAvailable().compareTo(request.getAmount()) < 0) {
            creditRepository.markRejected(request.getCustomerId());
            return LimitCheckResult.rejected();
        }
        return toResult(limit.getAvailable());
    }

    @Override
    public CreditDto getCredit(Long creditId) {
        return creditRepository.findById(creditId).map(this::toDto).orElseThrow();
    }

    @Override
    public void updateStatus(Long creditId, String status) {
        Credit credit = creditRepository.findById(creditId).orElseThrow();
        credit.setStatus(status);
        for (CreditInstallment installment : credit.getInstallments()) {
            recalculate(installment);
        }
        creditRepository.save(credit);
    }

    private void validate(LimitCheckRequest request) {
        if (request.getAmount() == null) {
            throw new IllegalArgumentException("amount zorunlu");
        }
    }

    private void recalculate(CreditInstallment installment) {
        installment.setAmount(installment.getAmount().setScale(2));
    }

    private CreditDto toDto(Credit credit) {
        return new CreditDto(credit.getId(), credit.getCustomerId(), credit.getAmount(), credit.getStatus());
    }

    private static LimitCheckResult toResult(BigDecimal available) {
        return new LimitCheckResult(true, available);
    }
}

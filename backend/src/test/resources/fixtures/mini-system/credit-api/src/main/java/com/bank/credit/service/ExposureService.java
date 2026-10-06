package com.bank.credit.service;

import com.bank.credit.domain.Credit;
import com.bank.credit.repository.CreditRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ExposureService {

    private final CreditRepository creditRepository;

    public ExposureService(CreditRepository creditRepository) {
        this.creditRepository = creditRepository;
    }

    public BigDecimal calculate(Long customerId) {
        List<Credit> credits = creditRepository.findByCustomerId(customerId);
        return sum(credits, 0);
    }

    /** Özyinelemeli çağrı örneği. */
    private BigDecimal sum(List<Credit> credits, int index) {
        if (index >= credits.size()) {
            return BigDecimal.ZERO;
        }
        return credits.get(index).getAmount().add(sum(credits, index + 1));
    }
}

package com.bank.leasing.service;

import com.bank.leasing.client.CreditApiClient;
import com.bank.leasing.client.SalesApiClient;
import com.bank.leasing.dto.ApplicationView;
import com.bank.leasing.dto.CreditApplicationRequest;
import com.bank.leasing.dto.CreditView;
import com.bank.leasing.dto.LimitCheckRequest;
import com.bank.leasing.dto.LimitCheckResult;
import com.bank.leasing.dto.OfferView;
import com.bank.leasing.util.ValidationUtils;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

/**
 * Orkestrasyon örneği: önce credit-api, koşula bağlı olarak sales-api çağrılır, ardından asenkron denetim kaydı.
 */
@Service
public class ApplicationServiceImpl implements ApplicationService {

    private final CreditApiClient creditApiClient;
    private final SalesApiClient salesApiClient;

    public ApplicationServiceImpl(CreditApiClient creditApiClient, SalesApiClient salesApiClient) {
        this.creditApiClient = creditApiClient;
        this.salesApiClient = salesApiClient;
    }

    @Override
    public ApplicationView getApplication(Long applicationId) {
        CreditView credit = creditApiClient.getCredit(applicationId);
        List<OfferView> offers = salesApiClient.listOffers(credit.customerId()).stream()
                .map(this::toOfferView)
                .toList();
        return new ApplicationView(credit, offers);
    }

    @Override
    public ApplicationView createApplication(CreditApplicationRequest request) {
        validateRequest(request);
        LimitCheckResult limit = creditApiClient.checkLimit(toLimitRequest(request));
        OfferView offer = null;
        if (limit.approved()) {
            offer = salesApiClient.createOffer(request.getCustomerId(), request.getAmount());
        }
        CompletableFuture.runAsync(() -> audit(request));
        return merge(limit, offer);
    }

    private void validateRequest(CreditApplicationRequest request) {
        ValidationUtils.requireText(request.getCustomerNo(), "customerNo");
    }

    private LimitCheckRequest toLimitRequest(CreditApplicationRequest request) {
        return new LimitCheckRequest(request.getCustomerId(), request.getCustomerNo(), request.getAmount());
    }

    private OfferView toOfferView(OfferView offer) {
        return offer;
    }

    private void audit(CreditApplicationRequest request) {
        // denetim kaydı
    }

    private ApplicationView merge(LimitCheckResult limit, OfferView offer) {
        return new ApplicationView(null, offer == null ? List.of() : List.of(offer));
    }
}

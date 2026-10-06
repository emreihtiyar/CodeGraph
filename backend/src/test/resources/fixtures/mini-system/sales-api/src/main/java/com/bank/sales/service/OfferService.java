package com.bank.sales.service;

import com.bank.sales.client.CoreClient;
import com.bank.sales.domain.Dealer;
import com.bank.sales.domain.Offer;
import com.bank.sales.repository.DealerRepository;
import com.bank.sales.repository.OfferRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

@Service
public class OfferService {

    private final OfferRepository offerRepository;
    private final DealerRepository dealerRepository;
    private final CoreClient coreClient;

    public OfferService(OfferRepository offerRepository, DealerRepository dealerRepository, CoreClient coreClient) {
        this.offerRepository = offerRepository;
        this.dealerRepository = dealerRepository;
        this.coreClient = coreClient;
    }

    public OfferDto createOffer(OfferRequest request) {
        Dealer dealer = dealerRepository.findById(request.dealerId()).orElseThrow();
        coreClient.getCustomer(request.customerNo());
        Offer offer = new Offer(request.customerId(), dealer, request.amount());
        Offer saved = offerRepository.save(offer);
        CompletableFuture.runAsync(() -> notifyDealer(saved));
        return toDto(saved);
    }

    public List<OfferDto> listOffers(Long customerId) {
        return offerRepository.findAllByCustomerId(customerId).stream().map(this::toDto).toList();
    }

    private void notifyDealer(Offer offer) {
        // bayi bilgilendirme
    }

    private OfferDto toDto(Offer offer) {
        return new OfferDto(offer.getId(), offer.getCustomerId(), offer.getAmount());
    }
}

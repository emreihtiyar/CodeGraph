package com.bank.sales.api;

import com.bank.sales.service.OfferDto;
import com.bank.sales.service.OfferRequest;
import com.bank.sales.service.OfferService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sales")
public class SalesController {

    private final OfferService offerService;

    public SalesController(OfferService offerService) {
        this.offerService = offerService;
    }

    @PostMapping("/offers")
    public OfferDto createOffer(@RequestBody OfferRequest request) {
        return offerService.createOffer(request);
    }

    @GetMapping("/customers/{customerId}/offers")
    public List<OfferDto> listOffers(@PathVariable Long customerId) {
        return offerService.listOffers(customerId);
    }
}

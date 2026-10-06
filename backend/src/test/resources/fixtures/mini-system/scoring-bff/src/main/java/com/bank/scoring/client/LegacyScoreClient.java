package com.bank.scoring.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Hedef servis analiz kapsamında değil: UNRESOLVED olmalı. */
@FeignClient(name = "legacy-score-service")
public interface LegacyScoreClient {

    @GetMapping("/legacy/scores/{id}")
    int getLegacyScore(@PathVariable("id") Long id);
}

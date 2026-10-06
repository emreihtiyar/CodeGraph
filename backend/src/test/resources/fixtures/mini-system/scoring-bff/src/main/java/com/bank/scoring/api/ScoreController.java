package com.bank.scoring.api;

import com.bank.scoring.service.ScoreService;
import com.bank.scoring.service.ScoreSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Sınıf düzeyinde mapping yok; metot düzeyinde {@code @RequestMapping(method=...)} kullanımı. */
@RestController
public class ScoreController {

    private final ScoreService scoreService;

    public ScoreController(ScoreService scoreService) {
        this.scoreService = scoreService;
    }

    @GetMapping("/scores/{customerId}")
    public int getScore(@PathVariable Long customerId) {
        return scoreService.score(customerId);
    }

    @RequestMapping(value = "/customers/{customerId}/summary", method = RequestMethod.GET)
    public ScoreSummary summary(@PathVariable Long customerId) {
        return scoreService.summary(customerId);
    }
}

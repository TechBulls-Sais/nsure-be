package com.nsure.communication;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.portfolio.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer portal: the signed-in customer manages their own channel and purpose choices (spec 04, NS-SH01-006). */
@RestController
@RequestMapping("/portfolio/shop/preferences")
public class CustomerPreferenceController {

    private final CommunicationService communications;
    private final PortfolioService portfolio;

    public CustomerPreferenceController(CommunicationService communications, PortfolioService portfolio) {
        this.communications = communications;
        this.portfolio = portfolio;
    }

    @GetMapping
    public CommunicationViews.CustomerPreferencesView mine() {
        return communications.ownPreferences(portfolio.customerForLogin());
    }

    @PutMapping
    public CommunicationViews.CustomerPreferencesView setChannel(@RequestBody JsonNode body) {
        return communications.setOwnPreference(portfolio.customerForLogin(), body);
    }

    @PutMapping("/purposes")
    public CommunicationViews.CustomerPreferencesView setPurpose(@RequestBody JsonNode body) {
        return communications.chooseOwnPurpose(portfolio.customerForLogin(), body);
    }
}

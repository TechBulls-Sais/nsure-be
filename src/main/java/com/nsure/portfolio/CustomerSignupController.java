package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer self sign-up from the public Customer sign-in page; no sign-in needed (see ApiAccessRules).
 * The customer gives the same details staff enter on Add customer, then confirms the one-time code sent to their
 * phone ({@code challenge} from the first call plus {@code otp}).
 */
@RestController
@RequestMapping("/public/signup")
public class CustomerSignupController {

    private final PortfolioService portfolio;

    public CustomerSignupController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @PostMapping("/otp")
    public PortfolioService.SignupCodeView requestOtp(@RequestBody JsonNode body) {
        return portfolio.requestSignupOtp(body);
    }

    @PostMapping
    public PortfolioViews.CustomerView signUp(@RequestBody JsonNode body) {
        return portfolio.signUp(body);
    }
}

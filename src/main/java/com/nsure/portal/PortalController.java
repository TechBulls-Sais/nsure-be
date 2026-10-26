package com.nsure.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.portfolio.PortfolioViews;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/portal")
public class PortalController {

    private final PortalService portal;

    public PortalController(PortalService portal) {
        this.portal = portal;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return portal.profile();
    }

    @GetMapping("/products")
    public List<PortfolioViews.ProductView> products() {
        return portal.products();
    }

    @GetMapping("/policies")
    public List<PortfolioViews.CoverView> policies() {
        return portal.myPolicies();
    }

    @PostMapping("/purchase")
    public Map<String, Object> purchase(@RequestBody JsonNode body) {
        return portal.purchase(body);
    }
}

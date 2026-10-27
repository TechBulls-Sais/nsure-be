package com.nsure.portfolio;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only product content for the public landing page; permitted without sign-in in SecurityConfig. */
@RestController
@RequestMapping("/public")
public class PublicProductController {

    private final PortfolioService portfolio;

    public PublicProductController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @GetMapping("/products")
    public List<PortfolioViews.PublicProductView> products() {
        return portfolio.publicProducts();
    }
}

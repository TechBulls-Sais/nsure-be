package com.nsure.servicedesk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer portal: the signed-in customer's own service requests and complaints. */
@RestController
@RequestMapping("/portfolio/shop/cases")
public class CustomerCaseController {

    private final ServiceDeskService desk;

    public CustomerCaseController(ServiceDeskService desk) {
        this.desk = desk;
    }

    @GetMapping
    public List<ServiceDeskViews.CustomerCaseView> cases() {
        return desk.customerCases();
    }

    /** {caseType, category, subject, description, linkedRef}; the channel is PORTAL. */
    @PostMapping
    public ServiceDeskViews.CustomerCaseView open(@RequestBody JsonNode body) {
        return desk.customerOpen(body);
    }
}

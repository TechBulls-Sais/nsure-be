package com.nsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integration")
public class IntegrationController {

    private final IntegrationService integration;
    private final PaymentNotifications payments;

    public IntegrationController(IntegrationService integration, PaymentNotifications payments) {
        this.integration = integration;
        this.payments = payments;
    }

    @GetMapping("/dashboard")
    public IntegrationViews.DashboardView dashboard() {
        return integration.dashboard();
    }

    @GetMapping("/events")
    public List<IntegrationViews.EventView> events(@RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "false") boolean failedOnly) {
        return integration.events(eventType, failedOnly);
    }

    @GetMapping("/inbound")
    public List<IntegrationViews.InboundView> inbound() {
        return integration.inbound();
    }

    @PostMapping("/outbox/dispatch")
    public IntegrationViews.DispatchView dispatch() {
        return integration.dispatchNow();
    }

    @PostMapping("/deliveries/{id}/replay")
    public IntegrationViews.DispatchView replay(@PathVariable Long id) {
        return integration.replay(id);
    }

    /** A provider's payment confirmation: {provider, providerReference, policyRef, amount, currency?, method?}. */
    @PostMapping("/payment-notifications")
    public IntegrationViews.InboundView paymentNotification(@RequestBody JsonNode body) {
        return payments.receive(body);
    }
}

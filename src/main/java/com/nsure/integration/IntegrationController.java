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
    private final ProviderStatusClient providerStatus;

    public IntegrationController(IntegrationService integration, PaymentNotifications payments,
            ProviderStatusClient providerStatus) {
        this.integration = integration;
        this.payments = payments;
        this.providerStatus = providerStatus;
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

    /** Simulator only: the provider's side receives money, {provider, providerReference, amount, currency?}. */
    @PostMapping("/simulator/provider-receipts")
    public IntegrationViews.ProviderReceiptView simulateProviderReceipt(@RequestBody JsonNode body) {
        SimulatedProviderReceipt r = providerStatus.simulateReceipt(text(body, "provider"),
                text(body, "providerReference"), text(body, "amount"), text(body, "currency"));
        return new IntegrationViews.ProviderReceiptView(r.getId(), r.getProvider(), r.getProviderRef(),
                r.getAmount(), r.getCurrency(), r.getRecordedBy(), r.getRecordedAt());
    }

    private static String text(JsonNode body, String field) {
        return body != null && body.hasNonNull(field) ? body.get(field).asText() : null;
    }
}

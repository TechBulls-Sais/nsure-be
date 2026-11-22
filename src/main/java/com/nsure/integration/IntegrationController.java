package com.nsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integration")
public class IntegrationController {

    private final IntegrationService integration;
    private final PaymentNotifications payments;
    private final ProviderStatusClient providerStatus;
    private final InterfaceCatalogue catalogue;
    private final PaymentCallbacks callbacks;
    private final ScreeningService screening;
    private final CompensationTracker compensations;
    private final BatchGateway batchGateway;

    public IntegrationController(IntegrationService integration, PaymentNotifications payments,
            ProviderStatusClient providerStatus, InterfaceCatalogue catalogue, PaymentCallbacks callbacks,
            ScreeningService screening, CompensationTracker compensations, BatchGateway batchGateway) {
        this.batchGateway = batchGateway;
        this.compensations = compensations;
        this.callbacks = callbacks;
        this.screening = screening;
        this.integration = integration;
        this.payments = payments;
        this.providerStatus = providerStatus;
        this.catalogue = catalogue;
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

    /** {reason}: a failed delivery goes back to its consumer with its original payload. */
    @PostMapping("/deliveries/{id}/replay")
    public IntegrationViews.DispatchView replay(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return integration.replay(id, text(body, "reason"));
    }

    /** {reason?}: a pending retry runs now instead of after its backoff. */
    @PostMapping("/deliveries/{id}/retry-now")
    public IntegrationViews.DispatchView retryNow(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return integration.retryNow(id, text(body, "reason"));
    }

    @GetMapping("/dead-letters")
    public List<IntegrationViews.DeadLetterView> deadLetters() {
        return integration.deadLetters();
    }

    @GetMapping("/interfaces")
    public Map<String, Object> interfaces() {
        return catalogue.catalogue();
    }

    /** {available, reason}: the provider simulator's availability switch for one interface. */
    @PutMapping("/interfaces/{code}/availability")
    public Map<String, Object> availability(@PathVariable String code, @RequestBody JsonNode body) {
        return catalogue.setAvailability(code, body);
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() {
        return catalogue.batches();
    }

    @GetMapping("/reconciliation")
    public List<Map<String, Object>> reconciliation() {
        return catalogue.reconciliation();
    }

    /** A census batch with its manifest, validated before any row is staged (§11 batch contract). */
    @PostMapping("/batches/census")
    public Map<String, Object> receiveCensus(@RequestBody JsonNode body) {
        return batchGateway.receiveCensus(body);
    }

    @GetMapping("/batch-receipts")
    public List<Map<String, Object>> batchReceipts() {
        return batchGateway.receipts();
    }

    /** The acknowledgement returned to the partner: counts, row errors and created references. */
    @GetMapping("/batch-receipts/{ref}/acknowledgement")
    public Map<String, Object> acknowledgement(@PathVariable String ref) {
        return batchGateway.acknowledgement(ref);
    }

    /** NS-TXN-004: compensating events with the legs each must correct and whether they did. */
    @GetMapping("/compensations")
    public List<Map<String, Object>> compensations() {
        return compensations.compensations();
    }

    @GetMapping("/screening")
    public List<Map<String, Object>> screeningChecks() {
        return screening.checks();
    }

    /** {customerRef}: a KYC/AML screening request through the (simulated) screening provider. */
    @PostMapping("/screening")
    public Map<String, Object> requestScreening(@RequestBody JsonNode body) {
        return screening.request(body);
    }

    @PostMapping("/screening/retry")
    public Map<String, Object> retryScreening() {
        return screening.retryNow();
    }

    /** {outcome CLEARED | CONFIRMED_MATCH, note}: Compliance decides a referral. */
    @PostMapping("/screening/{ref}/disposition")
    public Map<String, Object> disposeScreening(@PathVariable String ref, @RequestBody JsonNode body) {
        return screening.dispose(ref, body);
    }

    @GetMapping("/operator-actions")
    public List<Map<String, Object>> operatorActions() {
        return catalogue.operatorActions();
    }

    /** A provider's payment confirmation: {provider, providerReference, policyRef, amount, currency?, method?}. */
    @PostMapping("/payment-notifications")
    public IntegrationViews.InboundView paymentNotification(@RequestBody JsonNode body) {
        return payments.receive(body);
    }

    /**
     * A provider's signed settlement callback. No login: authenticated by X-Nsure-Signature over
     * "X-Nsure-Timestamp.body" and the replay window, checked before anything is read or changed.
     */
    @PostMapping("/callbacks/payments")
    public IntegrationViews.InboundView paymentCallback(@RequestBody(required = false) String body,
            @RequestHeader(value = "X-Nsure-Signature", required = false) String signature,
            @RequestHeader(value = "X-Nsure-Timestamp", required = false) String timestamp) {
        return callbacks.callback(body, signature, timestamp);
    }

    /** Simulator only: the provider signs and sends a settlement callback (optionally repeated or tampered). */
    @PostMapping("/simulator/payment-callbacks")
    public Map<String, Object> simulateCallback(@RequestBody JsonNode body) {
        return callbacks.simulate(body);
    }

    /** NS-SH12-004: asks the provider again about a confirmation whose outcome is unknown. */
    @PostMapping("/inbound/{id}/status-query")
    public IntegrationViews.InboundView statusQuery(@PathVariable Long id) {
        return payments.requery(id);
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

package com.nsure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outgoing benefit payments (NS-SH05-007): instructions per benefit, the payout exception queue, reconciliation with
 * the bank, and the SIMULATED bank console a demo uses to settle, reject, return or lose a payment.
 */
@RestController
@RequestMapping("/payments/payouts")
public class PayoutController {

    private final PayoutService payouts;

    public PayoutController(PayoutService payouts) {
        this.payouts = payouts;
    }

    /** A benefit's instructions, newest first: ?source=CLAIM&ref=CLM-0001 (or CARE_CASH and an event ref). */
    @GetMapping
    public List<PayoutViews.InstructionView> instructions(@RequestParam String source, @RequestParam String ref) {
        return payouts.instructions(source, ref);
    }

    /** Rejected, returned, unknown or contradicted instructions waiting for a person (AT-SH05-03). */
    @GetMapping("/exceptions")
    public List<PayoutViews.InstructionView> exceptions() {
        return payouts.exceptions();
    }

    @GetMapping("/{ref}")
    public PayoutViews.InstructionView instruction(@PathVariable String ref) {
        return payouts.instructionView(ref);
    }

    /** Status enquiry to the bank for an unknown or pending instruction; its answer is applied once. */
    @PostMapping("/{ref}/reconcile")
    public PayoutService.CallbackResult reconcile(@PathVariable String ref) {
        return payouts.reconcile(ref);
    }

    /** {note}: closes an exception that needs no resend. */
    @PostMapping("/{ref}/resolve")
    public PayoutViews.InstructionView resolve(@PathVariable String ref, @RequestBody JsonNode body) {
        return payouts.view(payouts.resolve(ref, text(body, "note")));
    }

    /**
     * The rail's callback, provider-shaped: {eventId, providerReference, outcome (SETTLED|REJECTED|RETURNED),
     * reasonCode?, reasonText?}. A repeated event id is answered DUPLICATE and changes nothing (AT-SH05-02).
     */
    @PostMapping("/callback")
    public PayoutService.CallbackResult callback(@RequestBody JsonNode body) {
        return payouts.providerCallback(text(body, "eventId"), text(body, "providerReference"), text(body, "outcome"),
                text(body, "reasonCode"), text(body, "reasonText"));
    }

    /** SIMULATED bank (demo only): {action: SETTLE|REJECT|RETURN|SETTLE_SILENTLY|TIMEOUT|REPEAT, reasonCode?, reasonText?}. */
    @PostMapping("/{ref}/simulator")
    public PayoutService.CallbackResult simulate(@PathVariable String ref, @RequestBody JsonNode body) {
        return payouts.simulate(ref, text(body, "action"), text(body, "reasonCode"), text(body, "reasonText"));
    }

    private static String text(JsonNode body, String field) {
        return body == null || !body.hasNonNull(field) ? null : body.get(field).asText();
    }
}

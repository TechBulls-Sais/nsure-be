package com.nsure.collections;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Collections (SH04): billing accounts, reversals, adjustments, the billing run and statement reconciliation. */
@RestController
@RequestMapping("/collections")
public class CollectionsController {

    private static final Logger log = LoggerFactory.getLogger(CollectionsController.class);

    private final CollectionsService collections;
    private final StatementReconciliation reconciliation;

    public CollectionsController(CollectionsService collections, StatementReconciliation reconciliation) {
        this.collections = collections;
        this.reconciliation = reconciliation;
    }

    /** A policy's due schedule, allocations, receipts, paid-to date and arrears. */
    @GetMapping("/policies/{ref}")
    public CollectionsViews.AccountView account(@PathVariable String ref) {
        return collections.account(ref);
    }

    /** {kind: RETURNED|REVERSED|CHARGEBACK, reason}: asks for the reversal; a second person approves it. */
    @PostMapping("/receipts/{ref}/reverse")
    public CollectionsViews.ReversalRequestView reverse(@PathVariable String ref, @RequestBody JsonNode body) {
        return collections.reverse(ref, body);
    }

    @GetMapping("/reversal-requests")
    public List<CollectionsViews.ReversalRequestView> reversalRequests() {
        return collections.reversalRequests();
    }

    /** {note?} */
    @PostMapping("/reversal-requests/{ref}/approve")
    public CollectionsViews.ReversalRequestView approveReversal(@PathVariable String ref,
            @RequestBody(required = false) JsonNode body) {
        return collections.approveReversal(ref, body);
    }

    /** {note} */
    @PostMapping("/reversal-requests/{ref}/reject")
    public CollectionsViews.ReversalRequestView rejectReversal(@PathVariable String ref, @RequestBody JsonNode body) {
        return collections.rejectReversal(ref, body);
    }

    /** {amount (signed), reason}; send the instalment's version in If-Match. */
    @PostMapping("/instalments/{ref}/adjustments")
    public CollectionsViews.AccountView adjust(@PathVariable String ref, @RequestBody JsonNode body) {
        return collections.adjust(ref, body);
    }

    /**
     * Issues upcoming instalments now. With {@code async=true} it answers 202 at once with an operation whose status
     * is at {@code Location} (/operations/{id}, spec §10).
     */
    @PostMapping("/billing-run")
    public org.springframework.http.ResponseEntity<?> billingRun(
            @RequestParam(name = "async", defaultValue = "false") boolean async) {
        if (!async) {
            return org.springframework.http.ResponseEntity.ok(collections.billingRunNow());
        }
        com.nsure.integration.Operations.OperationView operation = collections.billingRunInBackground();
        return org.springframework.http.ResponseEntity.accepted()
                .location(java.net.URI.create(operation.statusUrl())).body(operation);
    }

    /** Issues upcoming instalments every morning, after the lapse sweep. */
    @Scheduled(cron = "${nsure.collections.billing-cron:0 30 1 * * *}")
    public void scheduledBillingRun() {
        try {
            CollectionsViews.BillingRunView run = collections.billingRun(java.time.LocalDate.now(), "system");
            if (run.issued() > 0) {
                log.info("billing_run: issued {} instalments", run.issued());
            }
        } catch (RuntimeException ex) {
            log.error("Billing run failed", ex);
        }
    }

    /** {provider, periodFrom, periodTo, lines: [{reference, amount, currency?, valueDate?}]} */
    @PostMapping("/statements")
    public CollectionsViews.StatementView uploadStatement(@RequestBody JsonNode body) {
        return reconciliation.upload(body);
    }

    @GetMapping("/statements")
    public List<CollectionsViews.StatementView> statements() {
        return reconciliation.statements();
    }

    @GetMapping("/statements/{ref}/items")
    public List<CollectionsViews.ReconItemView> statementItems(@PathVariable String ref) {
        return reconciliation.items(ref, false);
    }

    /** Open reconciliation exceptions across statements, oldest first. */
    @GetMapping("/reconciliation")
    public List<CollectionsViews.ReconItemView> openExceptions() {
        return reconciliation.items(null, true);
    }

    @PostMapping("/reconciliation/{id}/assign")
    public CollectionsViews.ReconItemView assign(@PathVariable Long id, @RequestBody JsonNode body) {
        return reconciliation.assign(id, body);
    }

    @PostMapping("/reconciliation/{id}/resolve")
    public CollectionsViews.ReconItemView resolve(@PathVariable Long id, @RequestBody JsonNode body) {
        return reconciliation.resolve(id, body);
    }
}

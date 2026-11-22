package com.nsure.collections;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Debit-order and mobile-money collection (NS-SH04-002): mandates, approved batches and the rail's answers. */
@RestController
@RequestMapping("/collections")
public class CollectionRunsController {

    private final CollectionRuns runs;

    public CollectionRunsController(CollectionRuns runs) {
        this.runs = runs;
    }

    @GetMapping("/mandates")
    public List<CollectionsViews.MandateView> mandates(@RequestParam String policyRef) {
        return runs.mandates(policyRef);
    }

    /** {policyRef, channel DEBIT_ORDER|MOBILE_WALLET, institution, accountNumber, accountHolder?, debitDay?} */
    @PostMapping("/mandates")
    public CollectionsViews.MandateView addMandate(@RequestBody JsonNode body) {
        return runs.addMandate(body);
    }

    /** {reason} */
    @PostMapping("/mandates/{ref}/cancel")
    public CollectionsViews.MandateView cancelMandate(@PathVariable String ref, @RequestBody JsonNode body) {
        return runs.cancelMandate(ref, body);
    }

    @GetMapping("/batches")
    public List<CollectionsViews.CollectionBatchView> batches() {
        return runs.batches();
    }

    @GetMapping("/batches/{ref}")
    public CollectionsViews.CollectionBatchView batch(@PathVariable String ref) {
        return runs.batch(ref);
    }

    /** {collectionDate?} */
    @PostMapping("/batches")
    public CollectionsViews.CollectionBatchView prepare(@RequestBody(required = false) JsonNode body) {
        return runs.prepare(body);
    }

    @PostMapping("/batches/{ref}/approve")
    public CollectionsViews.CollectionBatchView approve(@PathVariable String ref) {
        return runs.approve(ref);
    }

    /** {reason} */
    @PostMapping("/batches/{ref}/cancel")
    public CollectionsViews.CollectionBatchView cancel(@PathVariable String ref, @RequestBody JsonNode body) {
        return runs.cancel(ref, body);
    }

    @PostMapping("/batches/{ref}/submit")
    public CollectionsViews.CollectionBatchView submit(@PathVariable String ref) {
        return runs.submit(ref);
    }

    /** {outcomes?: [{instructionRef, outcome CONFIRMED|FAILED|PENDING, reason?}]} — overrides for the simulator. */
    @PostMapping("/batches/{ref}/bank-response")
    public CollectionsViews.CollectionBatchView bankResponse(@PathVariable String ref,
            @RequestBody(required = false) JsonNode body) {
        return runs.bankResponse(ref, body);
    }

    /** {kind RETURNED|REVERSED, reason}: the bank's (simulated) return of a confirmed collection. */
    @PostMapping("/instructions/{ref}/bank-return")
    public CollectionsViews.InstructionView bankReturn(@PathVariable String ref, @RequestBody JsonNode body) {
        return runs.bankReturn(ref, body);
    }
}

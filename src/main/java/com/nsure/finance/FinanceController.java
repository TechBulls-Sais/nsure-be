package com.nsure.finance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/finance")
public class FinanceController {

    private final FinanceService finance;

    public FinanceController(FinanceService finance) {
        this.finance = finance;
    }

    @GetMapping("/accounts")
    public List<FinanceViews.AccountView> accounts() {
        return finance.accounts();
    }

    @GetMapping("/posting-rules")
    public List<FinanceViews.RuleView> rules() {
        return finance.rules();
    }

    @PutMapping("/posting-rules/{eventType}")
    public FinanceViews.RuleView updateRule(@PathVariable String eventType, @RequestBody JsonNode body) {
        return finance.updateRule(eventType, body);
    }

    @GetMapping("/journals")
    public List<FinanceViews.JournalView> journals(
            @RequestParam(required = false) String period, @RequestParam(required = false) String eventType) {
        return finance.journals(period, eventType);
    }

    @GetMapping("/journals/{ref}")
    public FinanceViews.JournalView journal(@PathVariable String ref) {
        return finance.journal(ref);
    }

    @PostMapping("/journals/{ref}/reverse")
    public FinanceViews.JournalView reverse(@PathVariable String ref, @RequestBody JsonNode body) {
        return finance.reverse(ref, body);
    }

    @GetMapping("/trial-balance")
    public FinanceViews.TrialBalanceView trialBalance(@RequestParam(required = false) String period) {
        return finance.trialBalance(period);
    }

    @GetMapping("/periods")
    public List<FinanceViews.PeriodView> periods() {
        return finance.periods();
    }

    /** soft-close, close ({reason, acknowledgeOpenItems}) or reopen ({reason}). */
    @PostMapping("/periods/{code}/{action:soft-close|close|reopen}")
    public FinanceViews.PeriodView periodAction(@PathVariable String code, @PathVariable String action,
            @RequestBody(required = false) JsonNode body) {
        return finance.periodAction(code, action, body);
    }

    @GetMapping("/exceptions")
    public List<FinanceViews.ExceptionView> exceptions() {
        return finance.exceptions();
    }

    @PostMapping("/exceptions/{id}/retry")
    public FinanceViews.ExceptionView retry(@PathVariable Long id) {
        return finance.retry(id);
    }

    @GetMapping("/gl-batches")
    public List<FinanceViews.GlBatchView> glBatches() {
        return finance.glBatches();
    }

    @PostMapping("/gl-batches")
    public FinanceViews.GlBatchView export() {
        return finance.exportToGl();
    }

    @PostMapping("/gl-batches/{ref}/acknowledge")
    public FinanceViews.GlBatchView acknowledge(@PathVariable String ref, @RequestBody JsonNode body) {
        return finance.acknowledge(ref, body);
    }
}

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
    private final FinanceControls controls;
    private final com.nsure.audit.evidence.EvidenceChain evidence;
    private final RefundControls refunds;

    public FinanceController(FinanceService finance, FinanceControls controls,
            com.nsure.audit.evidence.EvidenceChain evidence, RefundControls refunds) {
        this.refunds = refunds;
        this.evidence = evidence;
        this.controls = controls;
        this.finance = finance;
    }

    // ── controlled refunds (AT-SH10-01): raised by one person, approved and paid by another ──────────

    @GetMapping("/refunds")
    public java.util.List<RefundControls.RefundView> refunds() {
        return refunds.refunds();
    }

    /** {source, sourceRef?, policyRef?, payeeName, payoutMethod, accountRef, amount, reason} */
    @PostMapping("/refunds")
    public RefundControls.RefundView requestRefund(@org.springframework.web.bind.annotation.RequestBody
            com.fasterxml.jackson.databind.JsonNode body) {
        return refunds.request(body);
    }

    /** {note?} */
    @PostMapping("/refunds/{ref}/approve")
    public RefundControls.RefundView approveRefund(@org.springframework.web.bind.annotation.PathVariable String ref,
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            com.fasterxml.jackson.databind.JsonNode body) {
        return refunds.approve(ref, body);
    }

    /** {note} */
    @PostMapping("/refunds/{ref}/reject")
    public RefundControls.RefundView rejectRefund(@org.springframework.web.bind.annotation.PathVariable String ref,
            @org.springframework.web.bind.annotation.RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return refunds.reject(ref, body);
    }

    @GetMapping("/accounts")
    public List<FinanceViews.AccountView> accounts() {
        return finance.accounts();
    }

    @GetMapping("/posting-rules")
    public List<FinanceViews.RuleView> rules() {
        return finance.rules();
    }

    /** Proposes the next version of the rule; it applies only once another user approves it. */
    @PutMapping("/posting-rules/{eventType}")
    public FinanceViews.RuleView proposeRule(@PathVariable String eventType, @RequestBody JsonNode body) {
        return finance.proposeRule(eventType, body);
    }

    @PostMapping("/posting-rules/{eventType}/versions/{version}/approve")
    public FinanceViews.RuleView approveRule(@PathVariable String eventType, @PathVariable int version) {
        return finance.approveRule(eventType, version);
    }

    @PostMapping("/posting-rules/{eventType}/versions/{version}/reject")
    public FinanceViews.RuleView rejectRule(@PathVariable String eventType, @PathVariable int version,
            @RequestBody JsonNode body) {
        return finance.rejectRule(eventType, version, body);
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
        FinanceViews.GlBatchView batch = finance.exportToGl();
        // An export of financial data is audit evidence (NS-SH11-005).
        evidence.recordNow(null, "Exported GL batch", "GL batch", batch.ref(), null);
        return batch;
    }

    @PostMapping("/gl-batches/{ref}/acknowledge")
    public FinanceViews.GlBatchView acknowledge(@PathVariable String ref, @RequestBody JsonNode body) {
        return finance.acknowledge(ref, body);
    }

    @PostMapping("/gl-batches/{ref}/submit")
    public FinanceViews.GlBatchView submit(@PathVariable String ref) {
        return finance.submit(ref);
    }

    /** Body: {@code {"glDebitTotal": 100, "glCreditTotal": 100}} as the GL reports them. */
    @PostMapping("/gl-batches/{ref}/reconcile")
    public FinanceViews.GlBatchView reconcile(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return finance.reconcile(ref, body);
    }

    // ── manual journals (maker-checker) ──

    @GetMapping("/manual-journals")
    public List<FinanceViews.ManualJournalView> manualJournals() {
        return controls.manualJournals();
    }

    /** Body: {@code {"description", "reason", "accountingDate", "lines": [{"account", "debit", "credit"}]}}. */
    @PostMapping("/manual-journals")
    public FinanceViews.ManualJournalView proposeManualJournal(@RequestBody(required = false) JsonNode body) {
        return controls.proposeManualJournal(body);
    }

    @PostMapping("/manual-journals/{ref}/approve")
    public FinanceViews.ManualJournalView approveManualJournal(@PathVariable String ref) {
        return controls.approveManualJournal(ref);
    }

    @PostMapping("/manual-journals/{ref}/reject")
    public FinanceViews.ManualJournalView rejectManualJournal(@PathVariable String ref,
            @RequestBody(required = false) JsonNode body) {
        return controls.rejectManualJournal(ref, body);
    }

    // ── suspense clearance (maker-checker) ──

    @GetMapping("/suspense")
    public List<FinanceViews.SuspenseView> suspense() {
        return controls.suspense();
    }

    /** Body: {@code {"action": "ALLOCATE" | "REFUND", "policyRef", "reason"}}. */
    @PostMapping("/suspense/{id}/clearance")
    public FinanceViews.SuspenseView requestClearance(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return controls.requestClearance(id, body);
    }

    @PostMapping("/suspense/{id}/clearance/approve")
    public FinanceViews.SuspenseView approveClearance(@PathVariable Long id) {
        return controls.approveClearance(id);
    }

    @PostMapping("/suspense/{id}/clearance/reject")
    public FinanceViews.SuspenseView rejectClearance(@PathVariable Long id) {
        return controls.rejectClearance(id);
    }
}

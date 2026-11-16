package com.nsure.credit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** IL05 Individual credit life: lenders, loans, balances, credit claims, early settlements and refunds. */
@RestController
@RequestMapping("/credit")
public class CreditController {

    private final CreditService credit;

    public CreditController(CreditService credit) {
        this.credit = credit;
    }

    @GetMapping("/summary")
    public CreditViews.Summary summary() {
        return credit.summary();
    }

    @GetMapping("/lenders")
    public List<CreditViews.LenderView> lenders() {
        return credit.lenders();
    }

    /** {name, contactEmail?} */
    @PostMapping("/lenders")
    public CreditViews.LenderView addLender(@RequestBody JsonNode body) {
        return credit.addLender(body);
    }

    /** {batchReference, rows: [{lenderLoanId, effectiveDate, balance}]} */
    @PostMapping("/lenders/{ref}/balances")
    public CreditViews.BatchResult balanceBatch(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.balanceBatch(ref, body);
    }

    @GetMapping("/rule")
    public CreditViews.RuleView rule() {
        return credit.rule();
    }

    @PutMapping("/rule")
    public CreditViews.RuleView updateRule(@RequestBody JsonNode body) {
        return credit.updateRule(body);
    }

    @GetMapping("/loans")
    public List<CreditViews.LoanView> loans() {
        return credit.loans();
    }

    /** {lenderRef, lenderLoanId, customerRef, principal, termMonths, premiumBasis, secured?, pensioner?, …benefits} */
    @PostMapping("/loans")
    public CreditViews.LoanView submit(@RequestBody JsonNode body) {
        return credit.submit(body);
    }

    @GetMapping("/loans/{ref}")
    public CreditViews.LoanDetail loan(@PathVariable String ref) {
        return credit.loanDetail(ref);
    }

    /** {disbursementDate, reference} */
    @PostMapping("/loans/{ref}/disbursement")
    public CreditViews.LoanView disburse(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.disburse(ref, body);
    }

    /** {sourceReference, effectiveDate, balance} */
    @PostMapping("/loans/{ref}/balances")
    public CreditViews.LoanDetail updateBalance(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.updateBalance(ref, body);
    }

    /** {note} */
    @PostMapping("/loans/{ref}/balance-exception/resolve")
    public CreditViews.LoanDetail resolveException(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.resolveException(ref, body);
    }

    @GetMapping("/loans/{ref}/claim-assessment")
    public CreditViews.ClaimAssessment assessClaim(@PathVariable String ref,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDate) {
        return credit.assessClaim(ref, eventDate);
    }

    /** {eventDate, claimRef?, payeeVerified} */
    @PostMapping("/loans/{ref}/claim")
    public CreditViews.LoanDetail recordClaim(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.recordClaim(ref, body);
    }

    @GetMapping("/loans/{ref}/settlement-preview")
    public CreditViews.SettlementPreview previewSettlement(@PathVariable String ref,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return credit.previewSettlement(ref, date);
    }

    /** {settlementReference, settlementDate} */
    @PostMapping("/loans/{ref}/settlement")
    public CreditViews.LoanDetail settle(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.settle(ref, body);
    }

    /** {newLenderLoanId, principal, termMonths, settlementReference, premiumBasis?} */
    @PostMapping("/loans/{ref}/refinance")
    public CreditViews.LoanView refinance(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.refinance(ref, body);
    }

    @GetMapping("/refunds")
    public List<CreditViews.RefundView> refunds() {
        return credit.refunds();
    }

    /** {paymentReference} */
    @PostMapping("/refunds/{ref}/payment")
    public CreditViews.RefundView payRefund(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.payRefund(ref, body);
    }
}

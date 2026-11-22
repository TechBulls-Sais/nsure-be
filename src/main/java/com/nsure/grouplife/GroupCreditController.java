package com.nsure.grouplife;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Group credit life (GL06): lender agreement, loan bordereaux and underwriting of referrals, lender update files and
 * exceptions, premium remittance, settlement and cancellation refunds, and credit claims.
 */
@RestController
@RequestMapping("/group-life")
public class GroupCreditController {

    private final GroupCreditService credit;

    public GroupCreditController(GroupCreditService credit) {
        this.credit = credit;
    }

    @GetMapping("/schemes/{ref}/lender-agreement")
    public Map<String, Object> agreement(@PathVariable String ref) {
        return credit.agreement(ref);
    }

    @PutMapping("/schemes/{ref}/lender-agreement")
    public Map<String, Object> saveAgreement(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.saveAgreement(ref, body);
    }

    @GetMapping("/schemes/{ref}/credit-loans")
    public List<Map<String, Object>> creditLoans(@PathVariable String ref) {
        return credit.creditLoans(ref);
    }

    /** Version 2.0 or the version 1 layout {batchReference, ratePerMille?, rows [...]} */
    @PostMapping("/schemes/{ref}/credit-loans")
    public Map<String, Object> bordereau(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.bordereau(ref, body);
    }

    @GetMapping("/credit-loans/{id}")
    public Map<String, Object> loan(@PathVariable Long id) {
        return credit.loan(id);
    }

    /** {decision ACCEPT|DECLINE, coverAmount?, note} */
    @PostMapping("/credit-loans/{id}/underwriting")
    public Map<String, Object> underwrite(@PathVariable Long id, @RequestBody JsonNode body) {
        return credit.underwrite(id, body);
    }

    /** The lender update file (interface 2.0): balances, repayments, arrears, settlements and cancellations. */
    @PostMapping("/schemes/{ref}/credit-files")
    public Map<String, Object> updateFile(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.updateFile(ref, body);
    }

    @GetMapping("/schemes/{ref}/credit-exceptions")
    public List<Map<String, Object>> exceptions(@PathVariable String ref) {
        return credit.exceptions(ref);
    }

    @PostMapping("/credit-exceptions/{id}/resolution")
    public Map<String, Object> resolveException(@PathVariable Long id, @RequestBody JsonNode body) {
        return credit.resolveException(id, body);
    }

    @GetMapping("/schemes/{ref}/credit-remittances")
    public List<Map<String, Object>> remittances(@PathVariable String ref) {
        return credit.remittances(ref);
    }

    /** {period yyyy-MM} */
    @PostMapping("/schemes/{ref}/credit-remittances")
    public Map<String, Object> remit(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.remit(ref, body);
    }

    /** {reference, amount, rows?[{lenderLoanId, amount}]} */
    @PostMapping("/credit-remittances/{ref}/receipts")
    public Map<String, Object> remittanceReceipt(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.remittanceReceipt(ref, body);
    }

    @GetMapping("/credit-loans/{id}/settlement")
    public Map<String, Object> settlementPreview(@PathVariable Long id,
            @RequestParam(required = false) String settlementDate) {
        return credit.settlementPreview(id, settlementDate);
    }

    @PostMapping("/credit-loans/{id}/settlement")
    public Map<String, Object> settleLoan(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return credit.settleLoan(id, body);
    }

    @GetMapping("/schemes/{ref}/credit-refunds")
    public List<Map<String, Object>> refunds(@PathVariable String ref) {
        return credit.refunds(ref);
    }

    /** {approve, note}: by someone other than the requester. */
    @PostMapping("/credit-refunds/{ref}/decision")
    public Map<String, Object> decideRefund(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.decideRefund(ref, body);
    }

    /** {paymentReference}: simulated payout of an approved refund. */
    @PostMapping("/credit-refunds/{ref}/payment")
    public Map<String, Object> payRefund(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.payRefund(ref, body);
    }

    @GetMapping("/schemes/{ref}/credit-claims")
    public List<Map<String, Object>> claims(@PathVariable String ref) {
        return credit.claims(ref);
    }

    /** {benefit, eventDate, balanceAtEvent?, monthlyInstalment?, months?, note?} */
    @PostMapping("/credit-loans/{id}/claims")
    public Map<String, Object> notifyClaim(@PathVariable Long id, @RequestBody JsonNode body) {
        return credit.notifyClaim(id, body);
    }

    /** {action APPROVE|DECLINE|PAY|RECOVER, note?, paymentReference?, confirmedBalance?, recoveredAmount?} */
    @PostMapping("/credit-claims/{ref}/decision")
    public Map<String, Object> decideClaim(@PathVariable String ref, @RequestBody JsonNode body) {
        return credit.decideClaim(ref, body);
    }
}

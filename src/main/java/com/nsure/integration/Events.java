package com.nsure.integration;

/**
 * The event catalogue. Payload fields are listed with each type; amounts are decimal strings in BWP.
 */
public final class Events {

    /** policyRef, customerName, product, amount, method, receiptRef, firstPremium (boolean). */
    public static final String PREMIUM_RECEIVED = "PremiumReceived";
    /** policyRef, customerName, product, sumAssured, premium. Raised with the first premium. */
    public static final String POLICY_ISSUED = "PolicyIssued";
    /** policyRef, customerName, product, reason. */
    public static final String POLICY_CANCELLED = "PolicyCancelled";
    /** claimRef, policyRef, customerName, product, amount, payoutRef. */
    public static final String CLAIM_PAID = "ClaimPaid";
    /** provider, providerRef, policyRef, amount, reason. Cash received that could not be applied: suspense. */
    public static final String UNMATCHED_RECEIPT = "UnmatchedReceipt";
    /** schemeRef, schemeName, invoiceRef, period, amount. */
    public static final String SCHEME_INVOICE_ISSUED = "SchemeInvoiceIssued";
    /** schemeRef, invoiceRef, receiptRef, amount, outstanding. */
    public static final String SPONSOR_RECEIPT_ALLOCATED = "SponsorReceiptAllocated";
    /** commissionRef, intermediaryRef, policyRef, product, amount. */
    public static final String COMMISSION_ACCRUED = "CommissionAccrued";
    /** commissionRef, intermediaryRef, policyRef, product, amount (positive: the amount clawed back). */
    public static final String COMMISSION_REVERSED = "CommissionReversed";
    /** statementRef, intermediaryRef, amount. */
    public static final String COMMISSION_PAID = "CommissionPaid";
    /** cessionRef, treatyRef, policyRef, product, cededPremium. */
    public static final String RISK_CEDED = "RiskCeded";
    /** recoveryRef, treatyRef, claimRef, policyRef, amount. */
    public static final String RECOVERY_RECOGNISED = "ReinsuranceRecoveryRecognised";
    /** bordereauRef, treatyRef, reinsurer, period, recordCount, totalCededPremium, totalCededSum (SH07). */
    public static final String BORDEREAU_SUBMITTED = "ReinsuranceBordereauSubmitted";
    /** recoveryRef, treatyRef, claimRef, policyRef, amount (received), expected, reference (SH07). */
    public static final String RECOVERY_RECEIVED = "RecoveryReceived";
    /** claimRef, policyRef, customerName, product, amount, reason. Raised when a claim is lodged (spec 05 §9). */
    public static final String CLAIM_RECEIVED = "ClaimReceived";
    /** claimRef, policyRef, customerName, product, amount (the approved payout), reason. */
    public static final String CLAIM_APPROVED = "ClaimApproved";
    /** claimRef, policyRef, customerName, product, amount, reason (the rejection reason). */
    public static final String CLAIM_REJECTED = "ClaimRejected";
    /** controlKey, name, changedBy, reason. A control was switched off (spec 12 C-11). */
    public static final String CONTROL_DISABLED = "ControlDisabled";
    /** caseRef, caseType, customerName, category. */
    public static final String SERVICE_CASE_OPENED = "ServiceCaseOpened";

    /** inboundId, providerRef, action (ALLOCATE or REFUND), policyRef, amount. Suspense cash allocated or refunded. */
    public static final String SUSPENSE_CLEARED = "SuspenseCleared";

    /** policyRef, customerRef, customerName, product, amount, dueDate. A scheduled premium reminder (Blueprint §5). */
    public static final String PREMIUM_DUE_REMINDER = "PremiumDueReminder";

    /** Template event type for approved bulk operational notices; never published on the bus. */
    public static final String OPERATIONAL_NOTICE = "OperationalNotice";

    // Individual new business (spec IL01).
    public static final String APPLICATION_SUBMITTED = "ApplicationSubmitted";
    // IL02 underwriting.
    public static final String EVIDENCE_REQUESTED = "EvidenceRequested";
    public static final String UNDERWRITING_DECIDED = "UnderwritingDecided";
    public static final String OFFER_REVISED = "OfferRevised";
    public static final String OFFER_ANSWERED = "OfferAnswered";
    // IL03/IL04 policy servicing.
    public static final String POLICY_CHANGED = "PolicyChanged";
    public static final String COVER_LAPSED = "CoverLapsed";
    public static final String POLICY_REINSTATED = "PolicyReinstated";
    public static final String POLICY_TERMINATED = "PolicyTerminated";
    public static final String INSURED_LIFE_ADDED = "InsuredLifeAdded";
    public static final String INSURED_LIFE_CEASED = "InsuredLifeCeased";
    public static final String DEPENDANT_ELIGIBILITY_CHANGED = "DependantEligibilityChanged";
    public static final String BENEFIT_DUE = "ContractualBenefitDue";
    public static final String BENEFIT_PAID = "ContractualBenefitPaid";
    public static final String PREMIUM_REFUNDED = "PremiumRefunded";
    // IL05 individual credit life.
    public static final String LOAN_DISBURSED = "LoanDisbursed";
    public static final String LOAN_BALANCE_UPDATED = "LoanBalanceUpdated";
    public static final String LOAN_SETTLED = "LoanSettled";
    public static final String CREDIT_COVER_TERMINATED = "CreditCoverTerminated";
    // Group lifecycle.
    public static final String SCHEME_QUOTED = "SchemeQuoted";
    public static final String SCHEME_ACTIVATED = "SchemeActivated";
    public static final String SCHEME_RENEWED = "SchemeRenewed";
    public static final String SCHEME_CLOSED = "SchemeClosed";
    public static final String SCHEME_PLAN_APPROVED = "SchemePlanApproved";
    public static final String BORROWER_COVER_ACTIVATED = "BorrowerCoverActivated";
    public static final String CREDIT_BORDEREAU_PROCESSED = "CreditBordereauProcessed";
    public static final String GROUP_PREMIUM_REFUNDED = "GroupPremiumRefunded";
    public static final String OFFER_ACCEPTED = "OfferAccepted";

    // Collections and claims (spec SH04, SH05).
    public static final String INVOICE_ISSUED = "InvoiceIssued";
    public static final String RECEIPT_ALLOCATED = "ReceiptAllocated";
    public static final String PAYMENT_RETURNED = "PaymentReturned";
    /**
     * receiptRef, provider, providerRef, policyRef, amount, currency, method, source (CHECKOUT or NOTIFICATION). The
     * provider confirmed it holds the money; allocation to the policy follows as ReceiptAllocated (SH04).
     */
    public static final String RECEIPT_CONFIRMED = "ReceiptConfirmed";
    /** refundRef, source (e.g. SUSPENSE), providerRef, policyRef, amount, requestedBy, approvedBy. A refund paid out. */
    public static final String REFUND_SETTLED = "RefundSettled";
    public static final String BENEFIT_LIMIT_CONSUMED = "BenefitLimitConsumed";
    // SH05 names for the claim contract, published alongside the older names that existing handlers consume:
    // ClaimRegistered with ClaimReceived, ClaimDeclined with ClaimRejected, ClaimPaymentSettled with ClaimPaid.
    /** claimRef, policyRef, customerName, product, amount, reason, claimType, eventDate. Raised when a claim is lodged. */
    public static final String CLAIM_REGISTERED = "ClaimRegistered";
    /** claimRef, policyRef, customerName, product, amount, reason (assessor's note), evidenceOutstanding. */
    public static final String CLAIM_ASSESSED = "ClaimAssessed";
    /** claimRef, policyRef, customerName, product, amount, reason (the decline reason). */
    public static final String CLAIM_DECLINED = "ClaimDeclined";
    /** claimRef, policyRef, customerName, product, amount, payoutRef. The payout is confirmed settled. */
    public static final String CLAIM_PAYMENT_SETTLED = "ClaimPaymentSettled";

    private Events() {
    }
}

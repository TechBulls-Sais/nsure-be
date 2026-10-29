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
    /** caseRef, caseType, customerName, category. */
    public static final String SERVICE_CASE_OPENED = "ServiceCaseOpened";

    private Events() {
    }
}

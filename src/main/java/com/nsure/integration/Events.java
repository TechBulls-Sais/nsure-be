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
    /**
     * commissionRef, intermediaryRef, policyRef, product, amount, statementRef. An accrued commission line released
     * for payment on an intermediary statement (SH06); CommissionPaid follows when the statement is paid.
     */
    public static final String COMMISSION_RELEASED = "CommissionReleased";
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

    // SH10 workflow events (spec "Records and workspace"), raised alongside any older module-specific names.
    /** caseRef, caseType, customerRef, channel. A service request or complaint was opened (with ServiceCaseOpened). */
    public static final String CASE_OPENED = "CaseOpened";
    /** key, workflow, ref, owner, assignedBy. Open work was taken by, or assigned to, its owner. */
    public static final String TASK_ASSIGNED = "TaskAssigned";
    /** commandId, permissionCode, entity, resourceId, makerId, checker. A held change was approved and applied. */
    public static final String APPROVAL_GRANTED = "ApprovalGranted";
    /** key, workflow, ref, team, owner, dueAt. Open work passed its SLA due time; raised once per item. */
    public static final String SLA_BREACHED = "SlaBreached";
    /** caseRef, caseType, customerRef, closedBy. A service request or complaint was closed. */
    public static final String CASE_CLOSED = "CaseClosed";

    /** inboundId, providerRef, action (ALLOCATE or REFUND), policyRef, amount. Suspense cash allocated or refunded. */
    public static final String SUSPENSE_CLEARED = "SuspenseCleared";

    // Finance subledger (spec SH08). Raised by the subledger itself, so no subledger handler consumes them.
    /** journalRef, eventType, sourceRef, period, accountingDate, amount, ruleVersion, reverses (or ""). */
    public static final String JOURNAL_POSTED = "JournalPosted";
    /** eventKey, eventType, sourceRef, amount, reason. A monetary event could not be posted. */
    public static final String FINANCIAL_EXCEPTION_RAISED = "FinancialExceptionRaised";
    /** batchRef, glReference, journalCount, debitTotal, creditTotal. The general ledger accepted the batch. */
    public static final String GL_BATCH_ACCEPTED = "GlBatchAccepted";
    /** period, closedBy, openItems (acknowledged at close; empty when none). */
    public static final String PERIOD_CLOSED = "PeriodClosed";

    /** policyRef, customerRef, customerName, product, amount, dueDate. A scheduled premium reminder (Blueprint §5). */
    public static final String PREMIUM_DUE_REMINDER = "PremiumDueReminder";

    /** Template event type for approved bulk operational notices; never published on the bus. */
    public static final String OPERATIONAL_NOTICE = "OperationalNotice";

    // SH02 product configuration and release.
    /** product (or releaseCode), version, effectiveFrom, proposedBy, approvedBy, source. A product version approved. */
    public static final String PRODUCT_VERSION_APPROVED = "ProductVersionApproved";
    /** configRef or releaseCode, code, name, version, products, activatedBy, source. Made active for new business. */
    public static final String PRODUCT_ACTIVATED = "ProductActivated";
    /** configRef, code, name, version, status (the status it moved to), withdrawnBy. No longer active: stops new sales. */
    public static final String PRODUCT_WITHDRAWN = "ProductWithdrawn";

    // Individual new business (spec IL01).
    public static final String APPLICATION_SUBMITTED = "ApplicationSubmitted";
    /** applicationRef, policyRef, customerRef, product, quoteRef, quoteVersion. Staff issued an application's offer. */
    public static final String ISSUE_REQUESTED = "IssueRequested";
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
    // Group plans, member underwriting, census and group credit (spec GL02, GL03, GL04, GL06).
    /** schemeRef, categoryCode, change (ADDED or AMENDED), benefitBasis, freeCoverLimit, ratePerMille. */
    public static final String CATEGORY_TERMS_CHANGED = "CategoryTermsChanged";
    /** schemeRef, memberKey, memberId, changeType, requestedCover, acceptedCover, pendingCover. */
    public static final String MEMBER_UNDERWRITING_REFERRED = "MemberUnderwritingReferred";
    /** schemeRef, memberKey, memberId, decision (ACCEPT or DECLINE), acceptedCover, effectiveDate. */
    public static final String MEMBER_COVER_AUTHORISED = "MemberCoverAuthorised";
    /** schemeRef, memberKey, memberId, expiredOn, pendingCover: an underwriting offer lapsed unanswered (GL03). */
    public static final String MEMBER_DECISION_EXPIRED = "MemberDecisionExpired";
    /** schemeRef, batchRef, externalBatchId, mode, totalRows, validRows, invalidRows. */
    public static final String CENSUS_BATCH_VALIDATED = "CensusBatchValidated";
    /** schemeRef, memberKey, memberId, changeType, effectiveDate, coverBefore, coverAfter, pendingAfter, sourceRef. */
    public static final String MEMBER_COVER_CHANGED = "MemberCoverChanged";
    /** schemeRef, batchRef, externalBatchId, acceptedRows, rejectedRows. */
    public static final String CENSUS_BATCH_COMPLETED = "CensusBatchCompleted";
    /** schemeRef, certificateNo, settlementDate, amount. Raised with GroupPremiumRefunded when a settlement is made. */
    public static final String UNEARNED_PREMIUM_REFUND_APPROVED = "UnearnedPremiumRefundApproved";

    // Party master (spec SH01). References only, never identity numbers or contact details (NS-API-003).
    /** customerRef, partyType, channel (STAFF or SELF_SIGNUP), kycStatus, duplicateReview (boolean). */
    public static final String PARTY_CREATED = "PartyCreated";
    /** survivingRef, mergedRef, recordsMoved. */
    public static final String PARTY_MERGED = "PartyMerged";
    /** customerRef, review (KYC or EDD), previousStatus, status. */
    public static final String KYC_STATUS_CHANGED = "KycStatusChanged";

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

    // Communications (spec SH09B). Payloads carry references only: never the message content or the contact.
    /** messageRef, eventType, businessRef, customerRef, recipientRole, channel, purpose, templateCode, templateVersion, status, reason. */
    public static final String COMMUNICATION_REQUESTED = "CommunicationRequested";
    /** messageRef, eventType, businessRef, customerRef, recipientRole, channel, purpose, providerMessageId, status. */
    public static final String MESSAGE_DELIVERED = "MessageDelivered";
    /** messageRef, eventType, businessRef, customerRef, recipientRole, channel, purpose, providerMessageId, reason. */
    public static final String MESSAGE_FAILED = "MessageFailed";
    /** inboundId, channel, status (LINKED or TRIAGE), customerRef, caseRef. Never the sender or the text. */
    public static final String INBOUND_MESSAGE_RECEIVED = "InboundMessageReceived";

    private Events() {
    }
}

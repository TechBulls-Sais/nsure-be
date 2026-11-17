package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ins_application")
public class InsuranceApplication extends CoverRecord implements TieredCover {

    // Optimistic locking (NS-ARC-005): a write based on a stale copy fails with 409 instead of overwriting.
    @jakarta.persistence.Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public long getRowVersion() {
        return rowVersion;
    }

    @Column(length = 60)
    private String tier;

    @Column(name = "rate_table_version", length = 30)
    private String rateTableVersion;

    @Override
    public String getTier() {
        return tier;
    }

    @Override
    public void setTier(String tier) {
        this.tier = tier;
    }

    @Override
    public String getRateTableVersion() {
        return rateTableVersion;
    }

    @Override
    public void setRateTableVersion(String rateTableVersion) {
        this.rateTableVersion = rateTableVersion;
    }

    // The accepted quote this record was priced from (NS-IL01-001, NS-SH03-003).
    @jakarta.persistence.Column(name = "quote_ref", length = 20)
    private String quoteRef;

    @jakarta.persistence.Column(name = "quote_version")
    private Integer quoteVersion;

    public String getQuoteRef() {
        return quoteRef;
    }

    public Integer getQuoteVersion() {
        return quoteVersion;
    }

    public void setQuote(String quoteRef, Integer quoteVersion) {
        this.quoteRef = quoteRef;
        this.quoteVersion = quoteVersion;
    }

    // NS-IL01-002: proposer, payer, mandate, channel and declaration (null proposer or payer: the policyholder).
    @jakarta.persistence.Column(name = "proposer_name", length = 100)
    private String proposerName;

    @jakarta.persistence.Column(name = "proposer_relationship", length = 60)
    private String proposerRelationship;

    @jakarta.persistence.Column(name = "payer_name", length = 100)
    private String payerName;

    @jakarta.persistence.Column(name = "payer_relationship", length = 60)
    private String payerRelationship;

    @jakarta.persistence.Column(name = "payer_id_number", length = 40)
    private String payerIdNumber;

    @jakarta.persistence.Column(name = "mandate_method", length = 30)
    private String mandateMethod;

    @jakarta.persistence.Column(name = "mandate_institution", length = 60)
    private String mandateInstitution;

    @jakarta.persistence.Column(name = "mandate_account", length = 60)
    private String mandateAccount;

    @jakarta.persistence.Column(name = "channel", length = 20)
    private String channel;

    @jakarta.persistence.Column(name = "intermediary_code", length = 30)
    private String intermediaryCode;

    @jakarta.persistence.Column(name = "declared_by", length = 100)
    private String declaredBy;

    @jakarta.persistence.Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @jakarta.persistence.Column(name = "mandate_debit_day")
    private Integer mandateDebitDay;

    @jakarta.persistence.Column(name = "declared_at")
    private java.time.LocalDateTime declaredAt;

    @jakarta.persistence.Column(name = "submitted_at")
    private java.time.LocalDateTime submittedAt;

    public String getProposerName() {
        return proposerName;
    }

    public void setProposerName(String proposerName) {
        this.proposerName = proposerName;
    }

    public String getProposerRelationship() {
        return proposerRelationship;
    }

    public void setProposerRelationship(String proposerRelationship) {
        this.proposerRelationship = proposerRelationship;
    }

    public String getPayerName() {
        return payerName;
    }

    public void setPayerName(String payerName) {
        this.payerName = payerName;
    }

    public String getPayerRelationship() {
        return payerRelationship;
    }

    public void setPayerRelationship(String payerRelationship) {
        this.payerRelationship = payerRelationship;
    }

    public String getPayerIdNumber() {
        return payerIdNumber;
    }

    public void setPayerIdNumber(String payerIdNumber) {
        this.payerIdNumber = payerIdNumber;
    }

    public String getMandateMethod() {
        return mandateMethod;
    }

    public void setMandateMethod(String mandateMethod) {
        this.mandateMethod = mandateMethod;
    }

    public String getMandateInstitution() {
        return mandateInstitution;
    }

    public void setMandateInstitution(String mandateInstitution) {
        this.mandateInstitution = mandateInstitution;
    }

    public String getMandateAccount() {
        return mandateAccount;
    }

    public void setMandateAccount(String mandateAccount) {
        this.mandateAccount = mandateAccount;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getIntermediaryCode() {
        return intermediaryCode;
    }

    public void setIntermediaryCode(String intermediaryCode) {
        this.intermediaryCode = intermediaryCode;
    }

    public String getDeclaredBy() {
        return declaredBy;
    }

    public void setDeclaredBy(String declaredBy) {
        this.declaredBy = declaredBy;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(String submittedBy) {
        this.submittedBy = submittedBy;
    }

    public Integer getMandateDebitDay() {
        return mandateDebitDay;
    }

    public void setMandateDebitDay(Integer mandateDebitDay) {
        this.mandateDebitDay = mandateDebitDay;
    }

    public java.time.LocalDateTime getDeclaredAt() {
        return declaredAt;
    }

    public void setDeclaredAt(java.time.LocalDateTime declaredAt) {
        this.declaredAt = declaredAt;
    }

    public java.time.LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(java.time.LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }
}

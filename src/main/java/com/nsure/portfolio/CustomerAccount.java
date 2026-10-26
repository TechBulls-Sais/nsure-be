package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ins_customer")
public class CustomerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 150)
    private String email;

    @Column(nullable = false, length = 40)
    private String phone;

    @Column(length = 100)
    private String product;

    @Column(name = "policy_count", nullable = false)
    private int policyCount;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(nullable = false, length = 20)
    private String color;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    // A politically exposed person needs an enhanced due diligence review (demo server pip_pep_status).
    @Column(nullable = false)
    private boolean pep;

    @Column(name = "edd_status", length = 20)
    private String eddStatus;

    // Why staff rejected the KYC or EDD review; shown to the customer, cleared on approval.
    @Column(name = "kyc_rejection_reason", length = 500)
    private String kycRejectionReason;

    @Column(name = "edd_rejection_reason", length = 500)
    private String eddRejectionReason;

    protected CustomerAccount() {
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public int getPolicyCount() {
        return policyCount;
    }

    public void setPolicyCount(int policyCount) {
        this.policyCount = policyCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public boolean isPep() {
        return pep;
    }

    public void setPep(boolean pep) {
        this.pep = pep;
    }

    public String getEddStatus() {
        return eddStatus;
    }

    public void setEddStatus(String eddStatus) {
        this.eddStatus = eddStatus;
    }

    public String getKycRejectionReason() {
        return kycRejectionReason;
    }

    public void setKycRejectionReason(String kycRejectionReason) {
        this.kycRejectionReason = kycRejectionReason;
    }

    public String getEddRejectionReason() {
        return eddRejectionReason;
    }

    public void setEddRejectionReason(String eddRejectionReason) {
        this.eddRejectionReason = eddRejectionReason;
    }
}

package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.math.BigDecimal;

@MappedSuperclass
public abstract class CoverRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    /** The owning customer record. Customer scoping keys on this, never on the (non-unique) name (spec 04 §6). */
    @Column(name = "customer_ref", length = 20)
    private String customerRef;

    @Column(nullable = false, length = 100)
    private String product;

    /** Sum assured or claim amount, in pula and thebe (NS-DAT-003). */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Monthly premium in pula and thebe; on a rate-table plan it is the sum of the covered lives' rates. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal premium;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "record_date", nullable = false, length = 20)
    private String recordDate;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getPremium() {
        return premium;
    }

    public void setPremium(BigDecimal premium) {
        this.premium = premium;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRecordDate() {
        return recordDate;
    }

    public void setRecordDate(String recordDate) {
        this.recordDate = recordDate;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}

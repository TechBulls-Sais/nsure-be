package com.nsure.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A subledger account. The chart is Finance-owned configuration (decision D07), seeded for demonstration. */
@Entity
@Table(name = "fin_account")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "account_type", nullable = false, length = 20)
    private String accountType;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected LedgerAccount() {
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getAccountType() {
        return accountType;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}

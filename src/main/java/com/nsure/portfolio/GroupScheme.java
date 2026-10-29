package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A group scheme (GL01). The members and premium columns are a denormalised snapshot that com.nsure.group keeps in
 * step whenever membership or cover changes, so the portfolio dashboard stays coherent with live membership.
 */
@Entity
@Table(name = "ins_scheme")
public class GroupScheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 80)
    private String sector;

    @Column(nullable = false, length = 8)
    private String initials;

    @Column(nullable = false)
    private int members;

    @Column(nullable = false)
    private int premium;

    @Column(nullable = false, length = 20)
    private String renewal;

    @Column(nullable = false, length = 20)
    private String color;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "inception_date", nullable = false)
    private LocalDate inceptionDate;

    @Column(name = "renewal_date", nullable = false)
    private LocalDate renewalDate;

    @Column(name = "billing_frequency", nullable = false, length = 20)
    private String billingFrequency;

    @Column(name = "scheme_status", nullable = false, length = 20)
    private String status;

    protected GroupScheme() {
    }

    public GroupScheme(String ref, String name, String sector, String initials, String color, int displayOrder,
            LocalDate inceptionDate, LocalDate renewalDate, String renewal, String billingFrequency) {
        this.ref = ref;
        this.name = name;
        this.sector = sector;
        this.initials = initials;
        this.color = color;
        this.displayOrder = displayOrder;
        this.inceptionDate = inceptionDate;
        this.renewalDate = renewalDate;
        this.renewal = renewal;
        this.billingFrequency = billingFrequency;
        this.status = "ACTIVE";
    }

    /** Refreshes the dashboard snapshot from live membership: active members and whole-pula monthly premium. */
    public void syncTotals(int members, int premium) {
        this.members = members;
        this.premium = premium;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getInceptionDate() {
        return inceptionDate;
    }

    public LocalDate getRenewalDate() {
        return renewalDate;
    }

    public String getBillingFrequency() {
        return billingFrequency;
    }

    public String getStatus() {
        return status;
    }

    public String getRef() {
        return ref;
    }

    public String getName() {
        return name;
    }

    public String getSector() {
        return sector;
    }

    public String getInitials() {
        return initials;
    }

    public int getMembers() {
        return members;
    }

    public int getPremium() {
        return premium;
    }

    public String getRenewal() {
        return renewal;
    }

    public String getColor() {
        return color;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}

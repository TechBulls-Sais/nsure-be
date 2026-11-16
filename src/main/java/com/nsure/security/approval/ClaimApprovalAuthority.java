package com.nsure.security.approval;

import com.nsure.security.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** The most a role may approve on one claim payout (spec 04 §5.3). No row for a role means unlimited. */
@Entity
@Table(name = "claim_approval_authority")
public class ClaimApprovalAuthority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false, unique = true)
    private Role role;

    @Column(name = "max_payout_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal maxPayoutAmount;

    protected ClaimApprovalAuthority() {
    }

    public ClaimApprovalAuthority(Role role, BigDecimal maxPayoutAmount) {
        this.role = role;
        this.maxPayoutAmount = maxPayoutAmount;
    }

    public Role getRole() {
        return role;
    }

    public BigDecimal getMaxPayoutAmount() {
        return maxPayoutAmount;
    }

    public void setMaxPayoutAmount(BigDecimal maxPayoutAmount) {
        this.maxPayoutAmount = maxPayoutAmount;
    }
}

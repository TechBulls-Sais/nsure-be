package com.nsure.security.capability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "capability_grant",
        uniqueConstraints = @UniqueConstraint(name = "uk_capability_grant", columnNames = {"role_id", "capability"}))
public class CapabilityGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private NsureRole role;

    @Column(nullable = false, length = 64)
    private String capability;

    public String getCapability() {
        return capability;
    }
}

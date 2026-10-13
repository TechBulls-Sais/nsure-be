package com.nsure.mipas.capability;

import com.nsure.security.domain.Role;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "mipas_capability_grant")
public class CapabilityGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "capability_id", nullable = false)
    private MipasCapability capability;

    protected CapabilityGrant() {
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public MipasCapability getCapability() {
        return capability;
    }
}

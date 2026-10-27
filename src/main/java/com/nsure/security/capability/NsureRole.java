package com.nsure.security.capability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "nsure_role")
public class NsureRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    @Column(name = "desk_access", nullable = false)
    private boolean deskAccess;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, length = 400)
    private String purpose;

    @OneToMany(mappedBy = "role", fetch = FetchType.EAGER)
    private Set<CapabilityGrant> grants = new HashSet<>();

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isDeskAccess() {
        return deskAccess;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPurpose() {
        return purpose;
    }

    public Set<CapabilityGrant> getGrants() {
        return grants;
    }
}

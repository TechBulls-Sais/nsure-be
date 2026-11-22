package com.nsure.group;

import com.nsure.portfolio.GroupScheme;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** A scheme's census terms (NS-GL04-002): atomic batches required, and a second person to commit. */
@Entity
@Table(name = "grp_census_terms")
public class CensusTerms {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @Column(name = "atomic_required", nullable = false)
    private int atomicRequired;

    @Column(name = "four_eyes", nullable = false)
    private int fourEyes;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected CensusTerms() {
    }

    CensusTerms(GroupScheme scheme) {
        this.scheme = scheme;
    }

    void set(boolean atomic, boolean fourEyes, String actor) {
        this.atomicRequired = atomic ? 1 : 0;
        this.fourEyes = fourEyes ? 1 : 0;
        this.updatedBy = actor;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isAtomicRequired() {
        return atomicRequired == 1;
    }

    public boolean isFourEyes() {
        return fourEyes == 1;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

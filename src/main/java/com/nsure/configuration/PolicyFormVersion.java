package com.nsure.configuration;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The extra policy fields one product asks for under one release. It gets a version number when its release is
 * activated; the previous active form of that product is retired, but policies issued under it keep pointing at it.
 */
@Entity
@Table(name = "policy_form_version")
public class PolicyFormVersion {

    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "config_release_id", nullable = false)
    private ConfigRelease release;

    @Column(nullable = false, length = 100)
    private String product;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "form_status", nullable = false, length = 20)
    private String status;

    @Column(name = "definition_checksum", length = 64)
    private String definitionChecksum;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    @OneToMany(mappedBy = "form", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder asc")
    private List<PolicyFieldDefinition> fields = new ArrayList<>();

    protected PolicyFormVersion() {
    }

    PolicyFormVersion(ConfigRelease release, String product) {
        this.release = release;
        this.product = product;
        this.status = DRAFT;
    }

    void activate(int versionNo, String checksum) {
        this.versionNo = versionNo;
        this.definitionChecksum = checksum;
        this.status = ACTIVE;
        this.activatedAt = LocalDateTime.now();
    }

    void retire() {
        this.status = RETIRED;
        this.retiredAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public ConfigRelease getRelease() {
        return release;
    }

    public String getProduct() {
        return product;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public String getStatus() {
        return status;
    }

    public String getDefinitionChecksum() {
        return definitionChecksum;
    }

    public LocalDateTime getActivatedAt() {
        return activatedAt;
    }

    public LocalDateTime getRetiredAt() {
        return retiredAt;
    }

    public List<PolicyFieldDefinition> getFields() {
        return fields;
    }
}

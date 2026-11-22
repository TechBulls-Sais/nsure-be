package com.nsure.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * The employer or sponsor behind a scheme (NS-GL01-001/003): a prospect while it is being quoted, a client once a
 * master policy is activated for it. Quotations and schemes refer to it by reference, so the legal entity and its
 * registration are captured once.
 */
@Entity
@Table(name = "grp_sponsor")
public class GroupSponsor {

    public static final String PROSPECT = "PROSPECT";
    public static final String CLIENT = "CLIENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, length = 20)
    private String ref;

    @Column(name = "legal_name", nullable = false, length = 150)
    private String legalName;

    @Column(name = "registration_no", length = 40)
    private String registrationNo;

    @Column(nullable = false, length = 80)
    private String sector;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(name = "contact_email", length = 150)
    private String contactEmail;

    @Column(name = "sponsor_status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected GroupSponsor() {
    }

    public GroupSponsor(String ref, String legalName, String registrationNo, String sector, String contactName,
            String contactEmail, String createdBy) {
        this.ref = ref;
        this.legalName = legalName;
        this.registrationNo = registrationNo;
        this.sector = sector;
        this.contactName = contactName;
        this.contactEmail = contactEmail;
        this.status = PROSPECT;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    /** The sponsor has a master policy in force. */
    public void becameClient() {
        this.status = CLIENT;
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getRegistrationNo() {
        return registrationNo;
    }

    public String getSector() {
        return sector;
    }

    public String getContactName() {
        return contactName;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

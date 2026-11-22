package com.nsure.portfolio;

import com.nsure.rating.RateTableService;
import com.nsure.reference.RelationshipType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One covered life on a rate-table-priced application or policy (spec 03 R-16): why the record costs what it
 * costs, per life, as rated at entry. Exactly one of application / policy is set.
 */
@Entity
@Table(name = "ins_policy_life_cover")
public class PolicyLifeCover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id")
    private InsuranceApplication application;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id")
    private InsurancePolicy policy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "relationship_type_id", nullable = false)
    private RelationshipType relationship;

    /** Snapshot of the relationship name, so the row reads the same if the master data is renamed. */
    @Column(name = "life_type", nullable = false, length = 80)
    private String lifeType;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "age_at_entry", nullable = false)
    private int ageAtEntry;

    @Column(name = "cover_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal coverAmount;

    @Column(name = "monthly_premium", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyPremium;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** ACTIVE or CEASED (NS-IL04-001): a removed life stays on the record with its history. */
    @Column(name = "life_status", nullable = false, length = 20)
    private String lifeStatus = "ACTIVE";

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** Waiting periods run from here; a life removed and added again keeps its original date (NS-IL04-003). */
    @Column(name = "waiting_period_start")
    private LocalDate waitingPeriodStart;

    @Column(name = "cease_reason", length = 300)
    private String ceaseReason;

    /** The person's first cover row on the policy when re-added: limits and claims continue from it (NS-IL04-004). */
    @Column(name = "continuity_of")
    private Long continuityOf;

    /** How the date of birth was evidenced, when recorded (NS-IL04-001). */
    @Column(name = "dob_evidence", length = 150)
    private String dobEvidence;

    protected PolicyLifeCover() {
    }

    /** A life added to an in-force policy by endorsement. */
    public PolicyLifeCover(InsurancePolicy policy, RateTableService.PricedLife line, int displayOrder,
            LocalDate effectiveFrom, LocalDate waitingPeriodStart) {
        this(policy, line, displayOrder);
        this.effectiveFrom = effectiveFrom;
        this.waitingPeriodStart = waitingPeriodStart;
    }

    public boolean isActiveLife() {
        return "ACTIVE".equals(lifeStatus);
    }

    public Long getContinuityOf() {
        return continuityOf;
    }

    public void setContinuityOf(Long continuityOf) {
        this.continuityOf = continuityOf;
    }

    /** The person this row covers, for limits and consumption: the first row of a re-added person, else this one. */
    public Long getPersonId() {
        return continuityOf != null ? continuityOf : id;
    }

    public String getDobEvidence() {
        return dobEvidence;
    }

    public void setDobEvidence(String dobEvidence) {
        this.dobEvidence = dobEvidence;
    }

    /** Whether this life was covered on {@code day}: started by then (when dated) and not ended before it. */
    public boolean coveredOn(LocalDate day) {
        return (effectiveFrom == null || !day.isBefore(effectiveFrom)) && (effectiveTo == null || day.isBefore(effectiveTo));
    }

    /** Dates a life captured at issue from the policy's cover start, once cover starts (NS-IL04-001). */
    public void startWithPolicy(LocalDate coverStart) {
        if (effectiveFrom == null) {
            effectiveFrom = coverStart;
        }
        if (waitingPeriodStart == null) {
            waitingPeriodStart = coverStart;
        }
    }

    public String getLifeStatus() {
        return lifeStatus;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public LocalDate getWaitingPeriodStart() {
        return waitingPeriodStart;
    }

    public String getCeaseReason() {
        return ceaseReason;
    }

    public RelationshipType getRelationship() {
        return relationship;
    }

    /** Ends this life's cover; the row and everything claimed under it stay (NS-IL04-004). */
    public void cease(LocalDate on, String reason) {
        this.lifeStatus = "CEASED";
        this.effectiveTo = on;
        this.ceaseReason = reason;
    }

    public void setMonthlyPremium(BigDecimal monthlyPremium) {
        this.monthlyPremium = monthlyPremium;
    }

    PolicyLifeCover(CoverRecord record, RateTableService.PricedLife line, int displayOrder) {
        if (record instanceof InsurancePolicy owner) {
            this.policy = owner;
        } else if (record instanceof InsuranceApplication owner) {
            this.application = owner;
        } else {
            throw new IllegalArgumentException("Lives are covered only on applications and policies.");
        }
        this.relationship = line.relationship();
        this.lifeType = line.relationship().getRelationshipName();
        this.fullName = line.fullName();
        this.dateOfBirth = line.dateOfBirth();
        this.ageAtEntry = line.age();
        this.coverAmount = line.coverAmount();
        this.monthlyPremium = line.monthlyPremium();
        this.displayOrder = displayOrder;
    }

    public InsuranceApplication getApplication() {
        return application;
    }

    public InsurancePolicy getPolicy() {
        return policy;
    }

    public String getLifeType() {
        return lifeType;
    }

    public boolean isMainInsured() {
        return relationship.isSelfRelationship();
    }

    public String getFullName() {
        return fullName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public int getAgeAtEntry() {
        return ageAtEntry;
    }

    public BigDecimal getCoverAmount() {
        return coverAmount;
    }

    public BigDecimal getMonthlyPremium() {
        return monthlyPremium;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public Long getId() {
        return id;
    }
}

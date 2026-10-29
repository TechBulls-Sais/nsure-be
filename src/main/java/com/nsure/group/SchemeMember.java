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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * One member cover episode on a scheme (GL03/GL04): from the join date to the exit date. Cover is split into the
 * accepted (authorised) amount and the amount pending underwriting, so a certificate or invoice never uses more
 * than the authorised cover (AT-GL03-01). A member who leaves and rejoins gets a new episode.
 */
@Entity
@Table(name = "grp_member")
public class SchemeMember {

    public static final String ACTIVE = "ACTIVE";
    public static final String UNDERWRITING_PENDING = "UNDERWRITING_PENDING";
    public static final String PARTIALLY_ACCEPTED = "PARTIALLY_ACCEPTED";
    public static final String DECLINED = "DECLINED";
    public static final String CEASED = "CEASED";

    /** Statuses with a live episode: the member can leave or change salary, and counts as a scheme member. */
    public static final Set<String> ON_COVER = Set.of(ACTIVE, UNDERWRITING_PENDING, PARTIALLY_ACCEPTED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private BenefitCategory category;

    @Column(name = "member_key", nullable = false, length = 40)
    private String memberKey;

    @Column(name = "first_name", nullable = false, length = 60)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 60)
    private String lastName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(precision = 15, scale = 2)
    private BigDecimal salary;

    @Column(name = "member_status", nullable = false, length = 30)
    private String status;

    @Column(name = "join_date", nullable = false)
    private LocalDate joinDate;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "requested_cover", nullable = false, precision = 15, scale = 2)
    private BigDecimal requestedCover;

    @Column(name = "accepted_cover", nullable = false, precision = 15, scale = 2)
    private BigDecimal acceptedCover;

    @Column(name = "pending_cover", nullable = false, precision = 15, scale = 2)
    private BigDecimal pendingCover;

    /** Cover an underwriter authorised above the free-cover limit; later salary changes may use up to this. */
    @Column(name = "underwritten_cover", precision = 15, scale = 2)
    private BigDecimal underwrittenCover;

    @Column(name = "uw_decision", length = 20)
    private String underwritingDecision;

    @Column(name = "uw_reason", length = 500)
    private String underwritingReason;

    @Column(name = "uw_by", length = 100)
    private String underwrittenBy;

    @Column(name = "uw_at")
    private LocalDateTime underwrittenAt;

    @Column(name = "source_batch_ref", length = 20)
    private String sourceBatchRef;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SchemeMember() {
    }

    SchemeMember(GroupScheme scheme, BenefitCategory category, String memberKey, String firstName, String lastName,
            LocalDate dateOfBirth, BigDecimal salary, LocalDate joinDate, String sourceBatchRef) {
        this.scheme = scheme;
        this.category = category;
        this.memberKey = memberKey;
        this.firstName = firstName;
        this.lastName = lastName;
        this.dateOfBirth = dateOfBirth;
        this.salary = salary;
        this.joinDate = joinDate;
        this.sourceBatchRef = sourceBatchRef;
        this.createdAt = LocalDateTime.now();
        applyCover(category.coverFor(salary), category.getFreeCoverLimit());
    }

    /** Accepts cover up to the ceiling (free-cover limit or underwritten amount); the excess stays pending. */
    private void applyCover(BigDecimal requested, BigDecimal ceiling) {
        this.requestedCover = requested;
        this.acceptedCover = requested.min(ceiling);
        this.pendingCover = requested.subtract(acceptedCover);
        this.status = pendingCover.signum() > 0 ? UNDERWRITING_PENDING : ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    /** A salary change recalculates cover from the category formula; an increase above the ceiling is pending. */
    void changeSalary(BigDecimal newSalary) {
        this.salary = newSalary;
        BigDecimal ceiling = category.getFreeCoverLimit();
        if (underwrittenCover != null && underwrittenCover.compareTo(ceiling) > 0) {
            ceiling = underwrittenCover;
        }
        applyCover(category.coverFor(newSalary), ceiling);
    }

    void cease(LocalDate exitDate) {
        this.exitDate = exitDate;
        this.pendingCover = BigDecimal.ZERO.setScale(2);
        this.status = CEASED;
        this.updatedAt = LocalDateTime.now();
    }

    /** Underwriter accepts the pending cover in full or in part (never more than requested). */
    void accept(BigDecimal amount, String reason, String actor) {
        this.underwrittenCover = amount;
        this.acceptedCover = amount;
        this.pendingCover = BigDecimal.ZERO.setScale(2);
        this.status = amount.compareTo(requestedCover) >= 0 ? ACTIVE : PARTIALLY_ACCEPTED;
        decided("ACCEPT", reason, actor);
    }

    /** Underwriter declines the pending excess; the free-cover portion stays in force. */
    void decline(String reason, String actor) {
        this.pendingCover = BigDecimal.ZERO.setScale(2);
        this.status = acceptedCover.signum() > 0 ? PARTIALLY_ACCEPTED : DECLINED;
        decided("DECLINE", reason, actor);
    }

    private void decided(String decision, String reason, String actor) {
        this.underwritingDecision = decision;
        this.underwritingReason = reason;
        this.underwrittenBy = actor;
        this.underwrittenAt = LocalDateTime.now();
        this.updatedAt = underwrittenAt;
    }

    boolean onCover() {
        return ON_COVER.contains(status);
    }

    String fullName() {
        return firstName + " " + lastName;
    }

    public Long getId() {
        return id;
    }

    public GroupScheme getScheme() {
        return scheme;
    }

    public BenefitCategory getCategory() {
        return category;
    }

    public String getMemberKey() {
        return memberKey;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getJoinDate() {
        return joinDate;
    }

    public LocalDate getExitDate() {
        return exitDate;
    }

    public BigDecimal getRequestedCover() {
        return requestedCover;
    }

    public BigDecimal getAcceptedCover() {
        return acceptedCover;
    }

    public BigDecimal getPendingCover() {
        return pendingCover;
    }

    public BigDecimal getUnderwrittenCover() {
        return underwrittenCover;
    }

    public String getUnderwritingDecision() {
        return underwritingDecision;
    }

    public String getUnderwritingReason() {
        return underwritingReason;
    }

    public String getUnderwrittenBy() {
        return underwrittenBy;
    }

    public LocalDateTime getUnderwrittenAt() {
        return underwrittenAt;
    }

    public String getSourceBatchRef() {
        return sourceBatchRef;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

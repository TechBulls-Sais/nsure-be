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
import jakarta.persistence.Version;
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
    /** Cover suspended with the scheme (NS-GL01-006): not covered, billed or certified until reactivated. */
    public static final String SUSPENDED = "SUSPENDED";
    /**
     * Not actively at work on entry under a scheme whose rule is DEFER (AT-GL03-02): no cover, not billed or certified,
     * until the employer confirms the member is back at work.
     */
    public static final String COVER_DEFERRED = "COVER_DEFERRED";

    /** Statuses with a live episode: the member can leave or change salary, and counts as a scheme member. */
    public static final Set<String> ON_COVER = Set.of(ACTIVE, UNDERWRITING_PENDING, PARTIALLY_ACCEPTED, COVER_DEFERRED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking (NS-TXN-001): a write based on a stale copy is refused with 409, never overwrites.
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

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

    /** The actively-at-work declaration given on entry or the latest increase; null when none was given. */
    @Column(name = "actively_at_work")
    private Boolean activelyAtWork;

    /** The status the member returns to when a suspended scheme is reactivated. */
    @Column(name = "status_before_suspension", length = 30)
    private String statusBeforeSuspension;

    /** The latest suspension: first suspended day, and last suspended day once reactivated (open while suspended). */
    @Column(name = "suspended_from")
    private LocalDate suspendedFrom;

    @Column(name = "suspended_until")
    private LocalDate suspendedUntil;

    /** Premium loading on the member's accepted cover from an underwriting decision (NS-GL03-004); null: none. */
    @Column(name = "loading_percent", precision = 7, scale = 2)
    private BigDecimal loadingPercent;

    /** Exclusions an underwriting decision applies to the member's cover. */
    @Column(name = "uw_exclusions", length = 500)
    private String exclusions;

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
        // AT-GL02-02: the category formula in force on the join date, not one amended since.
        applyCover(category.coverFor(salary, joinDate), category.getFreeCoverLimit());
    }

    /**
     * A joiner whose free cover is withheld (not actively at work, or a late entrant): all requested cover is referred
     * to underwriting and none is in force until an underwriter accepts it (AT-GL03-02).
     */
    SchemeMember(GroupScheme scheme, BenefitCategory category, String memberKey, String firstName, String lastName,
            LocalDate dateOfBirth, BigDecimal salary, LocalDate joinDate, String sourceBatchRef, Boolean activelyAtWork,
            boolean withholdFreeCover) {
        this(scheme, category, memberKey, firstName, lastName, dateOfBirth, salary, joinDate, sourceBatchRef);
        this.activelyAtWork = activelyAtWork;
        if (Boolean.FALSE.equals(activelyAtWork) && "DEFER".equals(scheme.getAawRule())) {
            // AT-GL03-02, deferred-cover path: nothing in force or pending until the member is back at work.
            applyCover(category.coverFor(salary, joinDate), BigDecimal.ZERO.setScale(2));
            this.pendingCover = BigDecimal.ZERO.setScale(2);
            this.status = COVER_DEFERRED;
        } else if (withholdFreeCover) {
            applyCover(category.coverFor(salary, joinDate), BigDecimal.ZERO.setScale(2));
        } else if (!scheme.isPartialCoverAllowed() && pendingCover.signum() > 0) {
            // NS-GL03-002: where the scheme does not permit partial cover, none is admitted until all is underwritten.
            applyCover(requestedCover, BigDecimal.ZERO.setScale(2));
        }
    }

    /**
     * The employer confirms a deferred member is actively at work (AT-GL03-02): cover starts with the free-cover
     * treatment the member would have had on entry; anything above it is referred to underwriting.
     */
    void backAtWork() {
        if (!COVER_DEFERRED.equals(status)) {
            throw new IllegalArgumentException("Member " + memberKey + " has no cover deferred for not being at work");
        }
        this.activelyAtWork = Boolean.TRUE;
        BigDecimal ceiling = scheme.isPartialCoverAllowed() || requestedCover.compareTo(category.getFreeCoverLimit()) <= 0
                ? category.getFreeCoverLimit() : BigDecimal.ZERO.setScale(2);
        applyCover(requestedCover, ceiling);
    }

    /**
     * A category transfer (NS-GL03-005) keeps the cover already accepted on the previous episode, with its loading and
     * exclusions, up to the new category's formula (the demo scheme rule); only what is above it is referred.
     */
    void carryOver(SchemeMember previous) {
        this.activelyAtWork = previous.activelyAtWork;
        BigDecimal kept = previous.acceptedCover.min(requestedCover);
        if (kept.compareTo(acceptedCover) > 0) {
            this.underwrittenCover = kept;
            applyCover(requestedCover, kept);
        }
        this.loadingPercent = previous.loadingPercent;
        this.exclusions = previous.exclusions;
    }

    /** Moves the member's accepted cover onto the decision's terms: amount, loading and exclusions (NS-GL03-004). */
    void accept(BigDecimal amount, BigDecimal loading, String exclusions, String reason, String actor) {
        accept(amount, reason, actor);
        this.loadingPercent = loading == null || loading.signum() == 0 ? null : loading;
        this.exclusions = exclusions;
    }

    /** The monthly rate per 1,000 the member's cover is billed at: the category's, plus any underwriting loading. */
    public BigDecimal ratePerMille() {
        BigDecimal rate = category.getRatePerMille();
        return loadingPercent == null ? rate : rate.multiply(BigDecimal.ONE.add(loadingPercent
                .divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP))).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** A full month's premium on the accepted cover at the member's rate. */
    BigDecimal monthlyPremium() {
        return acceptedCover.multiply(ratePerMille()).divide(BigDecimal.valueOf(1000), 2, java.math.RoundingMode.HALF_UP);
    }

    /** A critical-illness payment that accelerates the life cover reduces it by the amount paid (section 08). */
    void accelerate(BigDecimal amount) {
        this.acceptedCover = acceptedCover.subtract(amount).max(BigDecimal.ZERO).setScale(2);
        this.updatedAt = LocalDateTime.now();
    }

    /** Restores accelerated cover under the approved reinstatement rule, never above the requested cover. */
    void reinstate(BigDecimal amount) {
        this.acceptedCover = acceptedCover.add(amount).min(requestedCover).setScale(2);
        this.updatedAt = LocalDateTime.now();
    }

    public BigDecimal getLoadingPercent() {
        return loadingPercent;
    }

    public String getExclusions() {
        return exclusions;
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
        changeSalary(newSalary, null);
    }

    /**
     * A salary change with the actively-at-work declaration at the increase date: a member not actively at work keeps
     * the cover already in force and any increase is referred to underwriting (NS-GL03-001).
     */
    void changeSalary(BigDecimal newSalary, Boolean activelyAtWorkNow) {
        changeSalary(newSalary, activelyAtWorkNow, null);
    }

    /**
     * A salary change effective on a date (AT-GL02-02): cover is recalculated with the category formula version in
     * force on that date, so a later amendment does not rewrite it.
     */
    void changeSalary(BigDecimal newSalary, Boolean activelyAtWorkNow, LocalDate effective) {
        this.salary = newSalary;
        if (COVER_DEFERRED.equals(status)) {
            // Still deferred: the cover it will start with follows the salary, nothing is in force yet.
            this.requestedCover = category.coverFor(newSalary, effective);
            this.updatedAt = LocalDateTime.now();
            return;
        }
        BigDecimal ceiling = category.getFreeCoverLimit();
        if (underwrittenCover != null && underwrittenCover.compareTo(ceiling) > 0) {
            ceiling = underwrittenCover;
        }
        if (Boolean.FALSE.equals(activelyAtWorkNow)) {
            ceiling = ceiling.min(acceptedCover);
        }
        if (activelyAtWorkNow != null) {
            this.activelyAtWork = activelyAtWorkNow;
        }
        applyCover(category.coverFor(newSalary, effective), ceiling);
    }

    public Boolean getActivelyAtWork() {
        return activelyAtWork;
    }

    /** Suspends cover from the given day with the scheme; the status before it is kept for reactivation. */
    void suspend(LocalDate from) {
        this.statusBeforeSuspension = status;
        this.status = SUSPENDED;
        this.suspendedFrom = from;
        this.suspendedUntil = null;
        this.updatedAt = LocalDateTime.now();
    }

    /** Cover resumes on the given day, in the status the member had before the suspension. */
    void reactivate(LocalDate on) {
        this.status = statusBeforeSuspension == null ? ACTIVE : statusBeforeSuspension;
        this.statusBeforeSuspension = null;
        LocalDate last = on.minusDays(1);
        this.suspendedUntil = suspendedFrom != null && last.isBefore(suspendedFrom) ? suspendedFrom.minusDays(1) : last;
        this.updatedAt = LocalDateTime.now();
    }

    /** How many days of [from, to] fall in the member's latest suspension (no cover and no premium on them). */
    int suspendedDays(LocalDate from, LocalDate to) {
        if (suspendedFrom == null) {
            return 0;
        }
        LocalDate start = suspendedFrom.isAfter(from) ? suspendedFrom : from;
        LocalDate end = suspendedUntil == null || suspendedUntil.isAfter(to) ? to : suspendedUntil;
        return end.isBefore(start) ? 0 : (int) java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
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

    public LocalDate getSuspendedFrom() {
        return suspendedFrom;
    }

    public LocalDate getSuspendedUntil() {
        return suspendedUntil;
    }

    /** Whether the member's episode covered the given day (joined, not yet exited, not suspended on it). */
    public boolean coveredOn(LocalDate day) {
        return !day.isBefore(joinDate) && (exitDate == null || !day.isAfter(exitDate)) && suspendedDays(day, day) == 0
                && !DECLINED.equals(status);
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

    public long getRowVersion() {
        return rowVersion;
    }
}

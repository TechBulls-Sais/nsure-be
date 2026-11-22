package com.nsure.rating;

import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.ControlKey;
import com.nsure.controls.ControlService;
import com.nsure.portfolio.CoverTier;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PolicyLifeCover;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate version workflow (spec 11): submit, approve with four-eyes and notice (E-24…E-26), reject, cancel, scheduled
 * publish, readable diff, in-force impact preview (E-20, E-21), CSV export and import with a dry-run diff (E-17…E-19),
 * across-the-board scaling (E-10), and partial cell patches with an optimistic-concurrency token (E-30).
 */
@Service
public class RateVersionService {

    private static final String CSV_HEADER =
            "product_code,plan,life_type,age_from,age_to,cover_amount,monthly_premium";

    private final RateTableService rates;
    private final ControlService controls;
    private final PlatformSecurityContext security;
    private final EntityManager em;

    /** NS-SH03-006: golden cases of product configurations priced from a table gate its publication. */
    private final org.springframework.beans.factory.ObjectProvider<com.nsure.portfolio.ProductRelease> releases;

    public RateVersionService(RateTableService rates, ControlService controls, PlatformSecurityContext security,
            EntityManager em, org.springframework.beans.factory.ObjectProvider<com.nsure.portfolio.ProductRelease> releases) {
        this.releases = releases;
        this.rates = rates;
        this.controls = controls;
        this.security = security;
        this.em = em;
    }

    // ── workflow ────────────────────────────────────────────────────────────────────────────────────────

    /** DRAFT → PENDING_APPROVAL (rate.write). */
    @Transactional
    public RateTableViews.Detail submit(String ref, int versionNo) {
        AppUser actor = actor("UPDATE_RATE_TABLE");
        RateTable table = rates.table(ref);
        RateTableVersion version = version(table, versionNo);
        if (!RateTableVersion.DRAFT.equals(version.getStatus())) {
            throw new IllegalArgumentException("Only a draft can be submitted; " + version.getLabel() + " is "
                    + version.getStatus() + ".");
        }
        if (rates.items(version).isEmpty()) {
            throw new IllegalArgumentException(version.getLabel() + " has no rates to submit.");
        }
        version.submit(actor.getUsername());
        return rates.detail(table, rates.versions(table), version);
    }

    /**
     * PENDING_APPROVAL → SCHEDULED, or PUBLISHED when effective today (rate.publish). Four-eyes: the author may not
     * publish their own version unless the control is off or they hold the break-glass PUBLISH_OWN_RATE_TABLE and give
     * a reason (E-24). V9 band completeness is an error here (E-22). A re-rate needs notice: effective from no earlier
     * than notice issued + rate.notice_days, or an override reason (E-26, Tap Cover TC-29).
     */
    @Transactional
    public RateTableViews.Detail approve(String ref, int versionNo, LocalDate effectiveFrom, LocalDate noticeIssuedAt,
            String overrideReason) {
        AppUser actor = actor("PUBLISH_RATE_TABLE");
        RateTable table = rates.table(ref);
        RateTableVersion version = version(table, versionNo);
        if (!RateTableVersion.PENDING_APPROVAL.equals(version.getStatus())) {
            throw new IllegalArgumentException("Only a submitted version can be approved. " + version.getLabel()
                    + " is " + version.getStatus() + ".");
        }
        String reason = overrideReason == null ? "" : overrideReason.trim();
        if (controls.isEnabled(ControlKey.RATE_FOUR_EYES.key())
                && actor.getUsername().equalsIgnoreCase(version.getCreatedBy())
                && !(actor.hasPermissionTo("PUBLISH_OWN_RATE_TABLE") && !reason.isEmpty())) {
            throw new NoAuthorizationException("You authored this rate version, so you cannot also publish it. "
                    + "A second approver must action it.");
        }
        List<String> gaps = rates.gaps(rates.items(version), rates.rules(table.getTier()));
        if (!gaps.isEmpty()) {
            throw new IllegalArgumentException("A version with gaps cannot be published:\n" + String.join("\n", gaps));
        }
        // NS-SH03-006: a rate change that alters validated golden results needs an approved baseline revision first.
        List<String> golden = releases.getObject().goldenFailures(table, version);
        if (!golden.isEmpty()) {
            throw new IllegalArgumentException(version.getLabel() + " changes validated golden results. Approve a"
                    + " revised baseline for each before publishing:\n" + String.join("\n", golden));
        }
        LocalDate today = LocalDate.now();
        LocalDate effective = effectiveFrom == null ? today : effectiveFrom;
        if (effective.isBefore(today)) {
            throw new IllegalArgumentException("The effective date cannot be in the past.");
        }
        boolean reRate = rates.currentVersion(table).isPresent();
        String override = null;
        if (reRate) {
            int days = controls.valueOf(ControlKey.RATE_NOTICE_DAYS.key()).intValue();
            boolean enough = noticeIssuedAt != null && !effective.isBefore(noticeIssuedAt.plusDays(days));
            if (!enough) {
                String message = "A re-rate needs " + days + " days' written notice: effective from " + effective
                        + (noticeIssuedAt == null ? " with no notice issued"
                                : " is before " + noticeIssuedAt.plusDays(days) + " (notice issued " + noticeIssuedAt + ")")
                        + ". Set a later date or give an override reason.";
                if (reason.isEmpty()) {
                    throw new ConflictException(message);
                }
                override = "Notice override: " + reason;
            }
        }
        if (!reason.isEmpty() && actor.getUsername().equalsIgnoreCase(version.getCreatedBy())) {
            override = (override == null ? "" : override + " · ") + "Self-publish (break-glass): " + reason;
        }
        version.approve(actor.getUsername(), effective, noticeIssuedAt, override);
        if (!effective.isAfter(today)) {
            publish(table, version, effective);
        }
        return rates.detail(table, rates.versions(table), version);
    }

    /** PENDING_APPROVAL → REJECTED, back to the author with a reason (rate.publish). */
    @Transactional
    public RateTableViews.Detail reject(String ref, int versionNo, String reason) {
        actor("PUBLISH_RATE_TABLE");
        RateTable table = rates.table(ref);
        RateTableVersion version = version(table, versionNo);
        if (!RateTableVersion.PENDING_APPROVAL.equals(version.getStatus())) {
            throw new IllegalArgumentException("Only a submitted version can be rejected.");
        }
        version.reject(required(reason, "a reason for rejecting"));
        return rates.detail(table, rates.versions(table), version);
    }

    /** SCHEDULED → REJECTED before it takes effect (rate.publish). */
    @Transactional
    public RateTableViews.Detail cancel(String ref, int versionNo, String reason) {
        actor("PUBLISH_RATE_TABLE");
        RateTable table = rates.table(ref);
        RateTableVersion version = version(table, versionNo);
        if (!RateTableVersion.SCHEDULED.equals(version.getStatus())) {
            throw new IllegalArgumentException("Only a scheduled version can be cancelled.");
        }
        version.reject("Cancelled before it took effect: " + required(reason, "a reason for cancelling"));
        return rates.detail(table, rates.versions(table), version);
    }

    /** Daily (E-25): publishes every SCHEDULED version whose effective date has arrived. */
    @Scheduled(cron = "${nsure.rating.publish-cron:0 5 0 * * *}")
    @Transactional
    public List<String> publishDue() {
        List<String> published = new ArrayList<>();
        for (RateTableVersion version : em.createQuery("select v from RateTableVersion v where v.status = :status"
                        + " and v.effectiveFrom <= :today", RateTableVersion.class)
                .setParameter("status", RateTableVersion.SCHEDULED)
                .setParameter("today", LocalDate.now())
                .getResultList()) {
            publish(version.getRateTable(), version, version.getEffectiveFrom());
            published.add(version.getLabel());
        }
        return published;
    }

    private void publish(RateTable table, RateTableVersion version, LocalDate on) {
        rates.currentVersion(table).filter(live -> live != version).ifPresent(live -> live.close(on));
        em.flush();
        version.publish(on);
    }

    // ── analysis ────────────────────────────────────────────────────────────────────────────────────────

    /** Structured and readable diff between two versions (spec 11 §8, R-20). */
    @Transactional(readOnly = true)
    public Diff diff(String ref, int from, int to) {
        actor("READ_RATE_TABLE");
        RateTable table = rates.table(ref);
        return diff(rows(rates.items(version(table, from))), rows(rates.items(version(table, to))));
    }

    /**
     * In-force book impact (E-20): every active policy on the plan re-priced through the live pricing function against
     * the chosen version (E-21), compared with what it pays now.
     */
    @Transactional(readOnly = true)
    public Impact impact(String ref, int versionNo) {
        actor("READ_RATE_TABLE");
        RateTable table = rates.table(ref);
        RateTableVersion version = version(table, versionNo);
        CoverTier tier = table.getTier();
        List<InsurancePolicy> book = em.createQuery("select p from InsurancePolicy p where p.product = :product"
                        + " and p.tier = :tier and p.status in ('Active', 'Active - No Cover')", InsurancePolicy.class)
                .setParameter("product", table.getProduct().getName())
                .setParameter("tier", tier.getName())
                .getResultList();
        String self = em.createQuery(
                        "select r.relationshipName from RelationshipType r where r.selfRelationship = true", String.class)
                .setMaxResults(1).getResultStream().findFirst().orElse("Main Insured");
        BigDecimal current = BigDecimal.ZERO;
        BigDecimal projected = BigDecimal.ZERO;
        long over10 = 0;
        List<String> unrateable = new ArrayList<>();
        for (InsurancePolicy policy : book) {
            List<RateTableService.LifeToPrice> lives = new ArrayList<>();
            List<LocalDate> birth = policy.getCustomerRef() == null ? List.of() : em.createQuery(
                            "select c.dateOfBirth from CustomerAccount c where c.ref = :ref", LocalDate.class)
                    .setParameter("ref", policy.getCustomerRef()).getResultList();
            lives.add(new RateTableService.LifeToPrice(self, policy.getCustomerName(), birth.isEmpty() ? null : birth.get(0)));
            for (PolicyLifeCover life : em.createQuery("select l from PolicyLifeCover l where l.policy = :policy",
                    PolicyLifeCover.class).setParameter("policy", policy).getResultList()) {
                if (!self.equalsIgnoreCase(life.getLifeType())) {
                    lives.add(new RateTableService.LifeToPrice(life.getLifeType(), life.getFullName(), life.getDateOfBirth()));
                }
            }
            BigDecimal now = policy.getPremium() == null ? BigDecimal.ZERO : policy.getPremium();
            try {
                BigDecimal next = rates.priceWith(tier, lives, null, Optional.of(version))
                        .map(RateTableService.PolicyPrice::monthlyTotal).orElse(now);
                current = current.add(now);
                projected = projected.add(next);
                if (now.signum() > 0 && next.subtract(now).multiply(BigDecimal.TEN).compareTo(now) > 0) {
                    over10++;
                }
            } catch (IllegalArgumentException unpriceable) {
                current = current.add(now);
                unrateable.add(policy.getRef() + ": " + unpriceable.getMessage());
            }
        }
        BigDecimal movement = projected.subtract(current);
        return new Impact(version.getLabel(), book.size(), current.setScale(2, RoundingMode.HALF_UP),
                projected.setScale(2, RoundingMode.HALF_UP), movement.setScale(2, RoundingMode.HALF_UP),
                current.signum() == 0 ? BigDecimal.ZERO
                        : movement.multiply(BigDecimal.valueOf(100)).divide(current, 2, RoundingMode.HALF_UP),
                over10, unrateable, LocalDateTime.now().withNano(0));
    }

    // ── import / export / bulk edits ────────────────────────────────────────────────────────────────────

    /** The canonical flat format (spec 11 §4.3), which round-trips exactly. Default: the live version. */
    @Transactional(readOnly = true)
    public String export(String ref, Integer versionNo) {
        actor("READ_RATE_TABLE");
        RateTable table = rates.table(ref);
        RateTableVersion version = versionNo == null
                ? rates.currentVersion(table).orElseThrow(() -> new NotFoundException(ref + " has no published version"))
                : version(table, versionNo);
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        String code = table.getProduct().getName();
        for (RateTableItem item : rates.items(version)) {
            csv.append(csvCell(code)).append(',').append(csvCell(table.getTier().getName())).append(',')
                    .append(csvCell(item.getLifeType().getRelationshipName())).append(',')
                    .append(item.getAgeFrom()).append(',').append(item.getAgeTo()).append(',')
                    .append(item.getCoverAmount().stripTrailingZeros().toPlainString()).append(',')
                    .append(item.getMonthlyPremium().setScale(2, RoundingMode.HALF_UP).toPlainString()).append('\n');
        }
        return csv.toString();
    }

    /**
     * Upload → parse → validate → dry-run diff against the live version (E-18): nothing is written. Errors come back
     * with their line numbers; a clean dry run returns the token that confirms it.
     */
    @Transactional(readOnly = true)
    public ImportDryRun importDryRun(String ref, String csv) {
        actor("UPDATE_RATE_TABLE");
        RateTable table = rates.table(ref);
        List<String> issues = new ArrayList<>();
        List<RateTableViews.RowInput> rows = parse(csv, issues);
        if (issues.isEmpty()) {
            try {
                rates.validate(table, rates.rules(table.getTier()), rows);
            } catch (IllegalArgumentException invalid) {
                issues.addAll(List.of(invalid.getMessage().split("\n")));
            }
        }
        List<RateTableItem> live = rates.currentVersion(table).map(rates::items).orElse(List.of());
        Diff diff = diff(rows(live), inputRows(rows));
        return new ImportDryRun(diff.added(), diff.removed(), diff.changed(), diff.averageMovementPct(), diff.lines(),
                issues, issues.isEmpty() ? token(csv) : null);
    }

    /** Applies a clean dry run as a DRAFT (never live; E-18). */
    @Transactional
    public RateTableViews.Detail importConfirm(String ref, String csv, String dryRunToken) {
        actor("UPDATE_RATE_TABLE");
        if (dryRunToken == null || !dryRunToken.equals(token(csv))) {
            throw new ConflictException("The file changed since its dry run. Run the import again to see the new diff.");
        }
        List<String> issues = new ArrayList<>();
        List<RateTableViews.RowInput> rows = parse(csv, issues);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", issues));
        }
        return rates.saveDraft(ref, new RateTableViews.GridSave(null, rows), "IMPORT");
    }

    /** Applies a factor (e.g. 1.05) to every premium, or to one life type's, as a DRAFT (E-10). */
    @Transactional
    public RateTableViews.Detail scale(String ref, BigDecimal factor, String lifeType) {
        actor("UPDATE_RATE_TABLE");
        if (factor == null || factor.signum() <= 0 || factor.compareTo(BigDecimal.TEN) > 0) {
            throw new IllegalArgumentException("The factor must be above 0 and at most 10, for example 1.05.");
        }
        RateTable table = rates.table(ref);
        RateTableVersion base = rates.openDraft(table).or(() -> rates.currentVersion(table))
                .orElseThrow(() -> new NotFoundException(ref + " has no rates to scale"));
        List<RateTableViews.RowInput> rows = new ArrayList<>();
        for (RateTableItem item : rates.items(base)) {
            boolean hit = lifeType == null || lifeType.isBlank()
                    || item.getLifeType().getRelationshipName().equalsIgnoreCase(lifeType.trim());
            rows.add(new RateTableViews.RowInput(item.getLifeType().getRelationshipName(), item.getAgeFrom(),
                    item.getAgeTo(), item.getCoverAmount(), hit
                            ? item.getMonthlyPremium().multiply(factor).setScale(2, RoundingMode.HALF_UP)
                            : item.getMonthlyPremium()));
        }
        return rates.saveDraft(ref, new RateTableViews.GridSave(base.getVersionNo(), rows), "MANUAL");
    }

    /**
     * Partial cell update on a DRAFT (E-3, E-30). {@code baseUpdatedAt} is the version's updatedAt the editor loaded;
     * a stale token is a 409 so concurrent edits never silently clobber each other.
     */
    @Transactional
    public RateTableViews.Detail patchCells(String ref, int versionNo, String baseUpdatedAt,
            List<RateTableViews.RowInput> cells) {
        actor("UPDATE_RATE_TABLE");
        RateTable table = rates.table(ref);
        RateTableVersion version = version(table, versionNo);
        if (!version.isEditable()) {
            throw new IllegalArgumentException(version.getLabel() + " is " + version.getStatus()
                    + "; only a draft can be edited.");
        }
        String current = version.getUpdatedAt() == null ? "" : version.getUpdatedAt().toString();
        if (baseUpdatedAt == null || !baseUpdatedAt.equals(current)) {
            throw new ConflictException(version.getLabel() + " was changed by someone else since you loaded it. "
                    + "Reload to see the current grid.");
        }
        Map<String, RateTableViews.RowInput> rows = new LinkedHashMap<>();
        for (RateTableItem item : rates.items(version)) {
            rows.put(key(item.getLifeType().getRelationshipName(), item.getAgeFrom(), item.getAgeTo()),
                    new RateTableViews.RowInput(item.getLifeType().getRelationshipName(), item.getAgeFrom(),
                            item.getAgeTo(), item.getCoverAmount(), item.getMonthlyPremium()));
        }
        for (RateTableViews.RowInput cell : cells == null ? List.<RateTableViews.RowInput>of() : cells) {
            String key = key(cell.lifeType(), cell.ageFrom() == null ? -1 : cell.ageFrom(),
                    cell.ageTo() == null ? -1 : cell.ageTo());
            if (cell.monthlyPremium() == null) {
                rows.remove(key);
            } else {
                RateTableViews.RowInput old = rows.get(key);
                rows.put(key, new RateTableViews.RowInput(cell.lifeType(), cell.ageFrom(), cell.ageTo(),
                        cell.coverAmount() != null ? cell.coverAmount() : old == null ? null : old.coverAmount(),
                        cell.monthlyPremium()));
            }
        }
        return rates.saveDraft(ref, new RateTableViews.GridSave(versionNo, List.copyOf(rows.values())), "MANUAL");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────

    private record Cell(BigDecimal cover, BigDecimal premium) {
    }

    private static Map<String, Cell> rows(List<RateTableItem> items) {
        Map<String, Cell> rows = new LinkedHashMap<>();
        items.forEach(item -> rows.put(key(item.getLifeType().getRelationshipName(), item.getAgeFrom(), item.getAgeTo()),
                new Cell(item.getCoverAmount(), item.getMonthlyPremium())));
        return rows;
    }

    private static Map<String, Cell> inputRows(List<RateTableViews.RowInput> input) {
        Map<String, Cell> rows = new LinkedHashMap<>();
        input.forEach(row -> rows.put(key(row.lifeType(), row.ageFrom() == null ? -1 : row.ageFrom(),
                row.ageTo() == null ? -1 : row.ageTo()), new Cell(row.coverAmount(), row.monthlyPremium())));
        return rows;
    }

    /** E-19: "Main Insured 40–44: P12.95 → P13.40 (+3.5 %)", counts, and the average premium movement. */
    private static Diff diff(Map<String, Cell> before, Map<String, Cell> after) {
        List<String> lines = new ArrayList<>();
        long added = 0;
        long removed = 0;
        long changed = 0;
        BigDecimal totalMovement = BigDecimal.ZERO;
        long moved = 0;
        for (Map.Entry<String, Cell> entry : after.entrySet()) {
            Cell was = before.get(entry.getKey());
            Cell now = entry.getValue();
            String band = label(entry.getKey());
            if (was == null) {
                added++;
                lines.add("Added " + band + ": " + pula(now.premium()));
            } else if (now.premium() != null && was.premium().compareTo(now.premium()) != 0) {
                changed++;
                BigDecimal pct = was.premium().signum() == 0 ? BigDecimal.ZERO
                        : now.premium().subtract(was.premium()).multiply(BigDecimal.valueOf(100))
                                .divide(was.premium(), 1, RoundingMode.HALF_UP);
                totalMovement = totalMovement.add(pct);
                moved++;
                lines.add(band + ": " + pula(was.premium()) + " → " + pula(now.premium()) + " ("
                        + (pct.signum() >= 0 ? "+" : "") + pct.toPlainString() + " %)");
            } else if (now.cover() != null && was.cover() != null && was.cover().compareTo(now.cover()) != 0) {
                changed++;
                lines.add(band + " cover: " + pula(was.cover()) + " → " + pula(now.cover()));
            }
        }
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                removed++;
                lines.add("Removed " + label(key));
            }
        }
        BigDecimal average = moved == 0 ? BigDecimal.ZERO
                : totalMovement.divide(BigDecimal.valueOf(moved), 1, RoundingMode.HALF_UP);
        String summary = changed + " of " + after.size() + " rows changed, " + added + " added, " + removed
                + " removed. Average movement " + (average.signum() >= 0 ? "+" : "") + average.toPlainString() + " %.";
        return new Diff(added, removed, changed, average, lines, summary);
    }

    private List<RateTableViews.RowInput> parse(String csv, List<String> issues) {
        List<RateTableViews.RowInput> rows = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            issues.add("The file is empty.");
            return rows;
        }
        String[] lines = csv.replace("\r", "").split("\n");
        int start = lines[0].trim().toLowerCase().startsWith("product_code") ? 1 : 0;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            if (cells.length != 7) {
                issues.add("Line " + (i + 1) + ": expected 7 columns (" + CSV_HEADER + "), found " + cells.length + ".");
                continue;
            }
            try {
                rows.add(new RateTableViews.RowInput(unquote(cells[2]), Integer.valueOf(cells[3].trim()),
                        Integer.valueOf(cells[4].trim()), new BigDecimal(cells[5].trim()), new BigDecimal(cells[6].trim())));
            } catch (NumberFormatException ex) {
                issues.add("Line " + (i + 1) + ": ages must be whole numbers and amounts must be numbers.");
            }
        }
        if (rows.isEmpty() && issues.isEmpty()) {
            issues.add("The file has no rate rows.");
        }
        return rows;
    }

    private RateTableVersion version(RateTable table, int versionNo) {
        return rates.versions(table).stream().filter(v -> v.getVersionNo() == versionNo).findFirst()
                .orElseThrow(() -> new NotFoundException("Version v" + versionNo + " of " + table.getRef() + " not found"));
    }

    private AppUser actor(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Enter " + what + ".");
        }
        if (value.length() > 450) {
            throw new IllegalArgumentException("Keep the reason to 450 characters.");
        }
        return value.trim();
    }

    private static String key(String lifeType, int from, int to) {
        return lifeType + "|" + from + "|" + to;
    }

    private static String label(String key) {
        String[] parts = key.split("\\|");
        return parts[0] + " " + parts[1] + "–" + parts[2];
    }

    private static String pula(BigDecimal value) {
        return value == null ? "—" : "P" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String csvCell(String value) {
        return value.contains(",") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("\"") && trimmed.endsWith("\"") ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
    }

    private static String token(String csv) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(csv.replace("\r", "").trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record Diff(long added, long removed, long changed, BigDecimal averageMovementPct, List<String> lines,
            String summary) {
    }

    /** Spec 11 §8 ImpactReport. */
    public record Impact(String version, long policiesAffected, BigDecimal currentMonthlyIncome,
            BigDecimal projectedMonthlyIncome, BigDecimal movementAmount, BigDecimal movementPct,
            long policiesIncreasingOver10Pct, List<String> policiesBecomingUnrateable, LocalDateTime asOf) {
    }

    /** Spec 11 §8 ImportDryRun. */
    public record ImportDryRun(long addedRows, long removedRows, long changedRows, BigDecimal averageMovementPct,
            List<String> diff, List<String> issues, String dryRunToken) {
    }
}

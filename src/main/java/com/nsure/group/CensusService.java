package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.portfolio.GroupScheme;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Census processing (GL04). A CSV is staged and every row validated without touching live membership; an explicit
 * commit then applies valid rows (PARTIAL) or all-or-nothing (ATOMIC). Both steps are idempotent: the same external
 * batch id returns the same batch, and committing twice changes nothing (AT-GL04-02).
 */
@Service
public class CensusService {

    static final List<String> HEADER = List.of("memberKey", "firstName", "lastName", "dateOfBirth", "category",
            "salary", "action", "effectiveDate");
    /**
     * Optional columns after the mandatory ones, in any order: the actively-at-work declaration (Y/N) at the entry or
     * increase date, and the date the member first became eligible (for the category's late-entry window).
     */
    static final List<String> OPTIONAL = List.of("activelyAtWork", "eligibleDate", "rowKey", "nationalId",
            "relationship", "correctsLine");
    /**
     * Template versions (NS-GL04-001): version 1 is the mandatory columns with activelyAtWork, eligibleDate and rowKey;
     * version 2 adds nationalId, relationship and correctsLine. A batch records the version its header used.
     */
    static final String TEMPLATE_VERSION = "2";
    private static final List<String> V2_COLUMNS = List.of("nationalId", "relationship", "correctsLine");
    private static final int CELLS = HEADER.size() + OPTIONAL.size();
    static final String JOIN = "JOIN";
    static final String LEAVE = "LEAVE";
    static final String SALARY = "SALARY";
    /** A transfer to another benefit category on the same scheme (NS-GL04-004). */
    static final String CATEGORY = "CATEGORY";
    private static final Set<String> ACTIONS = Set.of(JOIN, LEAVE, SALARY, CATEGORY);
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,13}(\\.\\d{1,2})?");
    private static final int MAX_ROWS = 5000;

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final GroupRecords records;
    private final CoverHistory cover;
    private final SupplementaryBilling supplementary;
    private final com.nsure.portfolio.PartyRegister parties;
    /**
     * Demo default (D10): a change backdated more than this many days is held for an authorised review instead of
     * being applied straight away. {@code nsure.group.census.retro-review-days}.
     */
    private final int retroReviewDays;

    public CensusService(EntityManager em, PlatformSecurityContext security, GroupRecords records, CoverHistory cover,
            SupplementaryBilling supplementary, com.nsure.portfolio.PartyRegister parties,
            @org.springframework.beans.factory.annotation.Value("${nsure.group.census.retro-review-days:90}")
            int retroReviewDays) {
        this.em = em;
        this.security = security;
        this.records = records;
        this.cover = cover;
        this.supplementary = supplementary;
        this.parties = parties;
        this.retroReviewDays = retroReviewDays;
    }

    /** The published census template (NS-GL04-001): version, columns with type, rule, example and description. */
    public Map<String, Object> template() {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("version", TEMPLATE_VERSION);
        out.put("supportedVersions", List.of("1", "2"));
        out.put("actions", List.of(JOIN, LEAVE, SALARY, CATEGORY));
        out.put("retroReviewDays", retroReviewDays);
        out.put("maxRows", MAX_ROWS);
        List<Map<String, Object>> columns = new ArrayList<>();
        column(columns, "memberKey", true, "1", "text (max 40)", "EMP-1001",
                "The sponsor's stable key for the member, unique on the scheme; never matched by name.");
        column(columns, "firstName", true, "1", "text (max 60)", "Kagiso", "Required for JOIN.");
        column(columns, "lastName", true, "1", "text (max 60)", "Sebina", "Required for JOIN.");
        column(columns, "dateOfBirth", true, "1", "date yyyy-MM-dd", "1988-04-12",
                "Required for JOIN; the entry age must fit the category.");
        column(columns, "category", true, "1", "category code", "STAFF",
                "Required for JOIN and CATEGORY (the category moved to).");
        column(columns, "salary", true, "1", "amount > 0, 2 decimals", "180000",
                "Annual salary; required for SALARY and salary-multiple categories.");
        column(columns, "action", true, "1", "JOIN | LEAVE | SALARY | CATEGORY", "JOIN",
                "Joiner, leaver (effectiveDate is the last day of cover), salary change or category transfer.");
        column(columns, "effectiveDate", true, "1", "date yyyy-MM-dd", "2026-11-01",
                "Backdated changes adjust invoiced months; more than " + retroReviewDays
                        + " days back, or behind an admitted claim, is held for review.");
        column(columns, "activelyAtWork", false, "1", "Y | N", "Y",
                "N refers all cover (or the increase) to underwriting.");
        column(columns, "eligibleDate", false, "1", "date yyyy-MM-dd", "2026-10-15",
                "JOIN only: when the member became eligible (late-entry window).");
        column(columns, "rowKey", false, "1", "text (max 80)", "HR-1", "Applied at most once per scheme.");
        column(columns, "nationalId", false, "2", "Omang (9 digits) or passport (6-20 letters/digits)", "123456789",
                "Resolves the member to the party record holding the same identity number.");
        column(columns, "relationship", false, "2", "MEMBER", "MEMBER",
                "Only the employee is an eligible member row; dependants are covered under the member's family benefit.");
        column(columns, "correctsLine", false, "2", "line number", "7",
                "In a correction batch: the line of the original batch this row corrects.");
        out.put("columns", columns);
        out.put("header", String.join(",", HEADER) + "," + String.join(",", OPTIONAL));
        return out;
    }

    private static void column(List<Map<String, Object>> out, String name, boolean mandatory, String since,
            String type, String example, String description) {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("name", name);
        c.put("mandatory", mandatory);
        c.put("sinceVersion", since);
        c.put("type", type);
        c.put("example", example);
        c.put("description", description);
        out.add(c);
    }

    // ── staging ────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public GroupViews.CensusBatchView upload(String schemeRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("PROCESS_CENSUS");
        GroupScheme scheme = records.lockedScheme(schemeRef);
        com.nsure.common.ExpectedVersion.check("Scheme " + schemeRef, scheme.getRowVersion());
        GroupRecords.requireActive(scheme, "a census");
        String externalId = GroupRecords.requiredText(body, "externalBatchId", "External batch id", 80);
        String mode = GroupRecords.text(body, "mode");
        mode = mode == null ? CensusBatch.PARTIAL : mode.toUpperCase(Locale.ROOT);
        if (!CensusBatch.PARTIAL.equals(mode) && !CensusBatch.ATOMIC.equals(mode)) {
            throw new IllegalArgumentException("Mode must be PARTIAL or ATOMIC");
        }
        String csv = body == null || !body.hasNonNull("csv") ? "" : normalise(body.get("csv").asText());
        if (csv.isBlank()) {
            throw new IllegalArgumentException("The census file is empty");
        }
        String hash = sha256(csv);

        CensusBatch existing = em.createQuery("select b from CensusBatch b where b.scheme = :scheme"
                        + " and b.externalBatchId = :externalId", CensusBatch.class)
                .setParameter("scheme", scheme)
                .setParameter("externalId", externalId)
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (existing.getFileHash().equals(hash) && existing.getMode().equals(mode)) {
                return records.batchView(existing);
            }
            throw new ConflictException("Batch " + externalId + " was already submitted for scheme " + schemeRef
                    + " (" + existing.getRef() + ") with different content");
        }

        List<String> columns = header(csv);
        List<String[]> lines = parse(csv);
        CensusBatch batch = new CensusBatch(records.nextRef(CensusBatch.class, "CEN-", 5), scheme, externalId, mode,
                hash, user.getUsername());
        batch.schema(columns.stream().anyMatch(V2_COLUMNS::contains) ? "2" : "1");
        String correcting = GroupRecords.text(body, "correctsBatchRef");
        CensusBatch original = null;
        if (correcting != null) {
            original = findBatch(correcting);
            if (!original.getScheme().getId().equals(scheme.getId())) {
                throw new IllegalArgumentException("Batch " + correcting + " belongs to another scheme");
            }
            batch.corrects(original.getRef());
        }
        em.persist(batch);

        Validator validator = new Validator(scheme);
        validator.original = original;
        int total = 0;
        int valid = 0;
        int held = 0;
        for (String[] line : lines) {
            int lineNo = Integer.parseInt(line[0]);
            String[] raw = Arrays.copyOfRange(line, 1, line.length);
            String[] cells = new String[CELLS];
            for (int c = 0; c < Math.min(raw.length, columns.size()); c++) {
                cells[columns.get(c).isEmpty() ? c : HEADER.size() + OPTIONAL.indexOf(columns.get(c))] = raw[c];
            }
            List<String> errors = new ArrayList<>();
            if (raw.length != columns.size()) {
                errors.add("expected " + columns.size() + " columns but found " + raw.length);
            }
            Check check = validator.validate(lineNo, cells);
            errors.addAll(check.errors());
            CensusRow row = new CensusRow(batch, lineNo, cells);
            if (errors.isEmpty() && !check.holds().isEmpty()) {
                row.outcome(CensusRow.HELD, null);
                row.hold(String.join("; ", check.holds()));
                held++;
            } else {
                row.outcome(errors.isEmpty() ? CensusRow.VALID : CensusRow.INVALID,
                        errors.isEmpty() ? null : String.join("; ", errors));
            }
            if (errors.isEmpty()) {
                row.premiums(check.premiumBefore(), check.premiumAfter());
            }
            em.persist(row);
            total++;
            if (errors.isEmpty()) {
                valid++;
            }
        }
        batch.validated(total, valid);
        batch.held(held);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("schemeRef", scheme.getRef());
        data.put("batchRef", batch.getRef());
        data.put("externalBatchId", externalId);
        data.put("mode", mode);
        data.put("totalRows", total);
        data.put("validRows", valid);
        data.put("invalidRows", total - valid);
        data.put("heldRows", held);
        data.put("schemaVersion", batch.getSchemaVersion());
        records.publish(com.nsure.integration.Events.CENSUS_BATCH_VALIDATED, batch.getRef(), data, user.getUsername());
        return records.batchView(batch);
    }

    /**
     * Batch reconciliation (GL04 workspace, NS-GL04-005): each applied row against the member change it created, the
     * member's certificate and the invoice lines billed since, with totals that must agree.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> reconciliation(String ref) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        CensusBatch batch = findBatch(ref);
        List<CensusRow> rows = em.createQuery("select r from CensusRow r where r.batch = :batch order by r.lineNo",
                CensusRow.class).setParameter("batch", batch).getResultList();
        Map<Long, MemberChange> changes = new HashMap<>();
        em.createQuery("select c from MemberChange c join fetch c.member where c.batchRef = :ref", MemberChange.class)
                .setParameter("ref", batch.getRef()).getResultList().forEach(c -> changes.put(c.getId(), c));
        List<Map<String, Object>> out = new ArrayList<>();
        int applied = 0;
        int traced = 0;
        int certified = 0;
        int billed = 0;
        for (CensusRow row : rows) {
            if (!CensusRow.APPLIED.equals(row.getStatus())) {
                continue;
            }
            applied++;
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("lineNo", row.getLineNo());
            v.put("memberKey", row.getMemberKey());
            v.put("action", row.getAction());
            v.put("memberId", row.getMemberId());
            v.put("changeId", row.getChangeId());
            v.put("adjustments", row.getAdjustmentRefs());
            v.put("reviewedBy", row.getReviewedBy());
            MemberChange change = row.getChangeId() == null ? null : changes.get(row.getChangeId());
            if (change != null) {
                traced++;
                SchemeMember m = change.getMember();
                v.put("changeType", change.getChangeType());
                v.put("effectiveDate", change.getEffectiveDate());
                v.put("coverAfter", change.getCoverAfter());
                v.put("pendingAfter", change.getPendingAfter());
                boolean certificate = m.onCover() && m.getAcceptedCover().signum() > 0;
                v.put("certificateNo", certificate ? batch.getScheme().getRef() + "-" + m.getMemberKey() : null);
                certified += certificate ? 1 : 0;
                List<String> invoices = em.createQuery("select l.invoice.ref from InvoiceLine l where l.memberId = :m"
                                + " and l.invoice.periodEnd >= :from order by l.invoice.period", String.class)
                        .setParameter("m", m.getId()).setParameter("from", change.getEffectiveDate()).getResultList();
                v.put("invoices", invoices);
                billed += invoices.size();
            }
            out.add(v);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("batchRef", batch.getRef());
        result.put("schemeRef", batch.getScheme().getRef());
        result.put("status", batch.getStatus());
        result.put("totalRows", batch.getTotalRows());
        result.put("acceptedRows", batch.getAcceptedRows());
        result.put("rejectedRows", batch.getRejectedRows());
        result.put("appliedRows", applied);
        result.put("memberChanges", changes.values().stream()
                .filter(c -> Set.of(JOIN, LEAVE, SALARY).contains(c.getChangeType())).count());
        result.put("rowsTracedToChanges", traced);
        result.put("certificates", certified);
        result.put("invoiceLines", billed);
        result.put("reconciled", applied == batch.getAcceptedRows() && traced == applied);
        result.put("rows", out);
        return result;
    }

    /** A member's cover periods, oldest first, including superseded ones (marked) for the audit trail. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> coverHistory(Long memberId) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        SchemeMember member = em.find(SchemeMember.class, memberId);
        if (member == null) {
            throw new NotFoundException("Member " + memberId + " not found");
        }
        List<MemberCoverPeriod> periods = em.createQuery("select p from MemberCoverPeriod p where p.member = :m"
                + " order by p.validFrom, p.id", MemberCoverPeriod.class).setParameter("m", member).getResultList();
        if (periods.isEmpty()) {
            periods = List.of(MemberCoverPeriod.snapshot(member));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MemberCoverPeriod p : periods) {
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("validFrom", p.getValidFrom());
            v.put("validTo", p.getValidTo());
            v.put("category", p.getCategoryCode());
            v.put("salary", p.getSalary());
            v.put("acceptedCover", p.getAcceptedCover());
            v.put("pendingCover", p.getPendingCover());
            v.put("status", p.getStatus());
            v.put("changeType", p.getChangeType());
            v.put("changeId", p.getChangeId());
            v.put("superseded", p.getSupersededBy() != null);
            out.add(v);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public GroupViews.CensusBatchView batch(String ref) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        return records.batchView(findBatch(ref));
    }

    // ── commit ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public GroupViews.CensusBatchView commit(String ref) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("PROCESS_CENSUS");
        CensusBatch batch = findBatch(ref);
        GroupScheme scheme = records.lockedScheme(batch.getScheme().getRef());
        em.refresh(batch);
        if (batch.isCommitted()) {
            return records.batchView(batch);
        }
        GroupRecords.requireActive(scheme, "a census commit");
        boolean atomic = CensusBatch.ATOMIC.equals(batch.getMode());
        if (atomic && batch.getInvalidRows() > 0) {
            throw new IllegalArgumentException("ATOMIC batch " + batch.getRef() + " has " + batch.getInvalidRows()
                    + " invalid row(s); nothing was applied. Correct the file and submit it as a new batch.");
        }
        if (atomic && batch.getHeldRows() > 0) {
            throw new IllegalArgumentException("ATOMIC batch " + batch.getRef() + " has " + batch.getHeldRows()
                    + " row(s) held for authorised review; nothing was applied. Review them first.");
        }
        List<CensusRow> rows = em.createQuery("select r from CensusRow r where r.batch = :batch order by r.lineNo",
                        CensusRow.class)
                .setParameter("batch", batch)
                .getResultList();

        // Membership may have moved on since staging: check the valid rows again before applying any of them.
        Validator validator = new Validator(scheme);
        validator.original = batch.getCorrectsBatchRef() == null ? null : findBatch(batch.getCorrectsBatchRef());
        Map<CensusRow, List<String>> stale = new HashMap<>();
        Map<CensusRow, List<String>> newlyHeld = new HashMap<>();
        for (CensusRow row : rows) {
            if (CensusRow.VALID.equals(row.getStatus())) {
                Check check = validator.validate(row.getLineNo(), cells(row));
                if (!check.errors().isEmpty()) {
                    stale.put(row, check.errors());
                } else if (!check.holds().isEmpty() && row.getReviewedBy() == null) {
                    newlyHeld.put(row, check.holds());
                }
            }
        }
        if (atomic && !newlyHeld.isEmpty()) {
            CensusRow first = newlyHeld.keySet().stream().min((a, b) -> a.getLineNo() - b.getLineNo()).orElseThrow();
            throw new IllegalArgumentException("ATOMIC batch " + batch.getRef() + " line " + first.getLineNo()
                    + " now needs an authorised review (" + String.join("; ", newlyHeld.get(first))
                    + "); nothing was applied.");
        }
        if (atomic && !stale.isEmpty()) {
            CensusRow first = stale.keySet().stream().min((a, b) -> a.getLineNo() - b.getLineNo()).orElseThrow();
            throw new IllegalArgumentException("ATOMIC batch " + batch.getRef() + " no longer validates (line "
                    + first.getLineNo() + ": " + String.join("; ", stale.get(first)) + "); nothing was applied.");
        }

        int accepted = 0;
        int rejected = 0;
        int held = 0;
        Map<Long, LocalDate> touched = new java.util.LinkedHashMap<>();
        Map<Long, List<CensusRow>> rowsByMember = new HashMap<>();
        for (CensusRow row : rows) {
            if (CensusRow.HELD.equals(row.getStatus())) {
                held++;
            } else if (!CensusRow.VALID.equals(row.getStatus())) {
                row.outcome(CensusRow.REJECTED, row.getError());
                rejected++;
            } else if (stale.containsKey(row)) {
                row.outcome(CensusRow.REJECTED, String.join("; ", stale.get(row)));
                rejected++;
            } else if (newlyHeld.containsKey(row)) {
                row.outcome(CensusRow.HELD, null);
                row.hold(String.join("; ", newlyHeld.get(row)));
                held++;
            } else {
                MemberChange change = apply(validator, batch, row, user.getUsername());
                row.applied(change.getMember().getId(), change.getId());
                corrected(validator, batch, row);
                touched.merge(change.getMember().getId(), change.getEffectiveDate(),
                        (a, b) -> a.isBefore(b) ? a : b);
                rowsByMember.computeIfAbsent(change.getMember().getId(), k -> new ArrayList<>()).add(row);
                accepted++;
            }
        }
        batch.committed(accepted, rejected, user.getUsername());
        batch.held(held);
        adjust(scheme, touched, rowsByMember, batch.getRef(), user.getUsername());
        records.refreshTotals(scheme);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("schemeRef", scheme.getRef());
        data.put("batchRef", batch.getRef());
        data.put("externalBatchId", batch.getExternalBatchId());
        data.put("acceptedRows", accepted);
        data.put("rejectedRows", rejected);
        data.put("heldRows", held);
        records.publish(com.nsure.integration.Events.CENSUS_BATCH_COMPLETED, batch.getRef(), data, user.getUsername());
        return records.batchView(batch);
    }

    /**
     * Supplementary billing (NS-GL05-002): applied rows that reach back into invoiced months get debit or credit
     * adjustments referencing the original lines; each row keeps the adjustment references it created.
     */
    private void adjust(GroupScheme scheme, Map<Long, LocalDate> touched, Map<Long, List<CensusRow>> rowsByMember,
            String sourceRef, String actor) {
        if (touched.isEmpty()) {
            return;
        }
        Map<Long, List<String>> refs = supplementary.reconcile(scheme, touched, sourceRef, actor);
        refs.forEach((memberId, list) -> rowsByMember.getOrDefault(memberId, List.of())
                .forEach(r -> r.adjustments(String.join(",", list))));
    }

    /** A correction row that applied closes the original row it corrects (NS-GL04-006). */
    private void corrected(Validator validator, CensusBatch batch, CensusRow row) {
        Integer line = row.getCorrectsLine();
        if (line == null || validator.original == null) {
            return;
        }
        em.createQuery("select r from CensusRow r where r.batch = :b and r.lineNo = :n", CensusRow.class)
                .setParameter("b", validator.original).setParameter("n", line).getResultStream().findFirst()
                .ifPresent(o -> {
                    if (CensusRow.HELD.equals(o.getStatus())) {
                        o.outcome(CensusRow.REJECTED, "Superseded by correction " + batch.getRef() + " line "
                                + row.getLineNo());
                        validator.original.held(Math.max(0, validator.original.getHeldRows() - 1));
                        if (validator.original.isCommitted()) {
                            validator.original.reviewRejected();
                        }
                    }
                    o.correctedBy(batch.getRef() + " line " + row.getLineNo());
                });
    }

    /**
     * {decision APPLY|REJECT, note}: the authorised review of a held row (AT-GL04-03), by someone other than the person
     * who submitted the batch. Before commit, APPLY releases the row for the commit; after commit it is applied now
     * (re-checked against live membership). REJECT rejects it with the reviewer's note.
     */
    @Transactional
    public GroupViews.CensusBatchView review(String ref, int lineNo, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UNDERWRITE_MEMBER");
        CensusBatch batch = findBatch(ref);
        GroupScheme scheme = records.lockedScheme(batch.getScheme().getRef());
        em.refresh(batch);
        if (user.getUsername().equalsIgnoreCase(batch.getSubmittedBy())) {
            throw new com.nsure.security.exception.NoAuthorizationException("You submitted " + ref
                    + ", so someone else must review its held rows.");
        }
        CensusRow row = em.createQuery("select r from CensusRow r where r.batch = :b and r.lineNo = :n", CensusRow.class)
                .setParameter("b", batch).setParameter("n", lineNo).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Batch " + ref + " has no line " + lineNo));
        if (!CensusRow.HELD.equals(row.getStatus())) {
            throw new ConflictException("Line " + lineNo + " of " + ref + " is not held for review");
        }
        String decision = GroupRecords.requiredText(body, "decision", "Decision", 10).toUpperCase(Locale.ROOT);
        String note = GroupRecords.requiredText(body, "note", "Review note", 300);
        if ("REJECT".equals(decision)) {
            row.reviewed(user.getUsername(), note);
            if (batch.isCommitted()) {
                row.outcome(CensusRow.REJECTED, "Rejected at review: " + note);
                batch.reviewRejected();
            } else {
                row.outcome(CensusRow.INVALID, "Rejected at review: " + note);
                batch.validated(batch.getTotalRows(), batch.getValidRows() - 1);
            }
            batch.held(batch.getHeldRows() - 1);
            em.flush();
            return records.batchView(batch);
        }
        if (!"APPLY".equals(decision)) {
            throw new IllegalArgumentException("Decision must be APPLY or REJECT");
        }
        row.reviewed(user.getUsername(), note);
        if (!batch.isCommitted()) {
            row.outcome(CensusRow.VALID, null);
            batch.held(batch.getHeldRows() - 1);
            em.flush();
            return records.batchView(batch);
        }
        GroupRecords.requireActive(scheme, "a census change");
        Validator validator = new Validator(scheme);
        validator.original = batch.getCorrectsBatchRef() == null ? null : findBatch(batch.getCorrectsBatchRef());
        Check check = validator.validate(row.getLineNo(), cells(row));
        if (!check.errors().isEmpty()) {
            throw new IllegalArgumentException("Line " + lineNo + " no longer applies: "
                    + String.join("; ", check.errors()));
        }
        MemberChange change = apply(validator, batch, row, user.getUsername());
        row.applied(change.getMember().getId(), change.getId());
        corrected(validator, batch, row);
        batch.reviewApplied();
        batch.held(batch.getHeldRows() - 1);
        adjust(scheme, Map.of(change.getMember().getId(), change.getEffectiveDate()),
                Map.of(change.getMember().getId(), List.of(row)), batch.getRef(), user.getUsername());
        records.refreshTotals(scheme);
        em.flush();
        return records.batchView(batch);
    }

    private MemberChange apply(Validator validator, CensusBatch batch, CensusRow row, String actor) {
        String action = row.getAction().trim().toUpperCase(Locale.ROOT);
        LocalDate effective = GroupRecords.parseDate(row.getEffectiveDate().trim());
        Boolean activelyAtWork = yesNo(row.getActivelyAtWork());
        if (JOIN.equals(action)) {
            BenefitCategory category = validator.categories.get(row.getCategoryCode().trim().toUpperCase(Locale.ROOT));
            LocalDate eligible = row.getEligibleDate() == null ? null : GroupRecords.parseDate(row.getEligibleDate().trim());
            boolean lateEntrant = lateEntrant(category, eligible, effective);
            boolean notAtWork = Boolean.FALSE.equals(activelyAtWork);
            SchemeMember member = new SchemeMember(batch.getScheme(), category, row.getMemberKey().trim(),
                    row.getFirstName().trim(), row.getLastName().trim(),
                    GroupRecords.parseDate(row.getDateOfBirth().trim()), amount(row.getSalary()), effective,
                    batch.getRef(), activelyAtWork, notAtWork || lateEntrant);
            em.persist(member);
            String nationalId = value(row.getNationalId());
            if (nationalId != null) {
                member.identify(nationalId.replaceAll("\\s+", "").toUpperCase(Locale.ROOT), partyFor(nationalId));
            }
            String joinNote = notAtWork ? "Not actively at work on the entry date: all cover is referred to underwriting"
                    : lateEntrant ? "Late entrant (eligible " + eligible + ", outside the " + category.getLateEntryDays()
                            + "-day window): all cover is referred to underwriting"
                    : SchemeMember.UNDERWRITING_PENDING.equals(member.getStatus())
                            ? "Cover above the free-cover limit is pending underwriting" : null;
            MemberChange change = new MemberChange(member, JOIN, effective, null, null, batch.getRef(), joinNote, actor);
            records.memberChanged(change);
            cover.record(member, effective, JOIN, change.getId());
            validator.members.put(member.getMemberKey(), member);
            return change;
        }
        SchemeMember member = validator.members.get(row.getMemberKey().trim());
        cover.baseline(member);
        BigDecimal salaryBefore = member.getSalary();
        BigDecimal coverBefore = member.getAcceptedCover();
        String note = null;
        if (LEAVE.equals(action)) {
            member.cease(effective);
        } else if (CATEGORY.equals(action)) {
            BenefitCategory to = validator.categories.get(row.getCategoryCode().trim().toUpperCase(Locale.ROOT));
            String from = member.getCategory().getCode();
            member.transferCategory(to, amount(row.getSalary()), activelyAtWork);
            note = "Category " + from + " to " + to.getCode()
                    + (member.getPendingCover().signum() > 0 ? "; cover above the limit is pending underwriting" : "");
        } else {
            member.changeSalary(amount(row.getSalary()), activelyAtWork);
            if (Boolean.FALSE.equals(activelyAtWork) && member.getPendingCover().signum() > 0) {
                note = "Not actively at work on the increase date: the increase is referred to underwriting";
            }
        }
        if (row.getReviewedBy() != null) {
            note = (note == null ? "" : note + ". ") + "Released at review by " + row.getReviewedBy();
        }
        MemberChange change = new MemberChange(member, action, effective, salaryBefore, coverBefore, batch.getRef(), note,
                actor);
        records.memberChanged(change);
        cover.record(member, effective, action, change.getId());
        return change;
    }

    /** The party holding this identity number, when exactly one active customer does (NS-GL04-003). */
    private String partyFor(String nationalId) {
        List<com.nsure.portfolio.CustomerAccount> holders = parties.holders(nationalId);
        return holders.size() == 1 ? holders.get(0).getRef() : null;
    }

    private CensusBatch findBatch(String ref) {
        return em.createQuery("select b from CensusBatch b join fetch b.scheme where b.ref = :ref", CensusBatch.class)
                .setParameter("ref", ref)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Census batch " + ref + " not found"));
    }

    private static String[] cells(CensusRow row) {
        return new String[] {row.getMemberKey(), row.getFirstName(), row.getLastName(), row.getDateOfBirth(),
                row.getCategoryCode(), row.getSalary(), row.getAction(), row.getEffectiveDate(),
                row.getActivelyAtWork(), row.getEligibleDate(), row.getRowKey(), row.getNationalId(),
                row.getRelationship(), row.getCorrectsLine() == null ? null : String.valueOf(row.getCorrectsLine())};
    }

    /** Y/YES/TRUE or N/NO/FALSE; null when blank, and when not a yes or no (validation reports that). */
    static Boolean yesNo(String text) {
        String v = value(text);
        if (v == null) {
            return null;
        }
        return switch (v.toUpperCase(Locale.ROOT)) {
            case "Y", "YES", "TRUE" -> Boolean.TRUE;
            case "N", "NO", "FALSE" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Joining more than the category's late-entry window after first becoming eligible; never when no window is set. */
    static boolean lateEntrant(BenefitCategory category, LocalDate eligible, LocalDate effective) {
        return category.getLateEntryDays() != null && eligible != null && effective != null
                && eligible.plusDays(category.getLateEntryDays()).isBefore(effective);
    }

    // ── validation ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A row's validation: errors reject it; holds keep a valid row for an authorised review (AT-GL04-03); the premium
     * estimate is a full month on the cover before and after the row (NS-GL04-005 preview).
     */
    record Check(List<String> errors, List<String> holds, BigDecimal premiumBefore, BigDecimal premiumAfter) {
    }

    /** Validates rows against the scheme's categories and live membership, tracking member keys seen in the file. */
    private final class Validator {

        private final GroupScheme scheme;
        private final Map<String, BenefitCategory> categories = new HashMap<>();
        private final Map<String, SchemeMember> members;
        private final Map<String, Integer> seen = new HashMap<>();
        private final Map<String, Integer> seenRowKeys = new HashMap<>();
        private final Map<String, String> closedPeriods = new HashMap<>();
        /** The batch a correction batch corrects (NS-GL04-006), or null. */
        private CensusBatch original;
        private LocalDate latestInvoiced;

        Validator(GroupScheme scheme) {
            this.scheme = scheme;
            for (BenefitCategory category : records.categories(scheme)) {
                categories.put(category.getCode(), category);
            }
            this.members = records.membersOnCover(scheme);
        }

        Check validate(int lineNo, String[] cells) {
            List<String> errors = new ArrayList<>();
            List<String> holds = new ArrayList<>();
            BigDecimal[] premium = new BigDecimal[2];
            validate(lineNo, cells, errors, holds, premium);
            return new Check(errors, holds, premium[0], premium[1]);
        }

        private void validate(int lineNo, String[] cells, List<String> errors, List<String> holds,
                BigDecimal[] premium) {
            String key = value(cells[0]);
            String firstName = value(cells[1]);
            String lastName = value(cells[2]);
            String dob = value(cells[3]);
            String categoryCode = value(cells[4]);
            String salaryText = value(cells[5]);
            String action = value(cells[6]);
            String effectiveText = value(cells[7]);
            String atWorkText = cells.length > 8 ? value(cells[8]) : null;
            String eligibleText = cells.length > 9 ? value(cells[9]) : null;
            String rowKey = cells.length > 10 ? value(cells[10]) : null;
            String nationalId = cells.length > 11 ? value(cells[11]) : null;
            String relationship = cells.length > 12 ? value(cells[12]) : null;
            String correctsLine = cells.length > 13 ? value(cells[13]) : null;
            if (relationship != null && !Set.of("MEMBER", "EMPLOYEE", "PRINCIPAL")
                    .contains(relationship.toUpperCase(Locale.ROOT))) {
                errors.add("relationship " + relationship + " is not an eligible member row; dependants are covered"
                        + " under the member's family benefit");
            }
            if (nationalId != null) {
                String number = nationalId.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                try {
                    parties.identity(number.matches("[0-9]+") ? com.nsure.portfolio.PartyRegister.OMANG
                            : com.nsure.portfolio.PartyRegister.PASSPORT, number);
                } catch (IllegalArgumentException ex) {
                    errors.add("nationalId: " + ex.getMessage());
                }
            }
            if (correctsLine != null) {
                checkCorrection(errors, correctsLine, key);
            }
            if (rowKey != null) {
                // NS-GL04-003: the sponsor's row key is applied once per scheme, whichever batch repeats it.
                if (rowKey.length() > 80) {
                    errors.add("rowKey must be at most 80 characters");
                } else if (seenRowKeys.containsKey(rowKey)) {
                    errors.add("duplicate rowKey " + rowKey + " in this file (first on line " + seenRowKeys.get(rowKey)
                            + ")");
                } else {
                    seenRowKeys.put(rowKey, lineNo);
                    em.createQuery("select r from CensusRow r join fetch r.batch b where b.scheme = :s"
                                    + " and r.rowKey = :k and r.status = :applied", CensusRow.class)
                            .setParameter("s", scheme).setParameter("k", rowKey)
                            .setParameter("applied", CensusRow.APPLIED).setMaxResults(1).getResultStream().findFirst()
                            .ifPresent(r -> errors.add("rowKey " + rowKey + " was already applied in "
                                    + r.getBatch().getRef() + " line " + r.getLineNo() + "; it is not applied again"));
                }
            }
            if (atWorkText != null && yesNo(atWorkText) == null) {
                errors.add("activelyAtWork must be Y or N");
            }

            if (key == null) {
                errors.add("memberKey is required");
            } else if (key.length() > 40) {
                errors.add("memberKey must be at most 40 characters");
            } else if (seen.containsKey(key)) {
                errors.add("duplicate memberKey " + key + " in this file (first on line " + seen.get(key) + ")");
            } else {
                seen.put(key, lineNo);
            }
            action = action == null ? null : action.toUpperCase(Locale.ROOT);
            if (action == null) {
                errors.add("action is required");
            } else if (!ACTIONS.contains(action)) {
                errors.add("action must be JOIN, LEAVE, SALARY or CATEGORY");
                action = null;
            }
            LocalDate effective = null;
            if (effectiveText == null) {
                errors.add("effectiveDate is required");
            } else if ((effective = GroupRecords.parseDate(effectiveText)) == null) {
                errors.add("effectiveDate must be a date in the format yyyy-MM-dd");
            }
            BigDecimal salary = null;
            if (salaryText != null) {
                salary = amount(salaryText);
                if (salary == null || salary.signum() <= 0) {
                    errors.add("salary must be a number greater than 0 with at most two decimals");
                    salary = null;
                }
            }
            if (action == null || key == null) {
                return;
            }
            SchemeMember member = members.get(key);
            if (eligibleText != null) {
                LocalDate eligible = GroupRecords.parseDate(eligibleText);
                if (!JOIN.equals(action)) {
                    errors.add("eligibleDate applies to JOIN rows only");
                } else if (eligible == null) {
                    errors.add("eligibleDate must be a date in the format yyyy-MM-dd");
                } else if (effective != null && eligible.isAfter(effective)) {
                    errors.add("eligibleDate is after the effectiveDate");
                }
            }
            Boolean atWork = yesNo(atWorkText);
            if (JOIN.equals(action)) {
                validateJoin(errors, member, key, firstName, lastName, dob, categoryCode, salaryText, effective);
                BenefitCategory category = categoryCode == null ? null
                        : categories.get(categoryCode.toUpperCase(Locale.ROOT));
                if (errors.isEmpty() && category != null) {
                    BigDecimal requested = category.coverFor(salary);
                    BigDecimal ceiling = Boolean.FALSE.equals(atWork) ? BigDecimal.ZERO : category.getFreeCoverLimit();
                    premium[0] = BigDecimal.ZERO.setScale(2);
                    premium[1] = category.monthlyPremium(requested.min(ceiling));
                }
            } else if (member == null) {
                errors.add(action + " needs an active member; " + key + " is not active on scheme " + scheme.getRef());
            } else {
                if (effective != null && effective.isBefore(member.getJoinDate())) {
                    errors.add("effectiveDate is before the member joined (" + member.getJoinDate() + ")");
                }
                if (SALARY.equals(action) && salaryText == null) {
                    errors.add("salary is required for a SALARY change");
                }
                BenefitCategory target = member.getCategory();
                if (CATEGORY.equals(action)) {
                    if (categoryCode == null) {
                        errors.add("category is required for a CATEGORY change (the category moved to)");
                    } else if ((target = categories.get(categoryCode.toUpperCase(Locale.ROOT))) == null) {
                        errors.add("unknown category " + categoryCode);
                    } else if (target.getId().equals(member.getCategory().getId())) {
                        errors.add("member " + key + " is already in category " + target.getCode());
                    } else if (target.salaryBased() && salary == null && member.getSalary() == null) {
                        errors.add("salary is required for salary-multiple category " + target.getCode());
                    }
                }
                if (effective != null) {
                    retrospective(holds, member, action, effective);
                }
                if (errors.isEmpty() && target != null) {
                    premium[0] = member.getCategory().monthlyPremium(member.getAcceptedCover());
                    premium[1] = LEAVE.equals(action) ? BigDecimal.ZERO.setScale(2)
                            : target.monthlyPremium(acceptedAfter(member, target,
                                    salary == null ? member.getSalary() : salary, atWork));
                }
            }
            if (effective != null && errors.isEmpty()) {
                retroWindow(holds, effective);
            }
        }

        /**
         * Retrospective checks (NS-GL04-004, AT-GL04-03): a leave, salary or category change dated before the event of a
         * claim the insurer has admitted would change the cover that claim was decided on, so it is held for review.
         */
        private void retrospective(List<String> holds, SchemeMember member, String action, LocalDate effective) {
            for (GroupClaim claim : em.createQuery("select c from GroupClaim c where c.member = :m"
                            + " and c.status in :admitted and c.eventDate >= :from order by c.eventDate",
                            GroupClaim.class)
                    .setParameter("m", member).setParameter("admitted", GroupClaim.ADMITTED)
                    .setParameter("from", LEAVE.equals(action) ? effective.plusDays(1) : effective).getResultList()) {
                holds.add("held for authorised review: claim " + claim.getRef() + " ("
                        + claim.getStatus().toLowerCase(Locale.ROOT).replace('_', ' ') + ", event "
                        + claim.getEventDate() + ") falls after this " + action.toLowerCase(Locale.ROOT) + " date");
            }
        }

        /**
         * A change that reaches back into a month already invoiced creates a supplementary adjustment. When it reaches
         * back more than the review window, or into a closed accounting period, it is held for an authorised review.
         */
        private void retroWindow(List<String> holds, LocalDate effective) {
            if (latestInvoiced == null) {
                latestInvoiced = em.createQuery("select max(i.periodEnd) from SchemeInvoice i where i.scheme = :s",
                        LocalDate.class).setParameter("s", scheme).getSingleResult();
                if (latestInvoiced == null) {
                    latestInvoiced = LocalDate.MIN;
                }
            }
            if (effective.isAfter(latestInvoiced)) {
                return;
            }
            LocalDate today = LocalDate.now();
            if (effective.isBefore(today.minusDays(retroReviewDays))) {
                holds.add("held for authorised review: backdated more than " + retroReviewDays + " days (demo review"
                        + " window)");
            }
            if (effective.isBefore(today.withDayOfMonth(1))) {
                String code = java.time.YearMonth.from(effective).toString();
                String status = closedPeriods.computeIfAbsent(code, c -> em.createQuery(
                                "select p.status from AccountingPeriod p where p.code = :c", String.class)
                        .setParameter("c", c).getResultStream().findFirst().orElse(""));
                if ("CLOSED".equals(status)) {
                    holds.add("held for authorised review: accounting period " + code + " is closed; any premium"
                            + " adjustment posts in the next open period");
                }
            }
        }

        /** A correction row must point at a line of the original batch that did not apply (NS-GL04-006). */
        private void checkCorrection(List<String> errors, String correctsLine, String key) {
            if (original == null) {
                errors.add("correctsLine needs the upload to name the batch it corrects (correctsBatchRef)");
                return;
            }
            int line;
            try {
                line = Integer.parseInt(correctsLine);
            } catch (NumberFormatException ex) {
                errors.add("correctsLine must be a line number");
                return;
            }
            CensusRow o = em.createQuery("select r from CensusRow r where r.batch = :b and r.lineNo = :n",
                    CensusRow.class).setParameter("b", original).setParameter("n", line).getResultStream().findFirst()
                    .orElse(null);
            if (o == null) {
                errors.add("correctsLine " + line + " is not a line of " + original.getRef());
            } else if (CensusRow.APPLIED.equals(o.getStatus())) {
                errors.add(original.getRef() + " line " + line + " was applied; correct applied cover with a new"
                        + " movement row instead");
            } else if (o.getCorrectedBy() != null) {
                errors.add(original.getRef() + " line " + line + " was already corrected by " + o.getCorrectedBy());
            } else if (key != null && o.getMemberKey() != null && !key.equalsIgnoreCase(o.getMemberKey().trim())) {
                errors.add("correctsLine " + line + " is for member " + o.getMemberKey() + ", not " + key);
            }
        }

        /** The accepted cover a salary or category change would leave (the same ceiling rules the member applies). */
        static BigDecimal acceptedAfter(SchemeMember member, BenefitCategory category, BigDecimal salary,
                Boolean atWork) {
            BigDecimal requested = category.coverFor(salary);
            BigDecimal ceiling = category.getFreeCoverLimit();
            if (member.getUnderwrittenCover() != null && member.getUnderwrittenCover().compareTo(ceiling) > 0) {
                ceiling = member.getUnderwrittenCover();
            }
            if (Boolean.FALSE.equals(atWork)) {
                ceiling = ceiling.min(member.getAcceptedCover());
            }
            return requested.min(ceiling);
        }

        private void validateJoin(List<String> errors, SchemeMember member, String key, String firstName,
                String lastName, String dob, String categoryCode, String salaryText, LocalDate effective) {
            if (member != null) {
                errors.add("member " + key + " is already active on scheme " + scheme.getRef());
            }
            if (firstName == null) {
                errors.add("firstName is required");
            } else if (firstName.length() > 60) {
                errors.add("firstName must be at most 60 characters");
            }
            if (lastName == null) {
                errors.add("lastName is required");
            } else if (lastName.length() > 60) {
                errors.add("lastName must be at most 60 characters");
            }
            LocalDate birth = null;
            if (dob == null) {
                errors.add("dateOfBirth is required");
            } else if ((birth = GroupRecords.parseDate(dob)) == null) {
                errors.add("dateOfBirth must be a date in the format yyyy-MM-dd");
            }
            BenefitCategory category = null;
            if (categoryCode == null) {
                errors.add("category is required");
            } else if ((category = categories.get(categoryCode.toUpperCase(Locale.ROOT))) == null) {
                errors.add("unknown category " + categoryCode);
            }
            if (category != null && category.salaryBased() && salaryText == null) {
                errors.add("salary is required for salary-multiple category " + category.getCode());
            }
            if (effective != null && effective.isBefore(scheme.getInceptionDate())) {
                errors.add("effectiveDate is before the scheme inception date (" + scheme.getInceptionDate() + ")");
            }
            if (birth != null && effective != null) {
                if (!birth.isBefore(effective)) {
                    errors.add("dateOfBirth must be before the effectiveDate");
                } else if (category != null) {
                    int age = Period.between(birth, effective).getYears();
                    if (age < category.getMinEntryAge() || age > category.getMaxEntryAge()) {
                        errors.add("age " + age + " is outside the entry ages " + category.getMinEntryAge() + "-"
                                + category.getMaxEntryAge() + " for category " + category.getCode());
                    }
                }
            }
        }
    }

    // ── CSV ────────────────────────────────────────────────────────────────────────────────────────

    private static String value(String cell) {
        if (cell == null) {
            return null;
        }
        String trimmed = cell.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal amount(String text) {
        String value = value(text);
        if (value == null || !AMOUNT.matcher(value).matches()) {
            return null;
        }
        return new BigDecimal(value).setScale(2);
    }

    static String normalise(String csv) {
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * The file's columns in order: the mandatory template columns as "" and each optional column by its name. The
     * header must start with the template columns and may add any of the optional ones once.
     */
    static List<String> header(String csv) {
        for (String line : csv.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            List<String> names = splitLine(line).stream().map(String::trim).toList();
            List<String> out = new ArrayList<>();
            boolean ok = names.size() >= HEADER.size() && names.size() <= HEADER.size() + OPTIONAL.size();
            for (int i = 0; ok && i < names.size(); i++) {
                if (i < HEADER.size()) {
                    ok = names.get(i).equalsIgnoreCase(HEADER.get(i));
                    out.add("");
                } else {
                    String name = names.get(i);
                    String optional = OPTIONAL.stream().filter(o -> o.equalsIgnoreCase(name)).findFirst().orElse(null);
                    ok = optional != null && !out.contains(optional);
                    out.add(optional);
                }
            }
            if (!ok) {
                throw new IllegalArgumentException("The first line must be the census header: " + String.join(",", HEADER)
                        + ", optionally followed by " + String.join(" and/or ", OPTIONAL));
            }
            return out;
        }
        throw new IllegalArgumentException("The census file has no data rows");
    }

    /**
     * Splits the file into data lines; each array starts with the file line number followed by the cells. The first
     * non-blank line is the header (checked by {@link #header}).
     */
    private static List<String[]> parse(String csv) {
        String[] lines = csv.split("\n", -1);
        List<String[]> out = new ArrayList<>();
        boolean header = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> cells = splitLine(lines[i]);
            if (!header) {
                header = true;
                continue;
            }
            String[] row = new String[cells.size() + 1];
            row[0] = String.valueOf(i + 1);
            for (int c = 0; c < cells.size(); c++) {
                row[c + 1] = cells.get(c);
            }
            out.add(row);
            if (out.size() > MAX_ROWS) {
                throw new IllegalArgumentException("A census batch may have at most " + MAX_ROWS + " rows");
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("The census file has no data rows");
        }
        return out;
    }

    /** One CSV line; double-quoted cells may contain commas and doubled quotes. */
    private static List<String> splitLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }
}

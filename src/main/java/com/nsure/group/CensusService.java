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
    static final List<String> OPTIONAL = List.of("activelyAtWork", "eligibleDate", "rowKey");
    private static final int CELLS = HEADER.size() + OPTIONAL.size();
    static final String JOIN = "JOIN";
    static final String LEAVE = "LEAVE";
    static final String SALARY = "SALARY";
    private static final Set<String> ACTIONS = Set.of(JOIN, LEAVE, SALARY);
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,13}(\\.\\d{1,2})?");
    private static final int MAX_ROWS = 5000;

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final GroupRecords records;

    public CensusService(EntityManager em, PlatformSecurityContext security, GroupRecords records) {
        this.em = em;
        this.security = security;
        this.records = records;
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
        em.persist(batch);

        Validator validator = new Validator(scheme);
        int total = 0;
        int valid = 0;
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
            errors.addAll(validator.validate(lineNo, cells));
            CensusRow row = new CensusRow(batch, lineNo, cells);
            row.outcome(errors.isEmpty() ? CensusRow.VALID : CensusRow.INVALID,
                    errors.isEmpty() ? null : String.join("; ", errors));
            em.persist(row);
            total++;
            if (errors.isEmpty()) {
                valid++;
            }
        }
        batch.validated(total, valid);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("schemeRef", scheme.getRef());
        data.put("batchRef", batch.getRef());
        data.put("externalBatchId", externalId);
        data.put("mode", mode);
        data.put("totalRows", total);
        data.put("validRows", valid);
        data.put("invalidRows", total - valid);
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
        List<CensusRow> rows = em.createQuery("select r from CensusRow r where r.batch = :batch order by r.lineNo",
                        CensusRow.class)
                .setParameter("batch", batch)
                .getResultList();

        // Membership may have moved on since staging: check the valid rows again before applying any of them.
        Validator validator = new Validator(scheme);
        Map<CensusRow, List<String>> stale = new HashMap<>();
        for (CensusRow row : rows) {
            if (CensusRow.VALID.equals(row.getStatus())) {
                List<String> errors = validator.validate(row.getLineNo(), cells(row));
                if (!errors.isEmpty()) {
                    stale.put(row, errors);
                }
            }
        }
        if (atomic && !stale.isEmpty()) {
            CensusRow first = stale.keySet().stream().min((a, b) -> a.getLineNo() - b.getLineNo()).orElseThrow();
            throw new IllegalArgumentException("ATOMIC batch " + batch.getRef() + " no longer validates (line "
                    + first.getLineNo() + ": " + String.join("; ", stale.get(first)) + "); nothing was applied.");
        }

        int accepted = 0;
        int rejected = 0;
        for (CensusRow row : rows) {
            if (!CensusRow.VALID.equals(row.getStatus())) {
                row.outcome(CensusRow.REJECTED, row.getError());
                rejected++;
            } else if (stale.containsKey(row)) {
                row.outcome(CensusRow.REJECTED, String.join("; ", stale.get(row)));
                rejected++;
            } else {
                MemberChange change = apply(validator, batch, row, user.getUsername());
                row.applied(change.getMember().getId(), change.getId());
                accepted++;
            }
        }
        batch.committed(accepted, rejected, user.getUsername());
        records.refreshTotals(scheme);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("schemeRef", scheme.getRef());
        data.put("batchRef", batch.getRef());
        data.put("externalBatchId", batch.getExternalBatchId());
        data.put("acceptedRows", accepted);
        data.put("rejectedRows", rejected);
        records.publish(com.nsure.integration.Events.CENSUS_BATCH_COMPLETED, batch.getRef(), data, user.getUsername());
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
            String joinNote = notAtWork && SchemeMember.COVER_DEFERRED.equals(member.getStatus())
                    ? "Not actively at work on the entry date: cover is deferred until the member is back at work"
                    : notAtWork ? "Not actively at work on the entry date: all cover is referred to underwriting"
                    : lateEntrant ? "Late entrant (eligible " + eligible + ", outside the " + category.getLateEntryDays()
                            + "-day window): all cover is referred to underwriting"
                    : SchemeMember.UNDERWRITING_PENDING.equals(member.getStatus())
                            ? "Cover above the free-cover limit is pending underwriting" : null;
            MemberChange change = new MemberChange(member, JOIN, effective, null, null, batch.getRef(), joinNote, actor);
            records.memberChanged(change);
            return change;
        }
        SchemeMember member = validator.members.get(row.getMemberKey().trim());
        BigDecimal salaryBefore = member.getSalary();
        BigDecimal coverBefore = member.getAcceptedCover();
        String note = null;
        if (LEAVE.equals(action)) {
            member.cease(effective);
        } else {
            member.changeSalary(amount(row.getSalary()), activelyAtWork, effective);
            if (Boolean.FALSE.equals(activelyAtWork) && member.getPendingCover().signum() > 0) {
                note = "Not actively at work on the increase date: the increase is referred to underwriting";
            }
        }
        MemberChange change = new MemberChange(member, action, effective, salaryBefore, coverBefore, batch.getRef(), note,
                actor);
        records.memberChanged(change);
        return change;
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
                row.getActivelyAtWork(), row.getEligibleDate(), row.getRowKey()};
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

    /** Validates rows against the scheme's categories and live membership, tracking member keys seen in the file. */
    private final class Validator {

        private final GroupScheme scheme;
        private final Map<String, BenefitCategory> categories = new HashMap<>();
        private final Map<String, SchemeMember> members;
        private final Map<String, Integer> seen = new HashMap<>();
        private final Map<String, Integer> seenRowKeys = new HashMap<>();

        Validator(GroupScheme scheme) {
            this.scheme = scheme;
            for (BenefitCategory category : records.categories(scheme)) {
                categories.put(category.getCode(), category);
            }
            this.members = records.membersOnCover(scheme);
        }

        List<String> validate(int lineNo, String[] cells) {
            List<String> errors = new ArrayList<>();
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
                errors.add("action must be JOIN, LEAVE or SALARY");
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
                return errors;
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
            if (JOIN.equals(action)) {
                validateJoin(errors, member, key, firstName, lastName, dob, categoryCode, salaryText, effective);
            } else if (member == null) {
                errors.add(action + " needs an active member; " + key + " is not active on scheme " + scheme.getRef());
            } else {
                if (effective != null && effective.isBefore(member.getJoinDate())) {
                    errors.add("effectiveDate is before the member joined (" + member.getJoinDate() + ")");
                }
                if (SALARY.equals(action) && salaryText == null) {
                    errors.add("salary is required for a SALARY change");
                }
                if (LEAVE.equals(action) && effective != null) {
                    // AT-GL04-03: a retrospective leave behind an admitted claim waits for an authorised review.
                    for (GroupClaim claim : em.createQuery("select c from GroupClaim c where c.member = :m"
                                    + " and c.status in :admitted and c.eventDate > :exit order by c.eventDate",
                                    GroupClaim.class)
                            .setParameter("m", member).setParameter("admitted", GroupClaim.ADMITTED)
                            .setParameter("exit", effective).getResultList()) {
                        errors.add("held for authorised review: claim " + claim.getRef() + " ("
                                + claim.getStatus().toLowerCase(Locale.ROOT).replace('_', ' ') + ", event "
                                + claim.getEventDate() + ") falls after this leave date");
                    }
                }
            }
            return errors;
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

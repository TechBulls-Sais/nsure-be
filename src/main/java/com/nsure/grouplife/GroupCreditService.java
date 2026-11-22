package com.nsure.grouplife;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.grouplife.GroupCreditRecords.CreditClaim;
import com.nsure.grouplife.GroupCreditRecords.CreditException;
import com.nsure.grouplife.GroupCreditRecords.CreditFile;
import com.nsure.grouplife.GroupCreditRecords.CreditPremium;
import com.nsure.grouplife.GroupCreditRecords.CreditRefund;
import com.nsure.grouplife.GroupCreditRecords.CreditRemittance;
import com.nsure.grouplife.GroupCreditRecords.LenderAgreement;
import com.nsure.grouplife.GroupCreditRecords.LoanClass;
import com.nsure.grouplife.GroupCreditRecords.LoanEvent;
import com.nsure.grouplife.GroupLifeRecords.Benefit;
import com.nsure.grouplife.GroupLifeRecords.CreditLoan;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.GroupScheme;
import com.nsure.portfolio.PartyRegister;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Group credit life (GL06). A lender scheme has an agreement (limits, loan classes, premium basis, remittance cycle,
 * refund formula). Loan bordereaux are validated row by row (disbursement, borrower age and identity, class, term,
 * elections, cap, duplicates); exceptions are referred to underwriting and only accepted rows get cover. Premiums
 * fall due on a single, annual or monthly schedule and are billed to the lender on remittance statements (the premium
 * receivable, kept apart from the loan balances the lender reports). Versioned lender update files carry balances,
 * repayments, arrears, settlements and cancellations with control totals; anomalies, and stale or missing balances,
 * are retained as exceptions. Claims pay the outstanding balance under the caps to the lender (any excess to the
 * borrower or estate) with recovery of overpayments. Settlements and cancellations refund unearned premium once per
 * loan, after a second person approves, reversing commission and reinsurance.
 *
 * <p>When a scheme has no agreement saved, the demo defaults below apply (D02/D03/D07, not approved terms); they are
 * properties under {@code nsure.group.credit.*} and are replaced by saving the scheme's lender agreement.
 */
@Service
public class GroupCreditService {

    static final Set<String> BASES = Set.of("SINGLE", "ANNUAL", "MONTHLY");
    static final Set<String> BENEFITS = Set.of("DEATH", "DISABILITY", "CRITICAL_ILLNESS", "TEMPORARY_DISABILITY");
    static final String V2 = "2.0";
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;
    private final EvidenceChain audit;
    private final PartyRegister parties;
    private final ObjectMapper mapper;
    private final Map<String, String> defaults;

    public GroupCreditService(EntityManager em, PlatformSecurityContext security, Outbox outbox, EvidenceChain audit,
            PartyRegister parties, ObjectMapper mapper,
            @Value("${nsure.group.credit.rate-per-mille:0.85}") String rate,
            @Value("${nsure.group.credit.premium-basis:SINGLE}") String basis,
            @Value("${nsure.group.credit.death-cap:750000}") String deathCap,
            @Value("${nsure.group.credit.ci-cap:350000}") String ciCap,
            @Value("${nsure.group.credit.td-monthly-cap:15000}") String tdCap,
            @Value("${nsure.group.credit.refund-deduction-percent:10}") String deduction,
            @Value("${nsure.group.credit.stale-balance-days:45}") String staleDays) {
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.audit = audit;
        this.parties = parties;
        this.mapper = mapper;
        this.defaults = Map.of("rate", rate, "basis", basis, "deathCap", deathCap, "ciCap", ciCap, "tdCap", tdCap,
                "deduction", deduction, "staleDays", staleDays);
    }

    // ── lender agreement ───────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> agreement(String schemeRef) {
        user("READ_SCHEME");
        scheme(schemeRef);
        return agreementView(terms(schemeRef), classes(schemeRef));
    }

    /**
     * Saves the lender agreement (NS-GL06-001): {lenderRef, agreementNo, effectiveFrom, premiumBasis, ratePerMille,
     * balanceBasis, premiumTiming, remittanceDay, deathCap, ciCap, tdMonthlyCap, tdMaxMonths, overCapRule, minAge,
     * maxAge, maxAgeAtEnd, maxTerm, notificationDays, claimBasis, refundMethod, refundDeductionPercent,
     * commissionPercent, cededPercent, staleBalanceDays, classes[{code, name, maxTerm, maxAmount, benefits[],
     * ratePerMille?}]}. New terms apply to loans registered afterwards.
     */
    @Transactional
    public Map<String, Object> saveAgreement(String schemeRef, JsonNode body) {
        AppUser user = user("MANAGE_SCHEME");
        GroupScheme s = scheme(schemeRef);
        LenderAgreement a = stored(schemeRef);
        boolean created = a == null;
        if (created) {
            a = defaults(schemeRef);
        }
        LenderAgreement base = a;
        a.lenderRef = optional(body, "lenderRef") == null ? base.lenderRef : optional(body, "lenderRef");
        if (a.lenderRef != null && em.createQuery("select count(l) from Lender l where l.ref = :r", Long.class)
                .setParameter("r", a.lenderRef).getSingleResult() == 0) {
            throw new IllegalArgumentException("Lender " + a.lenderRef + " is not a registered lender party");
        }
        a.agreementNo = text(body, "agreementNo", base.agreementNo, 40);
        a.effectiveFrom = optional(body, "effectiveFrom") == null ? base.effectiveFrom
                : LocalDate.parse(optional(body, "effectiveFrom"));
        a.premiumBasis = choice(body, "premiumBasis", base.premiumBasis, BASES);
        a.ratePerMille = decimal(body, "ratePerMille", base.ratePerMille, "0.0001", "100");
        a.balanceBasis = choice(body, "balanceBasis", base.balanceBasis, Set.of("ORIGINAL", "OUTSTANDING"));
        a.premiumTiming = choice(body, "premiumTiming", base.premiumTiming, Set.of("IN_ADVANCE", "IN_ARREARS"));
        a.remittanceDay = integer(body, "remittanceDay", base.remittanceDay, 1, 28);
        a.deathCap = decimal(body, "deathCap", base.deathCap, "1", "750000");
        a.ciCap = decimal(body, "ciCap", base.ciCap, "0", "350000");
        a.tdMonthlyCap = decimal(body, "tdMonthlyCap", base.tdMonthlyCap, "0", "15000");
        a.tdMaxMonths = integer(body, "tdMaxMonths", base.tdMaxMonths, 1, 24);
        a.overCapRule = choice(body, "overCapRule", base.overCapRule, Set.of("REFER", "FAIL"));
        a.minAge = integer(body, "minAge", base.minAge, 16, 70);
        a.maxAge = integer(body, "maxAge", base.maxAge, a.minAge, 80);
        a.maxAgeAtEnd = integer(body, "maxAgeAtEnd", base.maxAgeAtEnd, a.maxAge, 85);
        a.maxTerm = integer(body, "maxTerm", base.maxTerm, 1, 360);
        a.notificationDays = integer(body, "notificationDays", base.notificationDays, 1, 365);
        a.claimBasis = choice(body, "claimBasis", base.claimBasis, Set.of("OUTSTANDING_BALANCE", "SUM_INSURED"));
        a.refundMethod = choice(body, "refundMethod", base.refundMethod, Set.of("PRO_RATA", "RULE_OF_78"));
        a.refundDeductionPercent = decimal(body, "refundDeductionPercent", base.refundDeductionPercent, "0", "50");
        a.commissionPercent = decimal(body, "commissionPercent", base.commissionPercent, "0", "50");
        a.cededPercent = decimal(body, "cededPercent", base.cededPercent, "0", "100");
        a.staleBalanceDays = integer(body, "staleBalanceDays", base.staleBalanceDays, 7, 365);
        a.updatedBy = user.getUsername();
        a.updatedAt = LocalDateTime.now();
        if (created) {
            em.persist(a);
        }
        JsonNode classes = body.path("classes");
        if (classes.isArray()) {
            List<String> codes = new ArrayList<>();
            for (JsonNode c : classes) {
                String code = required(c, "code").toUpperCase(Locale.ROOT);
                codes.add(code);
                LoanClass lc = classes(schemeRef).stream().filter(x -> x.code.equals(code)).findFirst().orElse(null);
                boolean added = lc == null;
                if (added) {
                    lc = new LoanClass();
                    lc.schemeRef = schemeRef;
                    lc.code = code;
                }
                lc.name = text(c, "name", code, 80);
                lc.maxTerm = integer(c, "maxTerm", a.maxTerm, 1, a.maxTerm);
                lc.maxAmount = decimal(c, "maxAmount", a.deathCap, "1", "100000000");
                List<String> elected = new ArrayList<>();
                c.path("benefits").forEach(b -> elected.add(b.asText().toUpperCase(Locale.ROOT)));
                if (elected.isEmpty()) {
                    elected.addAll(List.of("DEATH", "DISABILITY"));
                }
                if (!BENEFITS.containsAll(elected) || !elected.contains("DEATH")) {
                    throw new IllegalArgumentException("Class " + code + ": benefits are from " + BENEFITS
                            + " and must include DEATH");
                }
                lc.benefits = String.join(",", elected);
                lc.ratePerMille = optional(c, "ratePerMille") == null ? null
                        : decimal(c, "ratePerMille", null, "0.0001", "100");
                if (added) {
                    em.persist(lc);
                }
            }
            for (LoanClass old : classes(schemeRef)) {
                if (!codes.contains(old.code)) {
                    if (em.createQuery("select count(l) from GroupCreditLoan l where l.schemeRef = :s and l.loanClass = :c",
                            Long.class).setParameter("s", schemeRef).setParameter("c", old.code).getSingleResult() > 0) {
                        throw new IllegalArgumentException("Class " + old.code + " has loans and cannot be removed");
                    }
                    em.remove(old);
                }
            }
        }
        em.flush();
        audit.record(user.getUsername(), "Lender agreement saved", "Scheme", s.getRef(), a.agreementNo);
        return agreementView(a, classes(schemeRef));
    }

    // ── loans and bordereaux ───────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> creditLoans(String schemeRef) {
        user("READ_SCHEME");
        return em.createQuery("select l from GroupCreditLoan l where l.schemeRef = :ref order by l.id desc",
                CreditLoan.class).setParameter("ref", schemeRef).getResultList().stream().map(this::loanView).toList();
    }

    /** One loan's certificate view: cover history, premium schedule, claims and refunds. */
    @Transactional(readOnly = true)
    public Map<String, Object> loan(Long id) {
        user("READ_SCHEME");
        CreditLoan l = loanById(id);
        Map<String, Object> v = loanView(l);
        v.put("history", em.createQuery("select e from GroupCreditLoanEvent e where e.loanId = :id order by e.id",
                LoanEvent.class).setParameter("id", id).getResultList().stream().map(e -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("type", e.type);
                    x.put("effectiveDate", e.effectiveDate);
                    x.put("balance", e.balance);
                    x.put("coverAmount", e.coverAmount);
                    x.put("detail", e.detail);
                    x.put("sourceRef", e.sourceRef);
                    x.put("actor", e.actor);
                    x.put("recordedAt", e.recordedAt);
                    return x;
                }).toList());
        v.put("premiums", em.createQuery("select p from GroupCreditPremium p where p.loanId = :id order by p.installmentNo",
                CreditPremium.class).setParameter("id", id).getResultList().stream().map(p -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("installmentNo", p.installmentNo);
                    x.put("dueDate", p.dueDate);
                    x.put("periodFrom", p.periodFrom);
                    x.put("periodTo", p.periodTo);
                    x.put("basisAmount", p.basisAmount);
                    x.put("ratePerMille", p.ratePerMille);
                    x.put("amount", p.amount);
                    x.put("status", p.status);
                    x.put("remittanceRef", p.remittanceRef);
                    return x;
                }).toList());
        v.put("claims", em.createQuery("select c from GroupCreditClaim c where c.loanId = :id order by c.id",
                CreditClaim.class).setParameter("id", id).getResultList().stream().map(this::claimView).toList());
        v.put("refunds", em.createQuery("select r from GroupCreditRefund r where r.loanId = :id order by r.id",
                CreditRefund.class).setParameter("id", id).getResultList().stream().map(this::refundView).toList());
        return v;
    }

    /**
     * A lender's new-loan bordereau. Version 2.0 ({batchReference, schemaVersion "2.0", controlTotals {rowCount,
     * totalLoanAmount}, rows [{lenderLoanId, borrowerName, borrowerIdNumber, dateOfBirth, loanClass, loanAmount,
     * termMonths, disbursementDate, benefits}]}) is validated in full; a file whose control totals do not reconcile is
     * rejected whole and kept. Version 1 (no schemaVersion: id, name, amount, term) is still accepted, without the
     * eligibility data. A loan already on the scheme is never added twice, and a repeated file returns its first result.
     */
    @Transactional
    public Map<String, Object> bordereau(String schemeRef, JsonNode body) {
        AppUser user = user("PROCESS_CENSUS");
        GroupScheme s = scheme(schemeRef);
        if (!"ACTIVE".equals(s.getStatus())) {
            throw new IllegalArgumentException(schemeRef + " is " + s.getStatus().toLowerCase(Locale.ROOT)
                    + " and cannot take new loans.");
        }
        Benefit credit = em.createQuery("select b from SchemeBenefit b where b.schemeRef = :ref"
                        + " and b.benefitType = 'CREDIT'", Benefit.class).setParameter("ref", schemeRef)
                .getResultStream().findFirst().orElseThrow(() -> new IllegalArgumentException(schemeRef
                        + " has no Credit benefit. Add it under the scheme's benefits first."));
        String batch = required(body, "batchReference");
        String version = optional(body, "schemaVersion") == null ? "1" : optional(body, "schemaVersion");
        if (!Set.of("1", V2).contains(version)) {
            throw new IllegalArgumentException("Unsupported bordereau version " + version + "; supported: 1, " + V2);
        }
        JsonNode rows = body.get("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("rows must list at least one loan.");
        }
        String hash = hash(body);
        CreditFile previous = file(schemeRef, batch);
        if (previous != null && !previous.fileHash.equals(hash)) {
            throw new ConflictException("Bordereau " + batch + " was already received with different content.");
        }
        // A repeated file is checked again row by row: every loan already on the scheme comes back as a duplicate.
        boolean repeated = previous != null;
        LenderAgreement terms = terms(schemeRef);
        BigDecimal cap = stored(schemeRef) != null ? terms.deathCap
                : credit.fixedAmount == null ? terms.deathCap : credit.fixedAmount;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchReference", batch);
        out.put("schemaVersion", version);
        out.put("rows", rows.size());
        JsonNode control = body.path("controlTotals");
        if (!control.isMissingNode() && !control.isNull()) {
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode r : rows) {
                total = total.add(new BigDecimal(r.path("loanAmount").asText("0")));
            }
            int rowCount = control.path("rowCount").asInt(-1);
            BigDecimal declared = control.hasNonNull("totalLoanAmount")
                    ? new BigDecimal(control.get("totalLoanAmount").asText()) : null;
            if (rowCount != rows.size() || declared != null && declared.compareTo(total) != 0) {
                out.put("status", "REJECTED");
                out.put("reason", "Control totals do not reconcile: the file has " + rows.size() + " row(s) totalling "
                        + total.toPlainString() + "; the lender declared " + rowCount + " row(s) totalling "
                        + (declared == null ? "-" : declared.toPlainString()) + ". Nothing was applied.");
                if (!repeated) {
                    saveFile(schemeRef, batch, "NEW_LOANS", version, hash, rows.size(), declared, "REJECTED", out,
                            user.getUsername());
                }
                out.put("repeated", repeated);
                return out;
            }
        }
        int accepted = 0;
        int referred = 0;
        int duplicates = 0;
        int rejected = 0;
        BigDecimal premiumTotal = BigDecimal.ZERO;
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode row : rows) {
            String id = row.path("lenderLoanId").asText("").trim();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("lenderLoanId", id);
            try {
                if (id.isEmpty() || row.path("borrowerName").asText("").isBlank()) {
                    throw new IllegalArgumentException("Loan id and borrower name are required.");
                }
                if (em.createQuery("select count(l) from GroupCreditLoan l where l.schemeRef = :ref"
                        + " and l.lenderLoanId = :id", Long.class).setParameter("ref", schemeRef).setParameter("id", id)
                        .getSingleResult() > 0) {
                    duplicates++;
                    result.put("outcome", "DUPLICATE");
                    result.put("note", "Already on the scheme: no second cover or premium.");
                    results.add(result);
                    continue;
                }
                CreditLoan l = register(schemeRef, row, version, terms, cap, batch, legacyRate(body, terms));
                if ("ACTIVE".equals(l.status)) {
                    activate(l, terms, batch, user.getUsername());
                    accepted++;
                    premiumTotal = premiumTotal.add(l.premium);
                } else {
                    event(l, "REFERRED", l.disbursementDate, l.note, batch, user.getUsername());
                    referred++;
                }
                result.put("outcome", l.status);
                result.put("certificateNo", l.certificateNo);
                result.put("note", l.note);
            } catch (RuntimeException ex) {
                rejected++;
                result.put("outcome", "REJECTED");
                result.put("note", ex.getMessage());
            }
            results.add(result);
        }
        out.put("status", "PROCESSED");
        out.put("accepted", accepted);
        out.put("referred", referred);
        out.put("duplicates", duplicates);
        out.put("rejected", rejected);
        out.put("premiumTotal", Money.of(premiumTotal));
        out.put("results", results);
        if (!repeated) {
            saveFile(schemeRef, batch, "NEW_LOANS", version, hash, rows.size(), null, "PROCESSED", out,
                    user.getUsername());
        }
        out.put("repeated", repeated);
        outbox.publish(Events.CREDIT_BORDEREAU_PROCESSED, schemeRef, Map.of("schemeRef", schemeRef, "batchReference",
                batch, "accepted", accepted, "referred", referred, "duplicates", duplicates, "rejected", rejected,
                "schemaVersion", version), user.getUsername());
        return out;
    }

    private BigDecimal legacyRate(JsonNode body, LenderAgreement terms) {
        String rate = optional(body, "ratePerMille");
        return rate == null ? null : new BigDecimal(rate);
    }

    /** Validates one row into a loan: ACTIVE when it passes, REFERRED with the reason, or an exception (rejected). */
    private CreditLoan register(String schemeRef, JsonNode row, String version, LenderAgreement terms, BigDecimal cap,
            String batch, BigDecimal legacyRate) {
        BigDecimal amount = Money.of(new BigDecimal(row.path("loanAmount").asText("0")));
        int term = row.path("termMonths").asInt(0);
        if (amount.signum() <= 0 || term < 1 || term > terms.maxTerm) {
            throw new IllegalArgumentException("Loan amount and a term of 1-" + terms.maxTerm + " months are required.");
        }
        CreditLoan l = new CreditLoan();
        l.schemeRef = schemeRef;
        l.lenderLoanId = row.path("lenderLoanId").asText().trim();
        l.certificateNo = schemeRef + "-L" + l.lenderLoanId;
        l.borrowerName = row.path("borrowerName").asText().trim();
        l.loanAmount = amount;
        l.termMonths = term;
        l.batchRef = batch;
        l.createdAt = LocalDateTime.now();
        l.interfaceVersion = version;
        l.premiumBasis = terms.premiumBasis;
        l.ratePerMille = legacyRate != null ? legacyRate : terms.ratePerMille;
        List<String> refer = new ArrayList<>();
        LocalDate today = LocalDate.now();
        if (V2.equals(version)) {
            String idNumber = row.path("borrowerIdNumber").asText("").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (idNumber.isEmpty()) {
                throw new IllegalArgumentException("borrowerIdNumber is required.");
            }
            parties.identity(idNumber.matches("[0-9]+") ? PartyRegister.OMANG : PartyRegister.PASSPORT, idNumber);
            l.borrowerIdNumber = idNumber;
            l.dateOfBirth = date(row, "dateOfBirth");
            l.disbursementDate = date(row, "disbursementDate");
            if (l.disbursementDate.isAfter(today)) {
                throw new IllegalArgumentException("Disbursement date " + l.disbursementDate + " is in the future.");
            }
            if (l.disbursementDate.isBefore(terms.effectiveFrom)) {
                throw new IllegalArgumentException("Disbursed " + l.disbursementDate + ", before the agreement started on "
                        + terms.effectiveFrom + ".");
            }
            int age = Period.between(l.dateOfBirth, l.disbursementDate).getYears();
            if (age < terms.minAge || age > terms.maxAge) {
                throw new IllegalArgumentException("Borrower aged " + age + " at disbursement is outside the eligible ages "
                        + terms.minAge + "-" + terms.maxAge + ".");
            }
            int ageAtEnd = Period.between(l.dateOfBirth, l.disbursementDate.plusMonths(term)).getYears();
            if (ageAtEnd > terms.maxAgeAtEnd) {
                refer.add("borrower would be " + ageAtEnd + " at the end of the term (limit " + terms.maxAgeAtEnd + ")");
            }
            if (ChronoUnit.DAYS.between(l.disbursementDate, today) > terms.notificationDays) {
                refer.add("notified " + ChronoUnit.DAYS.between(l.disbursementDate, today) + " days after disbursement (limit "
                        + terms.notificationDays + ")");
            }
            List<LoanClass> classes = classes(schemeRef);
            String code = row.path("loanClass").asText("").trim().toUpperCase(Locale.ROOT);
            LoanClass lc = classes.stream().filter(c -> c.code.equals(code)).findFirst().orElse(null);
            if (lc == null && !classes.isEmpty()) {
                throw new IllegalArgumentException("Unknown loan class " + (code.isEmpty() ? "(none)" : code) + "; classes: "
                        + classes.stream().map(c -> c.code).toList() + ".");
            }
            List<String> elected = new ArrayList<>();
            JsonNode b = row.path("benefits");
            if (b.isArray()) {
                b.forEach(x -> elected.add(x.asText().trim().toUpperCase(Locale.ROOT)));
            } else if (!b.asText("").isBlank()) {
                Arrays.stream(b.asText().split("[,;|]")).map(x -> x.trim().toUpperCase(Locale.ROOT))
                        .filter(x -> !x.isEmpty()).forEach(elected::add);
            }
            List<String> allowed = lc == null ? List.copyOf(BENEFITS) : List.of(lc.benefits.split(","));
            if (elected.isEmpty()) {
                elected.addAll(allowed);
            }
            if (!elected.contains("DEATH")) {
                throw new IllegalArgumentException("The death benefit must be elected.");
            }
            for (String e : elected) {
                if (!allowed.contains(e)) {
                    throw new IllegalArgumentException("Benefit " + e + " is not available on loan class "
                            + (lc == null ? "-" : lc.code) + ".");
                }
            }
            l.benefits = String.join(",", elected);
            if (lc != null) {
                l.loanClass = lc.code;
                if (term > lc.maxTerm) {
                    throw new IllegalArgumentException("Term " + term + " months is above the " + lc.code + " maximum of "
                            + lc.maxTerm + ".");
                }
                if (amount.compareTo(lc.maxAmount) > 0) {
                    refer.add("amount above the " + lc.code + " class maximum of " + Money.text(lc.maxAmount));
                }
                if (lc.ratePerMille != null) {
                    l.ratePerMille = lc.ratePerMille;
                }
            }
        } else {
            l.disbursementDate = today;
            l.benefits = "DEATH,DISABILITY";
        }
        l.startDate = l.disbursementDate;
        boolean over = amount.compareTo(cap) > 0;
        if (over && "FAIL".equals(terms.overCapRule)) {
            throw new IllegalArgumentException("Loan " + Money.text(amount) + " is above the scheme cap of "
                    + Money.text(cap) + ": refused under the agreement's over-cap rule.");
        }
        if (over) {
            refer.add("Loan " + Money.text(amount) + " is above the scheme cap of " + Money.text(cap)
                    + ": referred to underwriting; cover held at the cap");
        }
        l.coverAmount = over ? cap : amount;
        l.balance = amount;
        l.balanceDate = l.disbursementDate;
        l.status = refer.isEmpty() ? "ACTIVE" : "REFERRED";
        String note = refer.isEmpty() ? (V2.equals(version) ? null
                : "Interface v1: borrower age and identity were not supplied, so eligibility was not checked.")
                : String.join("; ", refer) + ".";
        l.note = note == null || note.length() <= 300 ? note : note.substring(0, 297) + "...";
        l.premium = single(l.coverAmount, l.ratePerMille, term);
        em.persist(l);
        return l;
    }

    /** Puts a loan on cover: first premium instalment, history and BorrowerCoverActivated. */
    private void activate(CreditLoan l, LenderAgreement terms, String source, String actor) {
        l.status = "ACTIVE";
        l.coverEndDate = l.disbursementDate.plusMonths(l.termMonths).minusDays(1);
        if ("SINGLE".equals(l.premiumBasis)) {
            l.premium = single(l.coverAmount, l.ratePerMille, l.termMonths);
            installment(l, 1, l.disbursementDate, l.coverEndDate, l.coverAmount, l.premium, terms);
        } else {
            scheduleDue(l, l.disbursementDate, terms);
            // The premium shown is the expected total over the term on the original amount.
            l.premium = single(l.coverAmount, l.ratePerMille, l.termMonths);
        }
        event(l, "ACTIVATED", l.disbursementDate, "Cover " + Money.text(l.coverAmount) + " on " + l.premiumBasis.toLowerCase(
                Locale.ROOT) + " premium at " + l.ratePerMille.stripTrailingZeros().toPlainString() + " per mille", source,
                actor);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemeRef", l.schemeRef);
        data.put("certificateNo", l.certificateNo);
        data.put("coverAmount", l.coverAmount);
        data.put("premiumBasis", l.premiumBasis);
        data.put("disbursementDate", l.disbursementDate.toString());
        outbox.publish(Events.BORROWER_COVER_ACTIVATED, l.certificateNo, data, actor);
    }

    private static BigDecimal single(BigDecimal cover, BigDecimal rate, int term) {
        return cover.multiply(rate).multiply(BigDecimal.valueOf(term)).divide(THOUSAND, 2, RoundingMode.HALF_UP);
    }

    /** Monthly and annual instalments due up to the date (single premium has one, at activation). */
    private void scheduleDue(CreditLoan l, LocalDate upTo, LenderAgreement terms) {
        if ("SINGLE".equals(l.premiumBasis) || !"ACTIVE".equals(l.status)) {
            return;
        }
        int step = "ANNUAL".equals(l.premiumBasis) ? 12 : 1;
        int existing = em.createQuery("select count(p) from GroupCreditPremium p where p.loanId = :id", Long.class)
                .setParameter("id", l.id).getSingleResult().intValue();
        for (int n = existing; n * step < l.termMonths; n++) {
            LocalDate from = l.disbursementDate.plusMonths((long) n * step);
            LocalDate to = from.plusMonths(Math.min(step, l.termMonths - n * step)).minusDays(1);
            LocalDate due = "IN_ARREARS".equals(terms.premiumTiming) ? to : from;
            if (due.isAfter(upTo) || l.coverEndDate != null && from.isAfter(l.coverEndDate)) {
                break;
            }
            BigDecimal basis = "OUTSTANDING".equals(terms.balanceBasis) && l.balance != null
                    ? l.balance.min(l.coverAmount) : l.coverAmount;
            int months = (int) ChronoUnit.MONTHS.between(from, to.plusDays(1));
            BigDecimal amount = basis.multiply(l.ratePerMille).multiply(BigDecimal.valueOf(Math.max(1, months)))
                    .divide(THOUSAND, 2, RoundingMode.HALF_UP);
            installment(l, n + 1, from, to, basis, amount, terms);
        }
    }

    private void installment(CreditLoan l, int no, LocalDate from, LocalDate to, BigDecimal basis, BigDecimal amount,
            LenderAgreement terms) {
        CreditPremium p = new CreditPremium();
        p.loanId = l.id;
        p.schemeRef = l.schemeRef;
        p.installmentNo = no;
        p.periodFrom = from;
        p.periodTo = to;
        p.dueDate = "IN_ARREARS".equals(terms.premiumTiming) && !"SINGLE".equals(l.premiumBasis) ? to : from;
        p.basisAmount = basis;
        p.ratePerMille = l.ratePerMille;
        p.amount = amount;
        p.status = "DUE";
        em.persist(p);
    }

    /** {decision ACCEPT|DECLINE, coverAmount?, note}: the underwriting decision on a referred loan. */
    @Transactional
    public Map<String, Object> underwrite(Long id, JsonNode body) {
        AppUser user = user("UNDERWRITE_MEMBER");
        CreditLoan l = loanById(id);
        if (!"REFERRED".equals(l.status)) {
            throw new ConflictException(l.certificateNo + " is " + l.status.toLowerCase(Locale.ROOT)
                    + "; only a referred loan is decided.");
        }
        String decision = required(body, "decision").toUpperCase(Locale.ROOT);
        String note = required(body, "note");
        LenderAgreement terms = terms(l.schemeRef);
        if ("ACCEPT".equals(decision)) {
            String coverText = optional(body, "coverAmount");
            BigDecimal coverAmount = coverText == null ? l.coverAmount : Money.of(new BigDecimal(coverText));
            if (coverAmount.signum() <= 0 || coverAmount.compareTo(l.loanAmount) > 0) {
                throw new IllegalArgumentException("Accepted cover must be above zero and at most the loan amount.");
            }
            l.coverAmount = coverAmount;
            l.note = ("Accepted by " + user.getUsername() + ": " + note);
            activate(l, terms, "UW", user.getUsername());
            event(l, "UW_ACCEPTED", LocalDate.now(), note, "UW", user.getUsername());
        } else if ("DECLINE".equals(decision)) {
            l.status = "DECLINED";
            l.note = ("Declined by " + user.getUsername() + ": " + note);
            event(l, "UW_DECLINED", LocalDate.now(), note, "UW", user.getUsername());
        } else {
            throw new IllegalArgumentException("Decision must be ACCEPT or DECLINE.");
        }
        if (l.note.length() > 300) {
            l.note = l.note.substring(0, 297) + "...";
        }
        return loanView(l);
    }

    // ── lender update files ────────────────────────────────────────────────────────────────────────

    /**
     * A lender update file (NS-GL06-004), interface version 2.0: {fileReference, schemaVersion, asOfDate,
     * controlTotals {rowCount, totalBalance}, rows [{lenderLoanId, type BALANCE|REPAYMENT|ARREARS|SETTLEMENT|
     * CANCELLATION, balance?, amountPaid?, arrearsMonths?, effectiveDate?}]}. Control totals must reconcile or the file
     * is rejected whole (and kept); the same reference again returns the first result.
     */
    @Transactional
    public Map<String, Object> updateFile(String schemeRef, JsonNode body) {
        AppUser user = user("PROCESS_CENSUS");
        scheme(schemeRef);
        String ref = required(body, "fileReference");
        String version = required(body, "schemaVersion");
        if (!V2.equals(version)) {
            throw new IllegalArgumentException("Unsupported lender interface version " + version + "; supported: " + V2);
        }
        JsonNode rows = body.path("rows");
        if (!rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("rows must list at least one loan update.");
        }
        String hash = hash(body);
        CreditFile previous = file(schemeRef, ref);
        if (previous != null) {
            if (!previous.fileHash.equals(hash)) {
                throw new ConflictException("File " + ref + " was already received with different content.");
            }
            return parseResult(previous);
        }
        LocalDate asOf = optional(body, "asOfDate") == null ? LocalDate.now() : LocalDate.parse(optional(body, "asOfDate"));
        BigDecimal balanceTotal = BigDecimal.ZERO;
        for (JsonNode r : rows) {
            if (r.hasNonNull("balance")) {
                balanceTotal = balanceTotal.add(new BigDecimal(r.get("balance").asText()));
            }
        }
        JsonNode control = body.path("controlTotals");
        int declaredRows = control.path("rowCount").asInt(-1);
        BigDecimal declaredBalance = control.hasNonNull("totalBalance") ? new BigDecimal(control.get("totalBalance").asText())
                : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileReference", ref);
        out.put("schemaVersion", version);
        out.put("rows", rows.size());
        if (declaredRows != rows.size() || declaredBalance == null || declaredBalance.compareTo(balanceTotal) != 0) {
            out.put("status", "REJECTED");
            out.put("reason", "Control totals do not reconcile: " + rows.size() + " row(s) with balances totalling "
                    + balanceTotal.toPlainString() + " against the declared " + declaredRows + " row(s) and "
                    + (declaredBalance == null ? "no total" : declaredBalance.toPlainString()) + ". Nothing was applied.");
            saveFile(schemeRef, ref, "UPDATES", version, hash, rows.size(), declaredBalance, "REJECTED", out,
                    user.getUsername());
            return out;
        }
        LenderAgreement terms = terms(schemeRef);
        List<Map<String, Object>> results = new ArrayList<>();
        int applied = 0;
        int exceptions = 0;
        for (JsonNode r : rows) {
            String id = r.path("lenderLoanId").asText("").trim();
            String type = r.path("type").asText("BALANCE").toUpperCase(Locale.ROOT);
            LocalDate effective = r.hasNonNull("effectiveDate") ? LocalDate.parse(r.get("effectiveDate").asText()) : asOf;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("lenderLoanId", id);
            result.put("type", type);
            CreditLoan l = em.createQuery("select l from GroupCreditLoan l where l.schemeRef = :s and l.lenderLoanId = :id",
                    CreditLoan.class).setParameter("s", schemeRef).setParameter("id", id).getResultStream().findFirst()
                    .orElse(null);
            if (l == null) {
                exception(schemeRef, null, id, "UNKNOWN_LOAN", "Update for a loan not on the scheme (" + type + ")", ref);
                result.put("outcome", "EXCEPTION");
                result.put("note", "Unknown loan; kept as an exception.");
                exceptions++;
                results.add(result);
                continue;
            }
            String outcome = switch (type) {
                case "BALANCE" -> balance(l, money(r, "balance"), effective, ref, user.getUsername(), terms);
                case "REPAYMENT" -> balance(l, (l.balance == null ? l.loanAmount : l.balance).subtract(money(r, "amountPaid"))
                        .max(BigDecimal.ZERO), effective, ref, user.getUsername(), terms);
                case "ARREARS" -> arrears(l, r.path("arrearsMonths").asInt(0), effective, ref, user.getUsername());
                case "SETTLEMENT", "CANCELLATION" -> {
                    if (Set.of("SETTLED", "CANCELLED").contains(l.status) || activeRefund(l) != null) {
                        yield "DUPLICATE: " + l.certificateNo + " is already " + l.status.toLowerCase(Locale.ROOT)
                                + "; no second refund";
                    }
                    if (!"ACTIVE".equals(l.status)) {
                        yield "EXCEPTION: " + l.certificateNo + " is " + l.status.toLowerCase(Locale.ROOT);
                    }
                    CreditRefund refund = close(l, effective, type, ref, user.getUsername(), terms);
                    yield "APPLIED: cover closed" + (refund == null ? "" : ", refund " + refund.ref + " of "
                            + Money.text(refund.refundAmount) + " awaiting approval");
                }
                default -> "EXCEPTION: unknown update type " + type;
            };
            if (outcome.startsWith("EXCEPTION")) {
                exceptions++;
            } else {
                applied++;
            }
            result.put("outcome", outcome.contains(":") ? outcome.substring(0, outcome.indexOf(':')) : outcome);
            result.put("note", outcome.contains(":") ? outcome.substring(outcome.indexOf(':') + 1).trim() : null);
            results.add(result);
        }
        refreshBalanceExceptions(schemeRef, terms);
        out.put("status", "PROCESSED");
        out.put("applied", applied);
        out.put("exceptions", exceptions);
        out.put("results", results);
        saveFile(schemeRef, ref, "UPDATES", version, hash, rows.size(), declaredBalance, "PROCESSED", out,
                user.getUsername());
        return out;
    }

    private String balance(CreditLoan l, BigDecimal balance, LocalDate effective, String source, String actor,
            LenderAgreement terms) {
        if (balance.compareTo(l.loanAmount) > 0) {
            exception(l.schemeRef, l.id, l.lenderLoanId, "BALANCE_ABOVE_LOAN", "Reported balance " + Money.text(balance)
                    + " is above the loan amount " + Money.text(l.loanAmount) + "; a top-up is not covered", source);
            return "EXCEPTION: balance above the loan amount";
        }
        l.balance = Money.of(balance);
        l.balanceDate = effective;
        if ("OUTSTANDING".equals(terms.balanceBasis) && "ACTIVE".equals(l.status)) {
            l.coverAmount = l.balance.min(terms.deathCap);
        }
        resolveExceptions(l, Set.of("STALE_BALANCE", "MISSING_BALANCE"), "Balance received in " + source);
        event(l, "BALANCE", effective, "Balance " + Money.text(l.balance), source, actor);
        if (l.balance.signum() == 0 && "ACTIVE".equals(l.status)) {
            exception(l.schemeRef, l.id, l.lenderLoanId, "ZERO_BALANCE", "Zero balance reported: a settlement notice is"
                    + " expected", source);
        }
        return "APPLIED: balance " + Money.text(l.balance);
    }

    private String arrears(CreditLoan l, int months, LocalDate effective, String source, String actor) {
        l.arrearsMonths = months;
        event(l, "ARREARS", effective, months + " month(s) in arrears", source, actor);
        if (months >= 3) {
            exception(l.schemeRef, l.id, l.lenderLoanId, "ARREARS", months + " months in arrears: cover continues under the"
                    + " agreement; lender to confirm recovery action (demo threshold 3 months)", source);
        }
        return "APPLIED: arrears " + months + " month(s)";
    }

    /** Stale or missing balances on active loans become retained exceptions (demo threshold: stale-balance days). */
    private void refreshBalanceExceptions(String schemeRef, LenderAgreement terms) {
        LocalDate limit = LocalDate.now().minusDays(terms.staleBalanceDays);
        for (CreditLoan l : em.createQuery("select l from GroupCreditLoan l where l.schemeRef = :s and l.status = 'ACTIVE'",
                CreditLoan.class).setParameter("s", schemeRef).getResultList()) {
            boolean reported = em.createQuery("select count(e) from GroupCreditLoanEvent e where e.loanId = :id"
                    + " and e.type = 'BALANCE'", Long.class).setParameter("id", l.id).getSingleResult() > 0;
            if (!reported && l.disbursementDate != null && l.disbursementDate.isBefore(limit)) {
                exception(schemeRef, l.id, l.lenderLoanId, "MISSING_BALANCE", "No balance reported since disbursement on "
                        + l.disbursementDate + " (more than " + terms.staleBalanceDays + " days)", null);
            } else if (reported && l.balanceDate != null && l.balanceDate.isBefore(limit)) {
                exception(schemeRef, l.id, l.lenderLoanId, "STALE_BALANCE", "Last balance " + Money.text(l.balance)
                        + " is from " + l.balanceDate + " (more than " + terms.staleBalanceDays + " days old)", null);
            }
        }
    }

    @Transactional
    public List<Map<String, Object>> exceptions(String schemeRef) {
        user("READ_SCHEME");
        scheme(schemeRef);
        refreshBalanceExceptions(schemeRef, terms(schemeRef));
        return em.createQuery("select e from GroupCreditException e where e.schemeRef = :s order by e.id desc",
                CreditException.class).setParameter("s", schemeRef).getResultList().stream().map(e -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("id", e.id);
                    v.put("loanId", e.loanId);
                    v.put("lenderLoanId", e.lenderLoanId);
                    v.put("type", e.type);
                    v.put("detail", e.detail);
                    v.put("fileReference", e.fileReference);
                    v.put("status", e.status);
                    v.put("raisedAt", e.raisedAt);
                    v.put("resolvedBy", e.resolvedBy);
                    v.put("resolutionNote", e.resolutionNote);
                    return v;
                }).toList();
    }

    /** {note}: resolves a lender-data exception by hand. */
    @Transactional
    public Map<String, Object> resolveException(Long id, JsonNode body) {
        AppUser user = user("PROCESS_CENSUS");
        CreditException e = em.find(CreditException.class, id);
        if (e == null) {
            throw new NotFoundException("Exception " + id + " not found");
        }
        if (!"OPEN".equals(e.status)) {
            throw new ConflictException("Exception " + id + " is already resolved");
        }
        e.status = "RESOLVED";
        e.resolvedBy = user.getUsername();
        e.resolvedAt = LocalDateTime.now();
        e.resolutionNote = cut(required(body, "note"), 300);
        return Map.of("id", e.id, "status", e.status);
    }

    private void exception(String schemeRef, Long loanId, String lenderLoanId, String type, String detail, String file) {
        if (loanId != null && em.createQuery("select count(e) from GroupCreditException e where e.loanId = :l"
                + " and e.type = :t and e.status = 'OPEN'", Long.class).setParameter("l", loanId).setParameter("t", type)
                .getSingleResult() > 0) {
            return;
        }
        CreditException e = new CreditException();
        e.schemeRef = schemeRef;
        e.loanId = loanId;
        e.lenderLoanId = lenderLoanId;
        e.type = type;
        e.detail = cut(detail, 500);
        e.fileReference = file;
        e.status = "OPEN";
        e.raisedAt = LocalDateTime.now();
        em.persist(e);
    }

    private void resolveExceptions(CreditLoan l, Set<String> types, String note) {
        for (CreditException e : em.createQuery("select e from GroupCreditException e where e.loanId = :l"
                + " and e.type in :t and e.status = 'OPEN'", CreditException.class).setParameter("l", l.id)
                .setParameter("t", types).getResultList()) {
            e.status = "RESOLVED";
            e.resolvedBy = "system";
            e.resolvedAt = LocalDateTime.now();
            e.resolutionNote = cut(note, 300);
        }
    }

    // ── premium remittance ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> remittances(String schemeRef) {
        user("READ_SCHEME");
        return em.createQuery("select r from GroupCreditRemittance r where r.schemeRef = :s order by r.period desc",
                CreditRemittance.class).setParameter("s", schemeRef).getResultList().stream().map(this::remittanceView)
                .toList();
    }

    /**
     * {period yyyy-MM}: the lender's premium statement for the period: every instalment due by its end, billed once.
     * The premium receivable posts through SchemeInvoiceIssued (type CREDIT_REMITTANCE), with commission accrued and
     * premium ceded at the agreement's rates.
     */
    @Transactional
    public Map<String, Object> remit(String schemeRef, JsonNode body) {
        AppUser user = user("BILL_SCHEME");
        scheme(schemeRef);
        YearMonth period = YearMonth.parse(required(body, "period"));
        CreditRemittance existing = em.createQuery("select r from GroupCreditRemittance r where r.schemeRef = :s"
                + " and r.period = :p", CreditRemittance.class).setParameter("s", schemeRef)
                .setParameter("p", period.toString()).getResultStream().findFirst().orElse(null);
        if (existing != null) {
            return remittanceView(existing);
        }
        LenderAgreement terms = terms(schemeRef);
        LocalDate end = period.atEndOfMonth();
        for (CreditLoan l : em.createQuery("select l from GroupCreditLoan l where l.schemeRef = :s and l.status = 'ACTIVE'",
                CreditLoan.class).setParameter("s", schemeRef).getResultList()) {
            scheduleDue(l, end, terms);
        }
        em.flush();
        List<CreditPremium> due = em.createQuery("select p from GroupCreditPremium p where p.schemeRef = :s"
                + " and p.status = 'DUE' and p.dueDate <= :end order by p.loanId, p.installmentNo", CreditPremium.class)
                .setParameter("s", schemeRef).setParameter("end", end).getResultList();
        if (due.isEmpty()) {
            throw new IllegalArgumentException("No premium is due from the lender up to the end of " + period + ".");
        }
        CreditRemittance r = new CreditRemittance();
        r.ref = nextRef("GCR-", "select r.ref from GroupCreditRemittance r");
        r.schemeRef = schemeRef;
        r.period = period.toString();
        r.amount = due.stream().map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        r.paidAmount = BigDecimal.ZERO.setScale(2);
        r.outstanding = r.amount;
        r.installments = due.size();
        r.commission = r.amount.multiply(terms.commissionPercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        r.ceded = r.amount.multiply(terms.cededPercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        r.status = "ISSUED";
        r.issuedBy = user.getUsername();
        r.issuedAt = LocalDateTime.now();
        em.persist(r);
        due.forEach(p -> {
            p.status = "BILLED";
            p.remittanceRef = r.ref;
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemeRef", schemeRef);
        data.put("invoiceRef", r.ref);
        data.put("invoiceType", "CREDIT_REMITTANCE");
        data.put("period", r.period);
        data.put("installments", r.installments);
        data.put("amount", r.amount.toPlainString());
        outbox.publish(Events.SCHEME_INVOICE_ISSUED, r.ref, data, user.getUsername());
        if (r.commission.signum() > 0) {
            outbox.publish(Events.COMMISSION_ACCRUED, r.ref, Map.of("schemeRef", schemeRef, "remittanceRef", r.ref,
                    "payee", terms.lenderRef == null ? schemeRef : terms.lenderRef, "amount", r.commission.toPlainString()),
                    user.getUsername());
        }
        if (r.ceded.signum() > 0) {
            outbox.publish(Events.RISK_CEDED, r.ref, Map.of("schemeRef", schemeRef, "remittanceRef", r.ref,
                    "cededPremium", r.ceded.toPlainString(), "amount", r.ceded.toPlainString()), user.getUsername());
        }
        refreshBalanceExceptions(schemeRef, terms);
        return remittanceView(r);
    }

    /**
     * {reference, amount, rows?[{lenderLoanId, amount}]}: the lender's remittance against a statement, reconciled loan
     * by loan when the lender sends its rows; mismatches are kept as exceptions. A repeated reference is ignored.
     */
    @Transactional
    public Map<String, Object> remittanceReceipt(String ref, JsonNode body) {
        AppUser user = user("BILL_SCHEME");
        CreditRemittance r = em.createQuery("select r from GroupCreditRemittance r where r.ref = :ref",
                CreditRemittance.class).setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Remittance " + ref + " not found"));
        String reference = required(body, "reference");
        String hash = hash(body);
        CreditFile previous = file(r.schemeRef, reference);
        if (previous != null) {
            return remittanceView(r);
        }
        BigDecimal amount = Money.of(new BigDecimal(required(body, "amount")));
        if (amount.signum() <= 0 || amount.compareTo(r.outstanding) > 0) {
            throw new IllegalArgumentException("The remittance must be above zero and at most the "
                    + r.outstanding.toPlainString() + " outstanding on " + ref + ".");
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        JsonNode rows = body.path("rows");
        if (rows.isArray() && !rows.isEmpty()) {
            Map<String, BigDecimal> billed = new LinkedHashMap<>();
            for (Object[] x : em.createQuery("select l.lenderLoanId, sum(p.amount) from GroupCreditPremium p,"
                    + " GroupCreditLoan l where l.id = p.loanId and p.remittanceRef = :r group by l.lenderLoanId",
                    Object[].class).setParameter("r", ref).getResultList()) {
                billed.put((String) x[0], (BigDecimal) x[1]);
            }
            int matched = 0;
            List<String> mismatches = new ArrayList<>();
            BigDecimal rowsTotal = BigDecimal.ZERO;
            for (JsonNode row : rows) {
                String id = row.path("lenderLoanId").asText("");
                BigDecimal paid = new BigDecimal(row.path("amount").asText("0"));
                rowsTotal = rowsTotal.add(paid);
                BigDecimal expected = billed.remove(id);
                if (expected == null) {
                    mismatches.add(id + " not on the statement (" + paid.toPlainString() + ")");
                } else if (expected.compareTo(paid) != 0) {
                    mismatches.add(id + " paid " + paid.toPlainString() + " of " + expected.toPlainString());
                } else {
                    matched++;
                }
            }
            billed.forEach((id, expected) -> mismatches.add(id + " not remitted (" + expected.toPlainString() + ")"));
            if (rowsTotal.compareTo(amount) != 0) {
                mismatches.add(0, "rows total " + rowsTotal.toPlainString() + " but remittance is " + amount.toPlainString());
            }
            rec.put("matched", matched);
            rec.put("mismatches", mismatches);
            if (!mismatches.isEmpty()) {
                exception(r.schemeRef, null, null, "REMITTANCE_MISMATCH", ref + " / " + reference + ": "
                        + String.join("; ", mismatches), reference);
            }
            r.reconciliation = cut(reference + ": " + matched + " loan(s) matched"
                    + (mismatches.isEmpty() ? ", reconciled" : "; " + String.join("; ", mismatches)), 1000);
        }
        r.paidAmount = r.paidAmount.add(amount);
        r.outstanding = r.amount.subtract(r.paidAmount);
        r.status = r.outstanding.signum() <= 0 ? "PAID" : "PARTIALLY_PAID";
        rec.put("amount", amount);
        saveFile(r.schemeRef, reference, "REMITTANCE", V2, hash, rows.isArray() ? rows.size() : 0, amount, "PROCESSED",
                rec, user.getUsername());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemeRef", r.schemeRef);
        data.put("invoiceRef", r.ref);
        data.put("receiptRef", reference);
        data.put("amount", amount.toPlainString());
        data.put("outstanding", r.outstanding.toPlainString());
        outbox.publish(Events.SPONSOR_RECEIPT_ALLOCATED, r.ref + "/" + reference, data, user.getUsername());
        return remittanceView(r);
    }

    // ── settlement, cancellation and refunds ───────────────────────────────────────────────────────

    /** The early-settlement preview: the refund a settlement on the date would give, with its formula. */
    @Transactional(readOnly = true)
    public Map<String, Object> settlementPreview(Long id, String settlementDate) {
        user("READ_SCHEME");
        CreditLoan l = loanById(id);
        LocalDate date = settlementDate == null || settlementDate.isBlank() ? LocalDate.now()
                : LocalDate.parse(settlementDate.trim());
        long used = monthsUsed(l, date);
        Map<String, Object> v = loanView(l);
        v.put("settlementDate", date);
        v.put("monthsUsed", used);
        v.put("monthsUnearned", l.termMonths - used);
        CreditRefund existing = activeRefund(l);
        if (existing != null) {
            v.put("refundPreview", existing.refundAmount);
        } else {
            Calc c = calc(l, date, "SETTLEMENT", terms(l.schemeRef));
            v.put("refundPreview", c.refund);
            v.put("grossUnearned", c.gross);
            v.put("deduction", c.deduction);
            v.put("commissionReversal", c.commission);
            v.put("reinsuranceReversal", c.reinsurance);
            v.put("formula", c.formula);
        }
        v.put("alreadySettled", Set.of("SETTLED", "CANCELLED").contains(l.status));
        return v;
    }

    /** {settlementDate?}: closes the borrower's cover; the unearned-premium refund waits for a second person. */
    @Transactional
    public Map<String, Object> settleLoan(Long id, JsonNode body) {
        AppUser user = user("BILL_SCHEME");
        CreditLoan l = loanById(id);
        if (Set.of("SETTLED", "CANCELLED").contains(l.status)) {
            return loanView(l);
        }
        if (!"ACTIVE".equals(l.status)) {
            throw new ConflictException(l.certificateNo + " is " + l.status.toLowerCase(Locale.ROOT)
                    + "; only a loan on cover can be settled.");
        }
        LocalDate date = optional(body, "settlementDate") == null ? LocalDate.now()
                : LocalDate.parse(optional(body, "settlementDate"));
        close(l, date, "SETTLEMENT", "MANUAL", user.getUsername(), terms(l.schemeRef));
        audit.record(user.getUsername(), "Group credit loan settled", "Scheme", l.schemeRef, l.certificateNo
                + " refund " + Money.text(l.refund));
        return loanView(l);
    }

    private CreditRefund close(CreditLoan l, LocalDate date, String reason, String source, String actor,
            LenderAgreement terms) {
        Calc c = calc(l, date, reason, terms);
        l.status = "CANCELLATION".equals(reason) ? "CANCELLED" : "SETTLED";
        l.settledOn = date;
        l.coverEndDate = date;
        l.refund = c.refund;
        l.note = cut(("CANCELLATION".equals(reason) ? "Cancelled: " : "Settled early: ") + c.formula, 300);
        for (CreditPremium p : em.createQuery("select p from GroupCreditPremium p where p.loanId = :id"
                + " and p.status = 'DUE' and p.periodFrom > :d", CreditPremium.class).setParameter("id", l.id)
                .setParameter("d", date).getResultList()) {
            p.status = "CANCELLED";
        }
        event(l, l.status, date, l.note, source, actor);
        if (c.refund.signum() <= 0 || activeRefund(l) != null) {
            return null;
        }
        CreditRefund r = new CreditRefund();
        r.ref = nextRef("GCF-", "select r.ref from GroupCreditRefund r");
        r.loanId = l.id;
        r.schemeRef = l.schemeRef;
        r.reason = reason;
        r.effectiveDate = date;
        r.monthsUnearned = c.monthsUnearned;
        r.grossUnearned = c.gross;
        r.deduction = c.deduction;
        r.refundAmount = c.refund;
        r.commissionReversal = c.commission;
        r.reinsuranceReversal = c.reinsurance;
        r.formula = cut(c.formula, 300);
        r.status = "PENDING";
        r.sourceRef = source;
        r.requestedBy = actor;
        r.requestedAt = LocalDateTime.now();
        em.persist(r);
        return r;
    }

    /**
     * {approve, note}: the refund approval, by someone other than who requested it. Approval publishes
     * UnearnedPremiumRefundApproved and reverses the commission and reinsurance on the unearned premium.
     */
    @Transactional
    public Map<String, Object> decideRefund(String ref, JsonNode body) {
        AppUser user = user("BILL_SCHEME");
        CreditRefund r = refund(ref);
        if (!"PENDING".equals(r.status)) {
            throw new ConflictException(ref + " is already " + r.status.toLowerCase(Locale.ROOT) + ".");
        }
        if (user.getUsername().equalsIgnoreCase(r.requestedBy)) {
            throw new NoAuthorizationException("You requested " + ref + ", so someone else must approve it.");
        }
        r.decidedBy = user.getUsername();
        r.decidedAt = LocalDateTime.now();
        r.decisionNote = cut(required(body, "note"), 300);
        CreditLoan l = loanById(r.loanId);
        if (!body.path("approve").asBoolean(false)) {
            r.status = "REJECTED";
            return refundView(r);
        }
        r.status = "APPROVED";
        Map<String, Object> approved = new LinkedHashMap<>();
        approved.put("schemeRef", r.schemeRef);
        approved.put("certificateNo", l.certificateNo);
        approved.put("refundRef", r.ref);
        approved.put("settlementDate", r.effectiveDate.toString());
        approved.put("amount", r.refundAmount.toPlainString());
        outbox.publish(Events.UNEARNED_PREMIUM_REFUND_APPROVED, l.certificateNo, approved, user.getUsername());
        if (r.commissionReversal.signum() > 0) {
            outbox.publish(Events.COMMISSION_REVERSED, r.ref, Map.of("schemeRef", r.schemeRef, "refundRef", r.ref,
                    "certificateNo", l.certificateNo, "amount", r.commissionReversal.toPlainString()), user.getUsername());
        }
        if (r.reinsuranceReversal.signum() > 0) {
            outbox.publish(Events.RISK_CEDED, r.ref, Map.of("schemeRef", r.schemeRef, "refundRef", r.ref,
                    "certificateNo", l.certificateNo, "cededPremium", r.reinsuranceReversal.negate().toPlainString(),
                    "amount", r.reinsuranceReversal.negate().toPlainString()), user.getUsername());
        }
        event(l, "REFUND_APPROVED", LocalDate.now(), r.ref + " " + Money.text(r.refundAmount), r.ref, user.getUsername());
        return refundView(r);
    }

    /** {paymentReference}: pays an approved refund (simulated payout) and posts GroupPremiumRefunded. */
    @Transactional
    public Map<String, Object> payRefund(String ref, JsonNode body) {
        AppUser user = user("BILL_SCHEME");
        CreditRefund r = refund(ref);
        if (!"APPROVED".equals(r.status)) {
            throw new ConflictException(ref + " is " + r.status.toLowerCase(Locale.ROOT) + "; only an approved refund is paid.");
        }
        CreditLoan l = loanById(r.loanId);
        r.status = "PAID";
        r.paymentReference = cut(required(body, "paymentReference"), 80);
        r.paidAt = LocalDateTime.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemeRef", r.schemeRef);
        data.put("certificateNo", l.certificateNo);
        data.put("refundRef", r.ref);
        data.put("customerName", l.borrowerName);
        data.put("paymentReference", r.paymentReference);
        data.put("amount", r.refundAmount.toPlainString());
        outbox.publish(Events.GROUP_PREMIUM_REFUNDED, l.certificateNo, data, user.getUsername());
        event(l, "REFUND_PAID", LocalDate.now(), r.ref + " paid (simulated payout " + r.paymentReference + ")", r.ref,
                user.getUsername());
        return refundView(r);
    }

    private record Calc(int monthsUnearned, BigDecimal gross, BigDecimal deduction, BigDecimal refund,
            BigDecimal commission, BigDecimal reinsurance, String formula) {
    }

    /** The agreed refund formula: pro rata or rule of 78 on the unearned months, less the deduction on settlement. */
    private Calc calc(CreditLoan l, LocalDate date, String reason, LenderAgreement terms) {
        long used = monthsUsed(l, date);
        int unearned = (int) (l.termMonths - used);
        BigDecimal gross;
        String method;
        List<CreditPremium> premiums = em.createQuery("select p from GroupCreditPremium p where p.loanId = :id"
                + " and p.status in ('DUE', 'BILLED')", CreditPremium.class).setParameter("id", l.id).getResultList();
        if (!"SINGLE".equals(l.premiumBasis) && l.premiumBasis != null && !premiums.isEmpty()) {
            gross = BigDecimal.ZERO;
            for (CreditPremium p : premiums) {
                if (p.periodFrom.isAfter(date)) {
                    gross = gross.add(p.amount);
                } else if (!p.periodTo.isBefore(date)) {
                    long months = Math.max(1, ChronoUnit.MONTHS.between(p.periodFrom, p.periodTo.plusDays(1)));
                    long left = Math.max(0, ChronoUnit.MONTHS.between(date, p.periodTo.plusDays(1)));
                    gross = gross.add(p.amount.multiply(BigDecimal.valueOf(left)).divide(BigDecimal.valueOf(months), 2,
                            RoundingMode.HALF_UP));
                }
            }
            method = "unearned part of the " + l.premiumBasis.toLowerCase(Locale.ROOT) + " instalments";
        } else if ("RULE_OF_78".equals(terms.refundMethod)) {
            long n = unearned;
            long t = l.termMonths;
            gross = l.premium.multiply(BigDecimal.valueOf(n * (n + 1))).divide(BigDecimal.valueOf(t * (t + 1)), 2,
                    RoundingMode.HALF_UP);
            method = "rule of 78: " + unearned + " of " + l.termMonths + " months";
        } else {
            gross = l.premium.multiply(BigDecimal.valueOf(unearned)).divide(BigDecimal.valueOf(l.termMonths), 2,
                    RoundingMode.HALF_UP);
            method = unearned + " of " + l.termMonths + " months unearned, pro rata";
        }
        gross = gross.setScale(2, RoundingMode.HALF_UP);
        BigDecimal pct = "CANCELLATION".equals(reason) ? BigDecimal.ZERO : terms.refundDeductionPercent;
        BigDecimal deduction = gross.multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal refund = gross.subtract(deduction);
        BigDecimal commission = gross.multiply(terms.commissionPercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal reinsurance = gross.multiply(terms.cededPercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        String formula = method + (pct.signum() > 0 ? ", less " + pct.stripTrailingZeros().toPlainString() + "%" : "")
                + " = " + Money.text(refund) + (stored(l.schemeRef) == null ? " (demo default formula)" : "");
        return new Calc(unearned, gross, deduction, refund, commission, reinsurance, formula);
    }

    private static long monthsUsed(CreditLoan l, LocalDate date) {
        return Math.min(l.termMonths, Math.max(0, ChronoUnit.MONTHS.between(l.startDate, date))
                + (date.isAfter(l.startDate.plusMonths(ChronoUnit.MONTHS.between(l.startDate, date))) ? 1 : 0));
    }

    private CreditRefund activeRefund(CreditLoan l) {
        return em.createQuery("select r from GroupCreditRefund r where r.loanId = :id and r.status <> 'REJECTED'",
                CreditRefund.class).setParameter("id", l.id).setMaxResults(1).getResultStream().findFirst().orElse(null);
    }

    // ── claims ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * {benefit DEATH|DISABILITY|CRITICAL_ILLNESS|TEMPORARY_DISABILITY, eventDate, balanceAtEvent?, monthlyInstalment?,
     * months?, note?}: a claim on the borrower's cover, calculated under the scheme's caps and payment basis.
     */
    @Transactional
    public Map<String, Object> notifyClaim(Long loanId, JsonNode body) {
        AppUser user = user("CREATE_CLAIM");
        CreditLoan l = loanById(loanId);
        String benefit = required(body, "benefit").toUpperCase(Locale.ROOT);
        if (!BENEFITS.contains(benefit)) {
            throw new IllegalArgumentException("Benefit must be one of " + BENEFITS);
        }
        List<String> elected = List.of((l.benefits == null ? "DEATH,DISABILITY" : l.benefits).split(","));
        if (!elected.contains(benefit)) {
            throw new IllegalArgumentException(l.certificateNo + " did not elect " + benefit + " (elected " + elected + ")");
        }
        LocalDate event = LocalDate.parse(required(body, "eventDate"));
        if (event.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The event date cannot be in the future.");
        }
        LocalDate end = l.settledOn != null ? l.settledOn : l.coverEndDate;
        if ("REFERRED".equals(l.status) || "DECLINED".equals(l.status) || event.isBefore(l.startDate)
                || end != null && event.isAfter(end)) {
            throw new IllegalArgumentException("No cover was in force on " + event + " for " + l.certificateNo + ".");
        }
        if (em.createQuery("select count(c) from GroupCreditClaim c where c.loanId = :id and c.benefit = :b"
                + " and c.status in ('NOTIFIED', 'APPROVED')", Long.class).setParameter("id", loanId)
                .setParameter("b", benefit).getSingleResult() > 0) {
            throw new ConflictException(l.certificateNo + " already has an open " + benefit.toLowerCase(Locale.ROOT)
                    + " claim.");
        }
        LenderAgreement terms = terms(l.schemeRef);
        BigDecimal balance = optional(body, "balanceAtEvent") != null ? Money.of(new BigDecimal(optional(body,
                "balanceAtEvent"))) : l.balance != null ? l.balance : l.loanAmount;
        CreditClaim c = new CreditClaim();
        c.ref = nextRef("GCC-", "select c.ref from GroupCreditClaim c");
        c.loanId = l.id;
        c.schemeRef = l.schemeRef;
        c.benefit = benefit;
        c.eventDate = event;
        c.status = "NOTIFIED";
        c.balanceAtEvent = balance;
        BigDecimal ciPaid = l.ciPaid == null ? BigDecimal.ZERO : l.ciPaid;
        String lender = terms.lenderRef == null ? "the lender" : "lender " + terms.lenderRef;
        switch (benefit) {
            case "TEMPORARY_DISABILITY" -> {
                BigDecimal instalment = optional(body, "monthlyInstalment") != null
                        ? Money.of(new BigDecimal(optional(body, "monthlyInstalment")))
                        : l.loanAmount.divide(BigDecimal.valueOf(l.termMonths), 2, RoundingMode.HALF_UP);
                int months = Math.min(Math.max(1, body.path("months").asInt(1)), terms.tdMaxMonths);
                c.monthlyAmount = instalment.min(terms.tdMonthlyCap);
                c.months = months;
                c.benefitAmount = c.monthlyAmount.multiply(BigDecimal.valueOf(months));
                c.lenderAmount = c.benefitAmount;
                c.borrowerAmount = BigDecimal.ZERO.setScale(2);
                c.calculation = "Instalment " + Money.text(instalment) + " capped at " + Money.text(terms.tdMonthlyCap)
                        + " a month x " + months + " month(s) (max " + terms.tdMaxMonths + "), paid to " + lender
                        + "; cover continues";
            }
            case "CRITICAL_ILLNESS" -> {
                BigDecimal room = terms.ciCap.subtract(ciPaid).max(BigDecimal.ZERO);
                c.benefitAmount = balance.min(room).min(l.coverAmount);
                c.lenderAmount = c.benefitAmount;
                c.borrowerAmount = BigDecimal.ZERO.setScale(2);
                c.calculation = "Outstanding balance " + Money.text(balance) + " up to the critical-illness cap of "
                        + Money.text(terms.ciCap) + (ciPaid.signum() > 0 ? " less " + Money.text(ciPaid) + " already paid" : "")
                        + ", paid to " + lender + "; it accelerates (reduces) the death cover";
            }
            default -> {
                BigDecimal insured = l.coverAmount.min(terms.deathCap);
                if ("SUM_INSURED".equals(terms.claimBasis)) {
                    c.benefitAmount = insured;
                    c.lenderAmount = balance.min(insured);
                    c.borrowerAmount = insured.subtract(c.lenderAmount);
                    c.calculation = "Sum insured " + Money.text(insured) + ": " + lender + " is paid the balance of "
                            + Money.text(c.lenderAmount) + ", the remainder goes to the borrower or estate";
                } else {
                    c.benefitAmount = balance.min(insured);
                    c.lenderAmount = c.benefitAmount;
                    c.borrowerAmount = BigDecimal.ZERO.setScale(2);
                    c.calculation = "Outstanding balance " + Money.text(balance) + " up to the cover of " + Money.text(insured)
                            + " (cap " + Money.text(terms.deathCap) + "), paid to " + lender
                            + (balance.compareTo(insured) > 0 ? "; the balance above cover stays with the borrower's estate"
                                    : "");
                }
            }
        }
        c.calculation = cut(c.calculation, 500);
        c.note = cut(optional(body, "note"), 500);
        c.notifiedBy = user.getUsername();
        c.notifiedAt = LocalDateTime.now();
        em.persist(c);
        event(l, "CLAIM_NOTIFIED", event, c.ref + " " + benefit.toLowerCase(Locale.ROOT) + " " + Money.text(c.benefitAmount),
                c.ref, user.getUsername());
        return claimView(c);
    }

    /**
     * {action APPROVE|DECLINE|PAY|RECOVER, note?, paymentReference?, confirmedBalance?, recoveredAmount?}. Approval is
     * by someone other than the notifier. Payment posts GroupClaimPaid; death, disability or a full critical-illness
     * payment ends the cover. RECOVER records an overpayment the lender must return (recovery rights).
     */
    @Transactional
    public Map<String, Object> decideClaim(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        CreditClaim c = em.createQuery("select c from GroupCreditClaim c where c.ref = :r", CreditClaim.class)
                .setParameter("r", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Claim " + ref + " not found"));
        CreditLoan l = loanById(c.loanId);
        String action = required(body, "action").toUpperCase(Locale.ROOT);
        switch (action) {
            case "APPROVE" -> {
                user.validateHasPermissionTo("APPROVE_CLAIM");
                expect(c, "NOTIFIED");
                if (user.getUsername().equalsIgnoreCase(c.notifiedBy)) {
                    throw new NoAuthorizationException("You notified " + ref + ", so someone else must decide it.");
                }
                c.status = "APPROVED";
                c.decidedBy = user.getUsername();
                c.decidedAt = LocalDateTime.now();
            }
            case "DECLINE" -> {
                user.validateHasPermissionTo("ASSESS_CLAIM");
                expect(c, "NOTIFIED");
                c.status = "DECLINED";
                c.note = cut(required(body, "note"), 500);
                c.decidedBy = user.getUsername();
                c.decidedAt = LocalDateTime.now();
            }
            case "PAY" -> {
                user.validateHasPermissionTo("SETTLE_CLAIM");
                expect(c, "APPROVED");
                c.status = "PAID";
                c.paymentReference = cut(required(body, "paymentReference"), 80);
                c.paidAt = LocalDateTime.now();
                if ("CRITICAL_ILLNESS".equals(c.benefit)) {
                    l.ciPaid = (l.ciPaid == null ? BigDecimal.ZERO : l.ciPaid).add(c.benefitAmount);
                    l.coverAmount = l.coverAmount.subtract(c.benefitAmount).max(BigDecimal.ZERO);
                }
                boolean ends = Set.of("DEATH", "DISABILITY").contains(c.benefit) || l.coverAmount.signum() == 0;
                if (ends) {
                    l.status = "CLAIMED";
                    l.coverEndDate = c.eventDate;
                    for (CreditPremium p : em.createQuery("select p from GroupCreditPremium p where p.loanId = :id"
                            + " and p.status = 'DUE' and p.periodFrom > :d", CreditPremium.class).setParameter("id", l.id)
                            .setParameter("d", c.eventDate).getResultList()) {
                        p.status = "CANCELLED";
                    }
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("schemeRef", c.schemeRef);
                data.put("claimRef", c.ref);
                data.put("certificateNo", l.certificateNo);
                data.put("benefit", c.benefit);
                data.put("lenderAmount", c.lenderAmount.toPlainString());
                data.put("borrowerAmount", c.borrowerAmount.toPlainString());
                data.put("amount", c.benefitAmount.toPlainString());
                outbox.publish(Events.GROUP_CLAIM_PAID, c.ref, data, user.getUsername());
                event(l, "CLAIM_PAID", c.eventDate, c.ref + " paid " + Money.text(c.benefitAmount)
                        + (ends ? "; cover ended" : ""), c.ref, user.getUsername());
            }
            case "RECOVER" -> {
                user.validateHasPermissionTo("SETTLE_CLAIM");
                expect(c, "PAID");
                String recovered = optional(body, "recoveredAmount");
                if (recovered != null) {
                    if (!"DUE".equals(c.recoveryStatus)) {
                        throw new ConflictException(ref + " has no recovery due.");
                    }
                    c.recoveryStatus = Money.of(new BigDecimal(recovered)).compareTo(c.recoveryAmount) >= 0
                            ? "RECOVERED" : "DUE";
                } else {
                    BigDecimal confirmed = Money.of(new BigDecimal(required(body, "confirmedBalance")));
                    BigDecimal over = c.lenderAmount.subtract(confirmed);
                    if (over.signum() <= 0) {
                        throw new IllegalArgumentException("The confirmed balance leaves nothing to recover.");
                    }
                    c.recoveryAmount = over;
                    c.recoveryStatus = "DUE";
                    c.note = cut("Lender confirmed a balance of " + Money.text(confirmed) + " at the event; "
                            + Money.text(over) + " paid above it is recoverable from the lender.", 500);
                }
                event(l, "RECOVERY", LocalDate.now(), c.ref + " recovery " + c.recoveryStatus.toLowerCase(Locale.ROOT)
                        + " " + Money.text(c.recoveryAmount), c.ref, user.getUsername());
            }
            default -> throw new IllegalArgumentException("Action must be APPROVE, DECLINE, PAY or RECOVER.");
        }
        return claimView(c);
    }

    private static void expect(CreditClaim c, String status) {
        if (!status.equals(c.status)) {
            throw new ConflictException(c.ref + " is " + c.status.toLowerCase(Locale.ROOT) + "; it must be "
                    + status.toLowerCase(Locale.ROOT) + ".");
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> claims(String schemeRef) {
        user("READ_SCHEME");
        return em.createQuery("select c from GroupCreditClaim c where c.schemeRef = :s order by c.id desc",
                CreditClaim.class).setParameter("s", schemeRef).getResultList().stream().map(this::claimView).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> refunds(String schemeRef) {
        user("READ_SCHEME");
        return em.createQuery("select r from GroupCreditRefund r where r.schemeRef = :s order by r.id desc",
                CreditRefund.class).setParameter("s", schemeRef).getResultList().stream().map(this::refundView).toList();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    /** The scheme's agreement, or the demo defaults when none is saved. */
    LenderAgreement terms(String schemeRef) {
        LenderAgreement a = stored(schemeRef);
        return a != null ? a : defaults(schemeRef);
    }

    private LenderAgreement stored(String schemeRef) {
        return em.createQuery("select a from GroupLenderAgreement a where a.schemeRef = :s", LenderAgreement.class)
                .setParameter("s", schemeRef).getResultStream().findFirst().orElse(null);
    }

    private LenderAgreement defaults(String schemeRef) {
        LenderAgreement a = new LenderAgreement();
        a.schemeRef = schemeRef;
        a.agreementNo = schemeRef + "-AGR";
        GroupScheme s = em.createQuery("select s from GroupScheme s where s.ref = :r", GroupScheme.class)
                .setParameter("r", schemeRef).getResultStream().findFirst().orElse(null);
        a.effectiveFrom = s == null ? LocalDate.of(2000, 1, 1) : s.getInceptionDate();
        a.premiumBasis = BASES.contains(defaults.get("basis")) ? defaults.get("basis") : "SINGLE";
        a.ratePerMille = new BigDecimal(defaults.get("rate"));
        a.balanceBasis = "ORIGINAL";
        a.premiumTiming = "IN_ADVANCE";
        a.remittanceDay = 7;
        a.deathCap = new BigDecimal(defaults.get("deathCap"));
        a.ciCap = new BigDecimal(defaults.get("ciCap"));
        a.tdMonthlyCap = new BigDecimal(defaults.get("tdCap"));
        a.tdMaxMonths = 12;
        a.overCapRule = "REFER";
        a.minAge = 18;
        a.maxAge = 65;
        a.maxAgeAtEnd = 70;
        a.maxTerm = 120;
        a.notificationDays = 90;
        a.claimBasis = "OUTSTANDING_BALANCE";
        a.refundMethod = "PRO_RATA";
        a.refundDeductionPercent = new BigDecimal(defaults.get("deduction"));
        a.commissionPercent = BigDecimal.ZERO;
        a.cededPercent = BigDecimal.ZERO;
        a.staleBalanceDays = Integer.parseInt(defaults.get("staleDays"));
        a.updatedBy = "system";
        a.updatedAt = LocalDateTime.now();
        return a;
    }

    private List<LoanClass> classes(String schemeRef) {
        return em.createQuery("select c from GroupLoanClass c where c.schemeRef = :s order by c.code", LoanClass.class)
                .setParameter("s", schemeRef).getResultList();
    }

    private Map<String, Object> agreementView(LenderAgreement a, List<LoanClass> classes) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("configured", a.id != null);
        v.put("schemeRef", a.schemeRef);
        v.put("lenderRef", a.lenderRef);
        v.put("lenderName", a.lenderRef == null ? null : em.createQuery("select l.name from Lender l where l.ref = :r",
                String.class).setParameter("r", a.lenderRef).getResultStream().findFirst().orElse(null));
        v.put("agreementNo", a.agreementNo);
        v.put("effectiveFrom", a.effectiveFrom);
        v.put("premiumBasis", a.premiumBasis);
        v.put("ratePerMille", a.ratePerMille);
        v.put("balanceBasis", a.balanceBasis);
        v.put("premiumTiming", a.premiumTiming);
        v.put("remittanceDay", a.remittanceDay);
        v.put("deathCap", a.deathCap);
        v.put("ciCap", a.ciCap);
        v.put("tdMonthlyCap", a.tdMonthlyCap);
        v.put("tdMaxMonths", a.tdMaxMonths);
        v.put("overCapRule", a.overCapRule);
        v.put("minAge", a.minAge);
        v.put("maxAge", a.maxAge);
        v.put("maxAgeAtEnd", a.maxAgeAtEnd);
        v.put("maxTerm", a.maxTerm);
        v.put("notificationDays", a.notificationDays);
        v.put("claimBasis", a.claimBasis);
        v.put("refundMethod", a.refundMethod);
        v.put("refundDeductionPercent", a.refundDeductionPercent);
        v.put("commissionPercent", a.commissionPercent);
        v.put("cededPercent", a.cededPercent);
        v.put("staleBalanceDays", a.staleBalanceDays);
        v.put("updatedBy", a.updatedBy);
        v.put("updatedAt", a.updatedAt);
        v.put("interfaceVersions", List.of("1", V2));
        v.put("classes", classes.stream().map(c -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", c.code);
            x.put("name", c.name);
            x.put("maxTerm", c.maxTerm);
            x.put("maxAmount", c.maxAmount);
            x.put("benefits", List.of(c.benefits.split(",")));
            x.put("ratePerMille", c.ratePerMille);
            return x;
        }).toList());
        return v;
    }

    Map<String, Object> loanView(CreditLoan l) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", l.id);
        v.put("certificateNo", l.certificateNo);
        v.put("lenderLoanId", l.lenderLoanId);
        v.put("borrowerName", l.borrowerName);
        v.put("borrowerIdNumber", l.borrowerIdNumber == null ? null : "*****" + l.borrowerIdNumber.substring(
                Math.max(0, l.borrowerIdNumber.length() - 4)));
        v.put("dateOfBirth", l.dateOfBirth);
        v.put("loanClass", l.loanClass);
        v.put("benefits", l.benefits == null ? List.of("DEATH", "DISABILITY") : List.of(l.benefits.split(",")));
        v.put("loanAmount", l.loanAmount);
        v.put("coverAmount", l.coverAmount);
        v.put("termMonths", l.termMonths);
        v.put("premiumBasis", l.premiumBasis == null ? "SINGLE" : l.premiumBasis);
        v.put("ratePerMille", l.ratePerMille);
        v.put("premium", l.premium);
        v.put("status", l.status);
        v.put("note", l.note);
        v.put("batchRef", l.batchRef);
        v.put("startDate", l.startDate);
        v.put("disbursementDate", l.disbursementDate);
        v.put("coverEndDate", l.coverEndDate);
        v.put("balance", l.balance);
        v.put("balanceDate", l.balanceDate);
        v.put("arrearsMonths", l.arrearsMonths);
        v.put("settledOn", l.settledOn);
        v.put("refund", l.refund);
        CreditRefund r = l.id == null ? null : activeRefund(l);
        v.put("refundRef", r == null ? null : r.ref);
        v.put("refundStatus", r == null ? null : r.status);
        v.put("interfaceVersion", l.interfaceVersion);
        return v;
    }

    private Map<String, Object> claimView(CreditClaim c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ref", c.ref);
        v.put("loanId", c.loanId);
        v.put("schemeRef", c.schemeRef);
        v.put("benefit", c.benefit);
        v.put("eventDate", c.eventDate);
        v.put("status", c.status);
        v.put("balanceAtEvent", c.balanceAtEvent);
        v.put("benefitAmount", c.benefitAmount);
        v.put("lenderAmount", c.lenderAmount);
        v.put("borrowerAmount", c.borrowerAmount);
        v.put("monthlyAmount", c.monthlyAmount);
        v.put("months", c.months);
        v.put("calculation", c.calculation);
        v.put("recoveryAmount", c.recoveryAmount);
        v.put("recoveryStatus", c.recoveryStatus);
        v.put("note", c.note);
        v.put("notifiedBy", c.notifiedBy);
        v.put("notifiedAt", c.notifiedAt);
        v.put("decidedBy", c.decidedBy);
        v.put("paymentReference", c.paymentReference);
        v.put("paidAt", c.paidAt);
        return v;
    }

    private Map<String, Object> refundView(CreditRefund r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ref", r.ref);
        v.put("loanId", r.loanId);
        v.put("reason", r.reason);
        v.put("effectiveDate", r.effectiveDate);
        v.put("monthsUnearned", r.monthsUnearned);
        v.put("grossUnearned", r.grossUnearned);
        v.put("deduction", r.deduction);
        v.put("refundAmount", r.refundAmount);
        v.put("commissionReversal", r.commissionReversal);
        v.put("reinsuranceReversal", r.reinsuranceReversal);
        v.put("formula", r.formula);
        v.put("status", r.status);
        v.put("sourceRef", r.sourceRef);
        v.put("requestedBy", r.requestedBy);
        v.put("requestedAt", r.requestedAt);
        v.put("decidedBy", r.decidedBy);
        v.put("decisionNote", r.decisionNote);
        v.put("paymentReference", r.paymentReference);
        v.put("paidAt", r.paidAt);
        return v;
    }

    private Map<String, Object> remittanceView(CreditRemittance r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ref", r.ref);
        v.put("schemeRef", r.schemeRef);
        v.put("period", r.period);
        v.put("amount", r.amount);
        v.put("paidAmount", r.paidAmount);
        v.put("outstanding", r.outstanding);
        v.put("installments", r.installments);
        v.put("commission", r.commission);
        v.put("ceded", r.ceded);
        v.put("status", r.status);
        v.put("reconciliation", r.reconciliation);
        v.put("issuedBy", r.issuedBy);
        v.put("issuedAt", r.issuedAt);
        return v;
    }

    private void event(CreditLoan l, String type, LocalDate date, String detail, String source, String actor) {
        LoanEvent e = new LoanEvent();
        e.loanId = l.id;
        e.type = type;
        e.effectiveDate = date == null ? LocalDate.now() : date;
        e.balance = l.balance;
        e.coverAmount = l.coverAmount;
        e.detail = cut(detail, 300);
        e.sourceRef = cut(source, 60);
        e.actor = actor;
        e.recordedAt = LocalDateTime.now();
        em.persist(e);
    }

    private CreditFile file(String schemeRef, String reference) {
        return em.createQuery("select f from GroupCreditFile f where f.schemeRef = :s and f.fileReference = :r",
                CreditFile.class).setParameter("s", schemeRef).setParameter("r", reference).getResultStream().findFirst()
                .orElse(null);
    }

    private void saveFile(String schemeRef, String reference, String kind, String version, String hash, int rows,
            BigDecimal control, String status, Map<String, Object> result, String actor) {
        CreditFile f = new CreditFile();
        f.schemeRef = schemeRef;
        f.fileReference = cut(reference, 60);
        f.kind = kind;
        f.schemaVersion = version;
        f.fileHash = hash;
        f.rowCount = rows;
        f.controlTotal = control;
        f.status = status;
        try {
            f.result = cut(mapper.writeValueAsString(result), 4000);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            f.result = null;
        }
        f.receivedBy = actor;
        f.receivedAt = LocalDateTime.now();
        em.persist(f);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResult(CreditFile f) {
        try {
            Map<String, Object> out = mapper.readValue(f.result, Map.class);
            out.put("repeated", true);
            return out;
        } catch (Exception ex) {
            return Map.of("fileReference", f.fileReference, "status", f.status, "repeated", true);
        }
    }

    private String hash(JsonNode body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String nextRef(String prefix, String jpql) {
        int max = 0;
        for (String ref : em.createQuery(jpql, String.class).getResultList()) {
            String digits = ref.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                max = Math.max(max, Integer.parseInt(digits));
            }
        }
        return prefix + String.format("%05d", max + 1);
    }

    private CreditLoan loanById(Long id) {
        CreditLoan l = em.find(CreditLoan.class, id);
        if (l == null) {
            throw new NotFoundException("Loan not found");
        }
        return l;
    }

    private CreditRefund refund(String ref) {
        return em.createQuery("select r from GroupCreditRefund r where r.ref = :r", CreditRefund.class)
                .setParameter("r", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Refund " + ref + " not found"));
    }

    private GroupScheme scheme(String ref) {
        return em.createQuery("select s from GroupScheme s where s.ref = :ref", GroupScheme.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Scheme " + ref + " not found"));
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private static String required(JsonNode body, String field) {
        String v = optional(body, field);
        if (v == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return v;
    }

    private static String optional(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        String v = body.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }

    private static String text(JsonNode body, String field, String fallback, int max) {
        String v = optional(body, field);
        v = v == null ? fallback : v;
        if (v == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return cut(v, max);
    }

    private static String choice(JsonNode body, String field, String fallback, Set<String> allowed) {
        String v = optional(body, field);
        v = v == null ? fallback : v.toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(field + " must be one of " + allowed.stream().sorted().toList());
        }
        return v;
    }

    private static BigDecimal decimal(JsonNode body, String field, BigDecimal fallback, String min, String max) {
        String v = optional(body, field);
        BigDecimal d = v == null ? fallback : new BigDecimal(v);
        if (d == null || d.compareTo(new BigDecimal(min)) < 0 || d.compareTo(new BigDecimal(max)) > 0) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max + ".");
        }
        return d;
    }

    private static int integer(JsonNode body, String field, int fallback, int min, int max) {
        String v = optional(body, field);
        int i = v == null ? fallback : Integer.parseInt(v);
        if (i < min || i > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max + ".");
        }
        return i;
    }

    private static LocalDate date(JsonNode row, String field) {
        String v = row.path(field).asText("").trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        try {
            return LocalDate.parse(v);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(field + " must be a date in the format yyyy-MM-dd.");
        }
    }

    private static BigDecimal money(JsonNode row, String field) {
        if (!row.hasNonNull(field)) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return Money.of(new BigDecimal(row.get(field).asText()));
    }
}

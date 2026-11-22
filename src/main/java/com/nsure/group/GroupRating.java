package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.group.RatingRecords.QuoteCensus;
import com.nsure.group.RatingRecords.QuoteCensusRow;
import com.nsure.group.RatingRecords.RateBand;
import com.nsure.group.RatingRecords.RateTable;
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
import java.util.ArrayList;
import java.util.HashMap;
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
 * Group rating (NS-GL01-002, section 08 pricing): the census a quotation is rated on is validated for completeness and
 * quality first, and the premium follows the stated rating basis with rates from an approved, versioned rate table
 * (age-banded per benefit) or a negotiated rate recorded as such. There is no single universal formula:
 *
 * <ul>
 *   <li>SUM_ASSURED: sum assured (salary × the category multiple, or its fixed cover) × rate / 1,000;</li>
 *   <li>SALARY: annual salary × rate / 1,000;</li>
 *   <li>PER_MEMBER: a rate per member a year;</li>
 *   <li>LOAN_BALANCE: loan balance × rate / 1,000.</li>
 * </ul>
 *
 * Table rates are risk rates; the quotation's expense, commission and profit loadings and any negotiated adjustment
 * are applied on top. The seeded tables are demo tables, labelled as such, until the insurer approves its own.
 */
@Service
public class GroupRating {

    public static final Set<String> BASES = Set.of("SALARY", "SUM_ASSURED", "PER_MEMBER", "LOAN_BALANCE");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final EvidenceChain audit;
    /** Demo defaults (D02/D03 open): the ages a quotation census may contain, and the age an indicative price assumes. */
    private final int minAge;
    private final int maxAge;
    private final int indicativeAge;

    public GroupRating(EntityManager em, PlatformSecurityContext security, EvidenceChain audit,
            @Value("${nsure.group.quote.census-min-age:16}") int minAge,
            @Value("${nsure.group.quote.census-max-age:70}") int maxAge,
            @Value("${nsure.group.quote.indicative-age:40}") int indicativeAge) {
        this.em = em;
        this.security = security;
        this.audit = audit;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.indicativeAge = indicativeAge;
    }

    // ── rate tables ────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rateTables() {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        return em.createQuery("select t from GroupRateTable t order by t.code, t.versionNo desc", RateTable.class)
                .getResultList().stream().map(this::tableView).toList();
    }

    /** {tableCode, label, benefitType, ratingBasis, bands [{ageFrom, ageTo, rate}], note?}: a new draft version. */
    @Transactional
    public Map<String, Object> draftRateTable(JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("CREATE_RATE_TABLE");
        String code = GroupRecords.requiredText(body, "tableCode", "Table code", 30).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_-]+")) {
            throw new IllegalArgumentException("Table code may use only letters, digits, - and _");
        }
        String basis = GroupRecords.requiredText(body, "ratingBasis", "Rating basis", 20).toUpperCase(Locale.ROOT);
        if (!BASES.contains(basis)) {
            throw new IllegalArgumentException("Rating basis must be one of " + BASES.stream().sorted().toList());
        }
        String benefit = GroupRecords.requiredText(body, "benefitType", "Benefit", 30).toUpperCase(Locale.ROOT);
        Integer latest = em.createQuery("select max(t.versionNo) from GroupRateTable t where t.code = :c", Integer.class)
                .setParameter("c", code).getSingleResult();
        if (latest != null) {
            RateTable previous = table(code, latest);
            if (!previous.benefitType.equals(benefit) || !previous.ratingBasis.equals(basis)) {
                throw new IllegalArgumentException(code + " rates " + previous.benefitType + " on " + previous.ratingBasis
                        + "; a new version cannot change that. Use a new table code.");
            }
            if ("DRAFT".equals(previous.status)) {
                throw new ConflictException(code + " v" + latest + " is still a draft; approve or replace it first.");
            }
        }
        JsonNode bands = body.get("bands");
        if (bands == null || !bands.isArray() || bands.isEmpty()) {
            throw new IllegalArgumentException("Give the table's age bands with their rates.");
        }
        RateTable t = new RateTable();
        t.code = code;
        t.versionNo = latest == null ? 1 : latest + 1;
        t.label = GroupRecords.requiredText(body, "label", "Label", 150);
        t.benefitType = benefit;
        t.ratingBasis = basis;
        t.status = "DRAFT";
        t.note = GroupRecords.text(body, "note");
        t.createdBy = user.getUsername();
        t.createdAt = LocalDateTime.now();
        em.persist(t);
        int previousTo = -1;
        List<int[]> ranges = new ArrayList<>();
        for (JsonNode b : bands) {
            Integer from = GroupRecords.integer(b, "ageFrom", "Band start age");
            Integer to = GroupRecords.integer(b, "ageTo", "Band end age");
            BigDecimal rate = GroupRecords.decimal(b, "rate", "Rate");
            if (from == null || to == null || rate == null || from > to || from < 0 || to > 120 || rate.signum() <= 0) {
                throw new IllegalArgumentException("Each band needs ages from ≤ to (0-120) and a rate above zero.");
            }
            if (from <= previousTo) {
                throw new IllegalArgumentException("Bands must be in age order without overlaps (" + from + " overlaps).");
            }
            previousTo = to;
            ranges.add(new int[] {from, to});
            RateBand band = new RateBand();
            band.table = t;
            band.ageFrom = from;
            band.ageTo = to;
            band.rate = rate.setScale(4, RoundingMode.HALF_UP);
            em.persist(band);
        }
        audit.record(user.getUsername(), "Group rate table drafted", "GroupRateTable", code, "v" + t.versionNo);
        return tableView(t);
    }

    /** Approves a draft version (not by its author); the earlier approved version is superseded for new quotations. */
    @Transactional
    public Map<String, Object> approveRateTable(String code, int version) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("PUBLISH_RATE_TABLE");
        RateTable t = table(code, version);
        if (!"DRAFT".equals(t.status)) {
            throw new ConflictException(code + " v" + version + " is " + t.status.toLowerCase(Locale.ROOT) + ".");
        }
        if (user.getUsername().equalsIgnoreCase(t.createdBy)) {
            throw new NoAuthorizationException("You drafted " + code + " v" + version + ", so someone else approves it.");
        }
        em.createQuery("update GroupRateTable t set t.status = 'SUPERSEDED' where t.code = :c and t.status = 'APPROVED'")
                .setParameter("c", code).executeUpdate();
        t.status = "APPROVED";
        t.approvedBy = user.getUsername();
        t.approvedAt = LocalDateTime.now();
        audit.record(user.getUsername(), "Group rate table approved", "GroupRateTable", code, "v" + version);
        return tableView(t);
    }

    private RateTable table(String code, int version) {
        return em.createQuery("select t from GroupRateTable t where t.code = :c and t.versionNo = :v", RateTable.class)
                .setParameter("c", code).setParameter("v", version).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Rate table " + code + " v" + version + " not found"));
    }

    /** The approved table for the benefit on the basis, else the benefit's approved table on any basis. */
    private RateTable approvedTable(String benefit, String basis) {
        List<RateTable> tables = em.createQuery("select t from GroupRateTable t where t.benefitType = :b"
                + " and t.status = 'APPROVED' order by t.code, t.versionNo desc", RateTable.class)
                .setParameter("b", benefit).getResultList();
        return tables.stream().filter(t -> t.ratingBasis.equals(basis)).findFirst()
                .orElse(tables.isEmpty() ? null : tables.get(0));
    }

    private List<RateBand> bands(RateTable t) {
        return em.createQuery("select b from GroupRateBand b where b.table = :t order by b.ageFrom", RateBand.class)
                .setParameter("t", t).getResultList();
    }

    private Map<String, Object> tableView(RateTable t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("tableCode", t.code);
        v.put("version", t.versionNo);
        v.put("label", t.label);
        v.put("benefitType", t.benefitType);
        v.put("ratingBasis", t.ratingBasis);
        v.put("unit", unit(t.ratingBasis));
        v.put("status", t.status);
        v.put("note", t.note);
        v.put("createdBy", t.createdBy);
        v.put("approvedBy", t.approvedBy);
        v.put("approvedAt", t.approvedAt);
        v.put("bands", bands(t).stream().map(b -> Map.of("ageFrom", b.ageFrom, "ageTo", b.ageTo, "rate", b.rate))
                .toList());
        return v;
    }

    public static String unit(String basis) {
        return switch (basis) {
            case "SALARY" -> "per 1,000 of annual salary a year";
            case "PER_MEMBER" -> "per member a year";
            case "LOAN_BALANCE" -> "per 1,000 of loan balance a year";
            default -> "per 1,000 of sum assured a year";
        };
    }

    // ── quotation census ───────────────────────────────────────────────────────────────────────────

    /** A category of the quoted benefit design: how its members' sum assured is set. */
    public record DesignCategory(String code, String name, String basis, BigDecimal multiple, BigDecimal fixedCover) {

        BigDecimal sumAssured(BigDecimal salary) {
            if (BenefitCategory.FIXED.equals(basis)) {
                return fixedCover == null ? BigDecimal.ZERO : fixedCover;
            }
            return salary == null ? BigDecimal.ZERO : salary.multiply(multiple);
        }
    }

    /**
     * Validates and keeps the next numbered census for the quotation (NS-GL01-002): every row's outcome, the totals of
     * the valid rows, and warnings about data quality. Columns are named in the header row: memberKey, dateOfBirth,
     * salary, and category and loanBalance where the design or basis needs them.
     */
    @Transactional
    public QuoteCensus recordCensus(String quoteRef, String csv, String basis, Map<String, DesignCategory> design,
            LocalDate asAt, String actor) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("The census is empty.");
        }
        String[] lines = csv.replace("\r", "").split("\n");
        String[] header = lines[0].split(",", -1);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            col.put(header[i].trim().toLowerCase(Locale.ROOT), i);
        }
        for (String needed : new String[] {"memberkey", "dateofbirth"}) {
            if (!col.containsKey(needed)) {
                throw new IllegalArgumentException("The census header must name memberKey, dateOfBirth and salary"
                        + " (and category, loanBalance where used); " + needed + " is missing.");
            }
        }
        boolean salaryNeeded = !Set.of("PER_MEMBER", "LOAN_BALANCE").contains(basis);
        if (salaryNeeded && !col.containsKey("salary")) {
            throw new IllegalArgumentException("The census needs a salary column for the " + basis + " basis.");
        }
        if ("LOAN_BALANCE".equals(basis) && !col.containsKey("loanbalance")) {
            throw new IllegalArgumentException("The census needs a loanBalance column for the LOAN_BALANCE basis.");
        }
        Integer latest = em.createQuery("select max(c.version) from QuoteCensus c where c.quoteRef = :r", Integer.class)
                .setParameter("r", quoteRef).getSingleResult();
        QuoteCensus census = new QuoteCensus();
        census.quoteRef = quoteRef;
        census.version = latest == null ? 1 : latest + 1;
        census.fileHash = sha256(csv);
        census.submittedBy = actor;
        census.submittedAt = LocalDateTime.now();
        census.salaryRoll = BigDecimal.ZERO;
        census.loanBalance = BigDecimal.ZERO;
        census.status = QuoteCensus.HAS_ERRORS;
        em.persist(census);
        Map<String, Integer> seen = new HashMap<>();
        List<BigDecimal> salaries = new ArrayList<>();
        int ageTotal = 0;
        int defaulted = 0;
        for (int n = 1; n < lines.length; n++) {
            if (lines[n].isBlank()) {
                continue;
            }
            String[] cells = lines[n].split(",", -1);
            QuoteCensusRow row = new QuoteCensusRow();
            row.census = census;
            row.lineNo = n + 1;
            row.memberKey = trim(cell(cells, col, "memberkey"), 40);
            row.dateOfBirth = trim(cell(cells, col, "dateofbirth"), 20);
            row.categoryCode = trim(cell(cells, col, "category"), 20);
            row.salary = trim(cell(cells, col, "salary"), 30);
            row.loanBalance = trim(cell(cells, col, "loanbalance"), 30);
            List<String> errors = new ArrayList<>();
            if (row.memberKey == null) {
                errors.add("memberKey is required");
            } else if (seen.containsKey(row.memberKey)) {
                errors.add("duplicate memberKey " + row.memberKey + " (first on line " + seen.get(row.memberKey) + ")");
            } else {
                seen.put(row.memberKey, row.lineNo);
            }
            LocalDate dob = GroupRecords.parseDate(row.dateOfBirth);
            Integer age = null;
            if (row.dateOfBirth == null) {
                errors.add("dateOfBirth is required");
            } else if (dob == null) {
                errors.add("dateOfBirth must be a date in the format yyyy-MM-dd");
            } else {
                age = Period.between(dob, asAt).getYears();
                if (age < minAge || age > maxAge) {
                    errors.add("age " + age + " is outside the " + minAge + "-" + maxAge + " ages that can be rated");
                }
            }
            BigDecimal salary = amount(row.salary);
            if (row.salary != null && (salary == null || salary.signum() <= 0)) {
                errors.add("salary must be a number above 0");
            } else if (row.salary == null && salaryNeeded) {
                errors.add("salary is required for the " + basis + " basis");
            }
            BigDecimal loan = amount(row.loanBalance);
            if ("LOAN_BALANCE".equals(basis) && (loan == null || loan.signum() <= 0)) {
                errors.add("loanBalance must be a number above 0");
            }
            if (!design.isEmpty()) {
                if (row.categoryCode == null && design.size() == 1) {
                    row.categoryCode = design.keySet().iterator().next();
                    defaulted++;
                } else if (row.categoryCode == null) {
                    errors.add("category is required (one of " + String.join(", ", design.keySet()) + ")");
                } else if (!design.containsKey(row.categoryCode.toUpperCase(Locale.ROOT))) {
                    errors.add("unknown category " + row.categoryCode);
                } else {
                    row.categoryCode = row.categoryCode.toUpperCase(Locale.ROOT);
                }
            }
            census.totalRows++;
            if (errors.isEmpty()) {
                row.status = "VALID";
                census.validRows++;
                census.lives++;
                if (salary != null) {
                    census.salaryRoll = census.salaryRoll.add(salary);
                    salaries.add(salary);
                }
                if (loan != null) {
                    census.loanBalance = census.loanBalance.add(loan);
                }
                ageTotal += age;
            } else {
                row.status = "INVALID";
                row.error = String.join("; ", errors);
                if (row.error.length() > 500) {
                    row.error = row.error.substring(0, 497) + "...";
                }
                census.invalidRows++;
            }
            em.persist(row);
        }
        if (census.totalRows == 0) {
            throw new IllegalArgumentException("The census has a header but no rows.");
        }
        census.salaryRoll = Money.of(census.salaryRoll);
        census.loanBalance = Money.of(census.loanBalance);
        census.averageAge = census.lives == 0 ? null
                : BigDecimal.valueOf(ageTotal).divide(BigDecimal.valueOf(census.lives), 1, RoundingMode.HALF_UP);
        census.status = census.invalidRows == 0 && census.validRows > 0 ? QuoteCensus.VALID : QuoteCensus.HAS_ERRORS;
        List<String> warnings = new ArrayList<>();
        if (!salaries.isEmpty()) {
            List<BigDecimal> sorted = salaries.stream().sorted().toList();
            BigDecimal median = sorted.get(sorted.size() / 2);
            long outliers = salaries.stream().filter(s -> s.compareTo(median.multiply(BigDecimal.TEN)) > 0).count();
            if (outliers > 0) {
                warnings.add(outliers + " salar" + (outliers == 1 ? "y is" : "ies are") + " more than ten times the median"
                        + " of " + Money.display(median) + "; check them before rating.");
            }
        }
        if (census.averageAge != null && census.averageAge.compareTo(BigDecimal.valueOf(50)) > 0) {
            warnings.add("The average age is " + census.averageAge + ", above 50: an older group than usual.");
        }
        if (defaulted > 0) {
            warnings.add(defaulted + " row(s) had no category and were placed in the only category of the design.");
        }
        census.warnings = warnings.isEmpty() ? null : String.join("\n", warnings);
        return census;
    }

    public QuoteCensus census(Long id) {
        return id == null ? null : em.find(QuoteCensus.class, id);
    }

    /** The census summary (and, when asked, every row with its outcome) for the quote workspace. */
    @Transactional(readOnly = true)
    public Map<String, Object> censusView(Long id, boolean withRows) {
        QuoteCensus c = census(id);
        if (c == null) {
            return null;
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("version", c.version);
        v.put("status", c.status);
        v.put("fileHash", c.fileHash);
        v.put("totalRows", c.totalRows);
        v.put("validRows", c.validRows);
        v.put("invalidRows", c.invalidRows);
        v.put("lives", c.lives);
        v.put("salaryRoll", c.salaryRoll);
        v.put("loanBalance", c.loanBalance);
        v.put("averageAge", c.averageAge);
        v.put("warnings", c.warnings == null ? List.of() : List.of(c.warnings.split("\n")));
        v.put("submittedBy", c.submittedBy);
        v.put("submittedAt", c.submittedAt);
        if (withRows) {
            v.put("rows", rows(c).stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("lineNo", r.lineNo);
                m.put("memberKey", r.memberKey);
                m.put("dateOfBirth", r.dateOfBirth);
                m.put("category", r.categoryCode);
                m.put("salary", r.salary);
                m.put("loanBalance", r.loanBalance);
                m.put("status", r.status);
                m.put("error", r.error);
                return m;
            }).toList());
        }
        return v;
    }

    private List<QuoteCensusRow> rows(QuoteCensus c) {
        return em.createQuery("select r from QuoteCensusRow r where r.census = :c order by r.lineNo", QuoteCensusRow.class)
                .setParameter("c", c).getResultList();
    }

    // ── pricing ────────────────────────────────────────────────────────────────────────────────────

    /** What a quotation is priced on. {@code census} is used only when every row passed validation. */
    public record PricingRequest(List<String> benefits, String basis, boolean negotiated, BigDecimal negotiatedRate,
            BigDecimal salaryMultiple, Map<String, DesignCategory> design, QuoteCensus census, int lives,
            BigDecimal salaryRoll, BigDecimal loanBalance, LocalDate asAt, BigDecimal expensePercent,
            BigDecimal commissionPercent, BigDecimal profitPercent, BigDecimal adjustmentPercent) {
    }

    /** The priced quotation: office premium, its components and where every rate came from. */
    public record Pricing(String status, String indicativeReason, BigDecimal technicalPremium, BigDecimal annualPremium,
            BigDecimal officeRate, BigDecimal sumAssured, int lives, BigDecimal salaryRoll, List<String> tables,
            List<Map<String, Object>> benefits, List<Map<String, Object>> categories) {
    }

    /** One rated life: its age, salary, sum assured, loan balance and category. */
    private record Life(int age, BigDecimal salary, BigDecimal sumAssured, BigDecimal loan, String category) {
    }

    public Pricing price(PricingRequest r) {
        boolean rated = r.census() != null && QuoteCensus.VALID.equals(r.census().status);
        String reason = r.census() == null ? "No census: priced on the lives and salary roll entered."
                : rated ? null : "Census v" + r.census().version + " has " + r.census().invalidRows
                        + " invalid row(s): priced on the lives and salary roll entered until it is corrected.";
        DesignCategory single = new DesignCategory("ALL", "All members", BenefitCategory.SALARY_MULTIPLE,
                r.salaryMultiple(), null);
        List<Life> lives = new ArrayList<>();
        if (rated) {
            for (QuoteCensusRow row : rows(r.census())) {
                if (!"VALID".equals(row.status)) {
                    continue;
                }
                BigDecimal salary = amount(row.salary);
                DesignCategory c = row.categoryCode != null && r.design().containsKey(row.categoryCode)
                        ? r.design().get(row.categoryCode) : single;
                lives.add(new Life(Period.between(GroupRecords.parseDate(row.dateOfBirth), r.asAt()).getYears(), salary,
                        c.sumAssured(salary), amount(row.loanBalance), c.code()));
            }
        } else {
            if (r.lives() < 1 || ((r.salaryRoll() == null || r.salaryRoll().signum() <= 0)
                    && !Set.of("PER_MEMBER", "LOAN_BALANCE").contains(r.basis()))) {
                throw new IllegalArgumentException("Give a census, or the number of lives and the salary roll for an"
                        + " indicative price.");
            }
            BigDecimal salary = r.salaryRoll() == null ? BigDecimal.ZERO : r.salaryRoll();
            BigDecimal loan = r.loanBalance() == null ? BigDecimal.ZERO : r.loanBalance();
            // The group as one block of lives at the indicative age.
            lives.add(new Life(indicativeAge, salary, single.sumAssured(salary), loan, single.code()));
        }
        int count = rated ? lives.size() : r.lives();
        BigDecimal totalSalary = lives.stream().map(l -> l.salary() == null ? BigDecimal.ZERO : l.salary())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSa = lives.stream().map(Life::sumAssured).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> tables = new ArrayList<>();
        List<Map<String, Object>> benefitLines = new ArrayList<>();
        Map<String, BigDecimal> categoryPremium = new LinkedHashMap<>();
        BigDecimal technical = BigDecimal.ZERO;
        if (r.negotiated()) {
            if (r.negotiatedRate() == null || r.negotiatedRate().signum() <= 0) {
                throw new IllegalArgumentException("A negotiated rate above zero is required.");
            }
            BigDecimal premium = BigDecimal.ZERO;
            for (Life l : lives) {
                BigDecimal p = premiumFor(r.basis(), l, r.negotiatedRate(), rated ? 1 : r.lives());
                premium = premium.add(p);
                categoryPremium.merge(l.category(), p, BigDecimal::add);
            }
            technical = premium;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("benefits", r.benefits());
            line.put("basis", r.basis());
            line.put("source", "Negotiated rate " + r.negotiatedRate().stripTrailingZeros().toPlainString() + " "
                    + unit(r.basis()));
            line.put("premium", Money.of(premium));
            benefitLines.add(line);
        } else {
            for (String benefit : r.benefits()) {
                RateTable t = approvedTable(benefit, r.basis());
                if (t == null) {
                    throw new IllegalArgumentException("There is no approved group rate table for " + benefit
                            + "; enter a negotiated rate or approve a rate table first.");
                }
                List<RateBand> bands = bands(t);
                BigDecimal premium = BigDecimal.ZERO;
                for (Life l : lives) {
                    RateBand band = bands.stream().filter(b -> l.age() >= b.ageFrom && l.age() <= b.ageTo).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(t.code + " v" + t.versionNo + " has no rate"
                                    + " for age " + l.age() + "."));
                    BigDecimal p = premiumFor(t.ratingBasis, l, band.rate, rated ? 1 : r.lives());
                    premium = premium.add(p);
                    categoryPremium.merge(l.category(), p, BigDecimal::add);
                }
                technical = technical.add(premium);
                tables.add(t.code + " v" + t.versionNo);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("benefit", benefit);
                line.put("basis", t.ratingBasis);
                line.put("source", t.label + " (" + t.code + " v" + t.versionNo + ")");
                line.put("premium", Money.of(premium));
                benefitLines.add(line);
            }
        }
        technical = Money.of(technical);
        BigDecimal loading = r.negotiated() ? BigDecimal.ZERO : nz(r.expensePercent()).add(nz(r.commissionPercent()))
                .add(nz(r.profitPercent()));
        BigDecimal factor = BigDecimal.ONE.add(loading.divide(HUNDRED, 8, RoundingMode.HALF_UP))
                .multiply(BigDecimal.ONE.add(nz(r.adjustmentPercent()).divide(HUNDRED, 8, RoundingMode.HALF_UP)));
        BigDecimal office = technical.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        BigDecimal exposure = switch (r.basis()) {
            case "SALARY" -> totalSalary;
            case "PER_MEMBER" -> BigDecimal.valueOf(count);
            case "LOAN_BALANCE" -> lives.stream().map(l -> l.loan() == null ? BigDecimal.ZERO : l.loan())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            default -> totalSa;
        };
        BigDecimal officeRate = r.negotiated() ? r.negotiatedRate() : exposure.signum() <= 0 ? BigDecimal.ZERO
                : "PER_MEMBER".equals(r.basis()) ? office.divide(exposure, 4, RoundingMode.HALF_UP)
                : office.multiply(THOUSAND).divide(exposure, 4, RoundingMode.HALF_UP);
        List<Map<String, Object>> categories = new ArrayList<>();
        Map<String, BigDecimal> categorySa = new HashMap<>();
        Map<String, Integer> categoryLives = new HashMap<>();
        for (Life l : lives) {
            categorySa.merge(l.category(), l.sumAssured(), BigDecimal::add);
            categoryLives.merge(l.category(), 1, Integer::sum);
        }
        BigDecimal techTotal = technical.signum() == 0 ? BigDecimal.ONE : technical;
        categoryPremium.forEach((code, p) -> {
            BigDecimal share = office.multiply(p).divide(techTotal, 2, RoundingMode.HALF_UP);
            BigDecimal sa = categorySa.getOrDefault(code, BigDecimal.ZERO);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("category", code);
            c.put("lives", rated ? categoryLives.getOrDefault(code, 0) : count);
            c.put("sumAssured", Money.of(sa));
            c.put("annualPremium", share);
            // The monthly rate per 1,000 of life cover a category bills on once the scheme is in force.
            c.put("monthlyRatePerMille", sa.signum() <= 0 ? null : share.multiply(THOUSAND)
                    .divide(sa, 8, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP));
            categories.add(c);
        });
        return new Pricing(rated ? "RATED" : "INDICATIVE", reason, technical, office,
                officeRate.setScale(4, RoundingMode.HALF_UP), Money.of(totalSa), count, Money.of(totalSalary), tables,
                benefitLines, categories);
    }

    /** One life's (or one block's) annual premium on the basis at the rate. */
    private static BigDecimal premiumFor(String basis, Life l, BigDecimal rate, int members) {
        return switch (basis) {
            case "SALARY" -> nz(l.salary()).multiply(rate).divide(THOUSAND, 6, RoundingMode.HALF_UP);
            case "PER_MEMBER" -> rate.multiply(BigDecimal.valueOf(members));
            case "LOAN_BALANCE" -> nz(l.loan()).multiply(rate).divide(THOUSAND, 6, RoundingMode.HALF_UP);
            default -> l.sumAssured().multiply(rate).divide(THOUSAND, 6, RoundingMode.HALF_UP);
        };
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String cell(String[] cells, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= cells.length) {
            return null;
        }
        String v = cells[i].trim();
        return v.isEmpty() ? null : v;
    }

    private static String trim(String v, int max) {
        return v == null ? null : v.length() <= max ? v : v.substring(0, max);
    }

    private static BigDecimal amount(String text) {
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

package com.nsure.grouplife;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.group.BenefitCategory;
import com.nsure.group.GroupRating;
import com.nsure.group.RatingRecords.QuoteCensus;
import com.nsure.group.GroupSchemeService;
import com.nsure.group.GroupSponsor;
import com.nsure.group.GroupViews;
import com.nsure.grouplife.GroupLifeRecords.Benefit;
import com.nsure.grouplife.GroupLifeRecords.Quote;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.GroupScheme;
import com.nsure.portfolio.ReferenceNumbers;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract set-up and activation of a master policy (NS-GL01-003/004, AT-GL01-01, GL01 contract set-up wizard).
 *
 * <p>A scheme is put in force only from an accepted quotation whose terms an underwriter approved, on an approved
 * group wording version, with the inception conditions met and every category carrying a free-cover limit within the
 * accepted terms. The set-up terms (legal sponsor, payer, broker, billing arrangement, the actively-at-work path and
 * whether partial cover is allowed) are recorded on the scheme. A scheme created directly waits in SETUP for this.
 */
@Service
public class SchemeSetup {

    static final Set<String> BILLING_METHODS = Set.of("EFT", "DEBIT_ORDER", "STOP_ORDER");
    static final Set<String> AAW_RULES = Set.of("REFER", "DEFER");

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;
    private final ReferenceNumbers numbers;
    private final EvidenceChain audit;
    private final GroupSchemeService schemes;
    private final ObjectMapper mapper;
    private final GroupRating rating;
    /** Demo default (D06/D11 open): the inception conditions every activation must confirm. */
    private final List<String> requiredConditions;
    /** Demo defaults (D01/D02 open): days a quotation stays open for acceptance, and its default loadings. */
    @Value("${nsure.group.quote.validity-days:30}")
    private int defaultValidityDays;
    @Value("${nsure.group.quote.expense-loading-percent:10}")
    private BigDecimal defaultExpensePercent;
    @Value("${nsure.group.quote.commission-percent:5}")
    private BigDecimal defaultCommissionPercent;
    @Value("${nsure.group.quote.profit-margin-percent:5}")
    private BigDecimal defaultProfitPercent;

    public SchemeSetup(EntityManager em, PlatformSecurityContext security, Outbox outbox, ReferenceNumbers numbers,
            EvidenceChain audit, GroupSchemeService schemes, ObjectMapper mapper, GroupRating rating,
            @Value("${nsure.group.activation.inception-conditions:SIGNED_PROPOSAL,SIGNED_CENSUS,FIRST_PREMIUM_OR_DEFERRAL}")
            List<String> requiredConditions) {
        this.rating = rating;
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.numbers = numbers;
        this.audit = audit;
        this.schemes = schemes;
        this.mapper = mapper;
        this.requiredConditions = requiredConditions.stream().map(c -> c.trim().toUpperCase(Locale.ROOT))
                .filter(c -> !c.isEmpty()).toList();
    }

    // ── activation ─────────────────────────────────────────────────────────────────────────────────

    /** The accepted quotation becomes a new master policy (the quote's own activation step). */
    @Transactional
    public void activateQuote(Quote q, JsonNode body, AppUser user) {
        expectAccepted(q);
        if (q.schemeRef != null) {
            throw new ConflictException(q.ref + " has already been activated as " + q.schemeRef + ".");
        }
        String inceptionText = text(body, "inceptionDate");
        if (inceptionText == null) {
            throw new IllegalArgumentException("inceptionDate is required.");
        }
        LocalDate inception = LocalDate.parse(inceptionText);
        // Problems with the terms are reported before anything is created.
        List<String> problems = problems(q, null, body);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        ObjectNode scheme = mapper.createObjectNode();
        scheme.put("name", q.sponsorName).put("sector", q.sector).put("inceptionDate", inception.toString())
                .put("renewalDate", inception.plusYears(1).toString());
        String schemeRef = schemes.createScheme(scheme).scheme().ref();
        activate(q, schemes.lockScheme(schemeRef), body, user);
    }

    /** {quoteRef, wordingRef, conditions, ...}: a scheme prepared in SETUP is activated on an accepted quotation. */
    @Transactional
    public GroupViews.SchemeDetailView activateScheme(String schemeRef, JsonNode body) {
        AppUser user = user("MANAGE_SCHEME");
        GroupScheme s = schemes.lockScheme(schemeRef);
        if (!"SETUP".equals(s.getStatus())) {
            throw new ConflictException(schemeRef + " is " + s.getStatus().toLowerCase(Locale.ROOT)
                    + "; only a scheme in set-up can be activated.");
        }
        String quoteRef = text(body, "quoteRef");
        if (quoteRef == null) {
            throw new IllegalArgumentException("Choose the accepted quotation (quoteRef) the scheme is activated on.");
        }
        Quote q = latest(quoteRef);
        expectAccepted(q);
        if (q.schemeRef != null) {
            throw new ConflictException(q.ref + " has already been activated as " + q.schemeRef + ".");
        }
        List<String> problems = problems(q, s, body);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        activate(q, s, body, user);
        return schemes.scheme(schemeRef);
    }

    /**
     * What activation would check, without changing anything (the wizard's last step): each condition with whether it
     * is met, the categories that would be created, the approved wordings to choose from and the conditions required.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> readiness(String quoteRef, String schemeRef, JsonNode body) {
        user("READ_SCHEME");
        Quote q = latest(quoteRef);
        GroupScheme s = schemeRef == null ? null : scheme(schemeRef);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quoteRef", q.ref);
        out.put("version", q.versionNo);
        out.put("quoteStatus", q.status);
        out.put("freeCoverLimit", q.freeCoverLimit);
        out.put("requiredConditions", requiredConditions);
        out.put("wordings", wordings());
        out.put("categories", s != null && !schemes.categoriesOf(s).isEmpty()
                ? schemes.categoriesOf(s).stream().map(c -> c.getCode() + " · " + c.getName()).toList()
                : categoryDefinitions(q, body).stream().map(c -> c.path("code").asText() + " · " + c.path("name").asText())
                        .toList());
        List<String> problems = problems(q, s, body == null ? mapper.createObjectNode() : body);
        if (!"ACCEPTED".equals(q.status)) {
            problems.add(0, q.ref + " v" + q.versionNo + " is " + q.status.toLowerCase(Locale.ROOT)
                    + "; the sponsor must accept the approved terms first.");
        }
        out.put("problems", problems);
        out.put("ready", problems.isEmpty());
        return out;
    }

    private void activate(Quote q, GroupScheme s, JsonNode body, AppUser user) {
        String actor = user.getUsername();
        // Legal sponsor: the one named on the request, else a sponsor record for the quotation's sponsor.
        GroupSponsor sponsor = sponsorFor(q, body, actor);
        sponsor.becameClient();
        String payer = text(body, "payerName");
        s.setUpTerms(sponsor.getRef(), payer == null ? sponsor.getLegalName() : payer, text(body, "brokerRef"),
                billingMethod(body), dueDay(body), aawRule(body), partialCover(body));
        if (schemes.categoriesOf(s).isEmpty()) {
            for (JsonNode c : categoryDefinitions(q, body)) {
                ObjectNode def = c.deepCopy();
                // A category without its own rate bills on the quotation's priced rate; entry ages default to 18-64.
                if (!def.hasNonNull("ratePerMille") || def.get("ratePerMille").asText().isBlank()) {
                    def.put("ratePerMille", monthlyRate(q).toPlainString());
                }
                if (!def.hasNonNull("minEntryAge")) {
                    def.put("minEntryAge", 18);
                }
                if (!def.hasNonNull("maxEntryAge")) {
                    def.put("maxEntryAge", 64);
                }
                schemes.addCategory(s.getRef(), def);
            }
        }
        Object[] wording = wording(text(body, "wordingRef"), integer(body, "wordingVersion"));
        s.activate(q.ref, (String) wording[0], (Integer) wording[1], String.join(", ", conditions(body)), actor);
        q.status = "ACTIVATED";
        q.schemeRef = s.getRef();
        List<String> seeded = seedBenefits(q, s.getRef(), actor);
        if (!seeded.isEmpty()) {
            outbox.publish(Events.SCHEME_PLAN_APPROVED, s.getRef(), Map.of("schemeRef", s.getRef(), "benefits", seeded,
                    "approvedBy", q.decidedBy == null ? "" : q.decidedBy, "source", q.ref + " v" + q.versionNo), actor);
        }
        schemes.activated(s, actor);
        audit.record(actor, "Scheme activated", "Scheme", s.getRef(), "From " + q.ref + " v" + q.versionNo + " on "
                + wording[0] + " v" + wording[1] + "; conditions " + String.join(", ", conditions(body)));
    }

    /** Every reason the accepted terms cannot be put in force yet (AT-GL01-01), in one answer. */
    private List<String> problems(Quote q, GroupScheme s, JsonNode body) {
        List<String> problems = new ArrayList<>();
        if (q.decidedBy == null) {
            problems.add(q.ref + " has no underwriting approval.");
        }
        if (q.freeCoverLimit == null) {
            problems.add(q.ref + " has no free-cover limit. Revise the quote to set it before activation.");
        }
        String wordingRef = text(body, "wordingRef");
        if (wordingRef == null) {
            problems.add("Choose the approved scheme wording (wordingRef) the master policy is issued on.");
        } else {
            try {
                wording(wordingRef, integer(body, "wordingVersion"));
            } catch (IllegalArgumentException ex) {
                problems.add(ex.getMessage());
            }
        }
        Set<String> given = new LinkedHashSet<>(conditions(body));
        List<String> missing = requiredConditions.stream().filter(c -> !given.contains(c)).toList();
        if (!missing.isEmpty()) {
            problems.add("Inception conditions not confirmed: " + String.join(", ", missing.stream()
                    .map(SchemeSetup::label).toList()) + ".");
        }
        List<String> categories = new ArrayList<>();
        if (s != null && !schemes.categoriesOf(s).isEmpty()) {
            for (BenefitCategory c : schemes.categoriesOf(s)) {
                checkFreeCover(problems, q, c.getCode(), c.getFreeCoverLimit());
                categories.add(c.getCode());
            }
        } else if (q.freeCoverLimit != null) {
            for (JsonNode c : categoryDefinitions(q, body)) {
                if (!c.hasNonNull("freeCoverLimit")) {
                    problems.add("Category " + c.path("code").asText() + " has no free-cover limit.");
                } else {
                    checkFreeCover(problems, q, c.path("code").asText(), new BigDecimal(c.path("freeCoverLimit").asText()));
                }
                categories.add(c.path("code").asText());
            }
        }
        if (q.freeCoverLimit != null && categories.isEmpty()) {
            problems.add("Define at least one benefit category with its free-cover limit.");
        }
        try {
            billingMethod(body);
            dueDay(body);
            aawRule(body);
        } catch (IllegalArgumentException ex) {
            problems.add(ex.getMessage());
        }
        String broker = text(body, "brokerRef");
        if (broker != null) {
            List<?> status = em.createNativeQuery("select status from dist_intermediary where record_ref = ?1")
                    .setParameter(1, broker).getResultList();
            if (status.isEmpty()) {
                problems.add("Broker " + broker + " is not a registered intermediary.");
            } else if (!"ACTIVE".equalsIgnoreCase(String.valueOf(status.get(0)))) {
                problems.add("Broker " + broker + " is " + String.valueOf(status.get(0)).toLowerCase(Locale.ROOT) + ".");
            }
        }
        return problems;
    }

    private static void checkFreeCover(List<String> problems, Quote q, String code, BigDecimal limit) {
        if (q.freeCoverLimit != null && limit != null && limit.compareTo(q.freeCoverLimit) > 0) {
            problems.add("Category " + code + " free cover of " + Money.display(limit) + " is above the accepted terms' "
                    + Money.display(q.freeCoverLimit) + ".");
        }
    }

    /**
     * The categories to create: those given on the request, else a single category from the accepted quotation's
     * design (its salary multiple, free-cover limit and the monthly equivalent of its rate).
     */
    private List<JsonNode> categoryDefinitions(Quote q, JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode given = body == null ? null : body.get("categories");
        if (given != null && given.isArray() && !given.isEmpty()) {
            given.forEach(out::add);
            return out;
        }
        QuoteTerms terms = terms(q);
        JsonNode design = terms == null ? null : json(terms.design);
        if (design != null && design.isArray() && !design.isEmpty()) {
            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            JsonNode priced = json(terms.pricingDetail);
            if (priced != null) {
                priced.path("categories").forEach(c -> {
                    if (c.hasNonNull("monthlyRatePerMille")) {
                        rates.put(c.path("category").asText(), new BigDecimal(c.get("monthlyRatePerMille").asText()));
                    }
                });
            }
            for (JsonNode d : design) {
                ObjectNode c = d.deepCopy();
                if (!c.hasNonNull("freeCoverLimit") && q.freeCoverLimit != null) {
                    c.put("freeCoverLimit", q.freeCoverLimit.toPlainString());
                }
                BigDecimal rate = rates.get(c.path("code").asText());
                if (rate != null && rate.signum() > 0 && !c.hasNonNull("ratePerMille")) {
                    c.put("ratePerMille", rate.toPlainString());
                }
                out.add(c);
            }
            return out;
        }
        ObjectNode c = mapper.createObjectNode();
        c.put("code", "ALL").put("name", "All eligible employees").put("benefitBasis", "SALARY_MULTIPLE")
                .put("salaryMultiple", q.salaryMultiple.toPlainString())
                .put("ratePerMille", monthlyRate(q).toPlainString()).put("minEntryAge", 18).put("maxEntryAge", 64);
        if (q.freeCoverLimit != null) {
            c.put("freeCoverLimit", q.freeCoverLimit.toPlainString());
        }
        out.add(c);
        return out;
    }

    /** The quoted annual premium per 1,000 of sum assured, as the monthly rate a category bills on. */
    static BigDecimal monthlyRate(Quote q) {
        BigDecimal sumAssured = q.salaryRoll.multiply(q.salaryMultiple);
        if (sumAssured.signum() <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = q.annualPremium.multiply(BigDecimal.valueOf(1000)).divide(sumAssured, 8, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
        return rate.signum() > 0 ? rate : new BigDecimal("0.0001");
    }

    /** One benefit row per quoted benefit the scheme does not define yet, approved with the quotation's terms. */
    private List<String> seedBenefits(Quote q, String schemeRef, String actor) {
        List<String> existing = em.createQuery("select b.benefitType from SchemeBenefit b where b.schemeRef = :ref",
                String.class).setParameter("ref", schemeRef).getResultList();
        List<String> seeded = new ArrayList<>();
        for (String raw : q.benefits.split(",")) {
            String type = raw.trim();
            if (type.isEmpty() || existing.contains(type)) {
                continue;
            }
            Benefit b = new Benefit();
            b.schemeRef = schemeRef;
            b.benefitType = type;
            b.basis = "GLA".equals(type) || "LUMP_SUM_DISABILITY".equals(type) ? "SALARY_MULTIPLE"
                    : "INCOME_PROTECTION".equals(type) ? "MONTHLY_INCOME_PERCENT"
                    : "CREDIT".equals(type) ? "LOAN_BALANCE" : "FIXED";
            b.multiple = "SALARY_MULTIPLE".equals(b.basis) ? q.salaryMultiple
                    : "MONTHLY_INCOME_PERCENT".equals(b.basis) ? new BigDecimal("75.00") : null;
            b.fixedAmount = "FIXED".equals(b.basis) ? GroupLifeService.PRODUCT_MAXIMUM.get(type) : null;
            b.terms = GroupLifeService.defaultTerms(type);
            b.updatedBy = actor;
            b.updatedAt = LocalDateTime.now();
            // The plan comes from the quote the underwriter approved.
            b.approvalStatus = "APPROVED";
            b.approvedBy = q.decidedBy;
            b.approvedAt = LocalDateTime.now();
            em.persist(b);
            seeded.add(type);
        }
        return seeded;
    }

    // ── quotation terms and pricing (NS-GL01-001/002) ─────────────────────────────────────────────

    /**
     * The rating basis, salary multiple, free-cover limit and the census aggregates entered (used only for an
     * indicative price); the premium itself is set by {@link #price} once the version exists.
     */
    void basis(Quote q, JsonNode body) {
        String basis = text(body, "ratingBasis");
        q.ratingBasis = basis == null ? "SALARY" : basis.toUpperCase(Locale.ROOT);
        if (!GroupRating.BASES.contains(q.ratingBasis)) {
            throw new IllegalArgumentException("ratingBasis must be SALARY, SUM_ASSURED, PER_MEMBER or LOAN_BALANCE.");
        }
        q.salaryMultiple = decimal(body, "salaryMultiple", BigDecimal.ONE).setScale(2, RoundingMode.HALF_UP);
        if (q.salaryMultiple.signum() <= 0 || q.salaryMultiple.compareTo(BigDecimal.valueOf(50)) > 0) {
            throw new IllegalArgumentException("salaryMultiple must be above 0 and at most 50.");
        }
        Integer lives = integer(body, "lives");
        q.lives = lives == null ? 0 : lives;
        q.salaryRoll = Money.of(decimal(body, "salaryRoll", BigDecimal.ZERO));
        if (q.lives < 0 || q.salaryRoll.signum() < 0) {
            throw new IllegalArgumentException("Lives and salary roll cannot be negative.");
        }
        BigDecimal rate = decimal(body, "ratePerMille", BigDecimal.ZERO);
        if (rate.signum() < 0) {
            throw new IllegalArgumentException("ratePerMille cannot be negative.");
        }
        q.ratePerMille = rate.setScale(4, RoundingMode.HALF_UP);
        q.annualPremium = BigDecimal.ZERO.setScale(2);
        String free = text(body, "freeCoverLimit");
        q.freeCoverLimit = free == null ? null : Money.of(new BigDecimal(free));
        q.validUntil = LocalDate.now().plusDays(defaultValidityDays);
    }

    /**
     * Prices a new quotation version and keeps its terms: the census (a new numbered version when one is given,
     * otherwise the previous version's), the rate source, loadings, validity and design. {@code changes} is what the
     * user sent; {@code previous} the version it revises, whose terms carry over unless changed.
     */
    void price(Quote q, JsonNode changes, Quote previous, AppUser user) {
        QuoteTerms base = previous == null ? null : terms(previous);
        QuoteTerms t = new QuoteTerms();
        t.quoteId = q.id;
        apply(q, t, changes, base, previous, user.getUsername());
        em.persist(t);
        audit.record(user.getUsername(), previous == null ? "Scheme quote prepared" : "Scheme quote revised", "SchemeQuote",
                q.ref, "v" + q.versionNo + ": " + Money.display(q.annualPremium) + " a year, " + t.pricingStatus.toLowerCase(Locale.ROOT)
                        + (t.rateTables == null ? " on a negotiated rate" : " on " + t.rateTables));
    }

    /** {csv}: a new census version for the draft version of the quotation, which is priced again on it. */
    @Transactional
    public Map<String, Object> uploadCensus(String ref, JsonNode body) {
        AppUser user = user("MANAGE_SCHEME");
        Quote q = latest(ref);
        if (!"DRAFT".equals(q.status)) {
            throw new ConflictException(ref + " v" + q.versionNo + " is " + q.status.toLowerCase(Locale.ROOT)
                    + "; revise the quotation to rate a new census on a new version.");
        }
        if (text(body, "csv") == null) {
            throw new IllegalArgumentException("csv is required: the census with a header row.");
        }
        QuoteTerms t = terms(q);
        if (t == null) {
            t = new QuoteTerms();
            t.quoteId = q.id;
            em.persist(t);
        }
        ObjectNode changes = mapper.createObjectNode();
        changes.put("census", text(body, "csv"));
        apply(q, t, changes, t, q, user.getUsername());
        audit.record(user.getUsername(), "Scheme quote census", "SchemeQuote", ref, "v" + q.versionNo + " rated on census"
                + " v" + rating.census(t.censusId).getVersion());
        Map<String, Object> out = new LinkedHashMap<>(termsView(q));
        out.put("annualPremium", q.annualPremium);
        return out;
    }

    /** The census version with every row and its outcome (the census validation summary). */
    @Transactional(readOnly = true)
    public Map<String, Object> census(String ref, int version) {
        user("READ_SCHEME");
        Long id = em.createQuery("select c.id from QuoteCensus c where c.quoteRef = :r and c.version = :v", Long.class)
                .setParameter("r", ref).setParameter("v", version).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Census v" + version + " of " + ref + " not found"));
        return rating.censusView(id, true);
    }

    private void apply(Quote q, QuoteTerms t, JsonNode changes, QuoteTerms base, Quote previous, String actor) {
        String sponsorRef = text(changes, "sponsorRef");
        if (sponsorRef != null) {
            sponsor(sponsorRef);
        }
        t.sponsorRef = sponsorRef != null ? sponsorRef : base == null ? null : base.sponsorRef;
        JsonNode categories = changes == null ? null : changes.get("categories");
        String designJson = categories != null && categories.isArray() ? designOf(categories)
                : base == null ? null : base.design;
        t.design = designJson;
        Map<String, GroupRating.DesignCategory> design = designMap(json(designJson));
        String csv = text(changes, "census");
        if (csv != null) {
            t.censusId = rating.recordCensus(q.ref, csv, q.ratingBasis, design, LocalDate.now(), actor).getId();
        } else if (base != null) {
            t.censusId = base.censusId;
        }
        String source = text(changes, "rateSource");
        if (source == null) {
            String given = text(changes, "ratePerMille");
            boolean negotiatedRate = given != null && new BigDecimal(given).signum() > 0;
            source = negotiatedRate ? QuoteTerms.NEGOTIATED : base != null && base.rateSource != null ? base.rateSource
                    : base != null && q.ratePerMille.signum() > 0 ? QuoteTerms.NEGOTIATED : QuoteTerms.TABLE;
        }
        source = source.toUpperCase(Locale.ROOT);
        if (!Set.of(QuoteTerms.TABLE, QuoteTerms.NEGOTIATED).contains(source)) {
            throw new IllegalArgumentException("rateSource must be TABLE (approved rate tables) or NEGOTIATED.");
        }
        t.rateSource = source;
        JsonNode assumptions = changes == null ? null : changes.get("assumptions");
        t.expensePercent = percent(assumptions, "expensePercent", or(base == null ? null : base.expensePercent,
                defaultExpensePercent), 0, 50);
        t.commissionPercent = percent(assumptions, "commissionPercent", or(base == null ? null : base.commissionPercent,
                defaultCommissionPercent), 0, 50);
        t.profitPercent = percent(assumptions, "profitPercent", or(base == null ? null : base.profitPercent,
                defaultProfitPercent), 0, 50);
        t.adjustmentPercent = percent(changes, "adjustmentPercent", or(base == null ? null : base.adjustmentPercent,
                BigDecimal.ZERO), -50, 100);
        Integer validity = integer(changes, "validityDays");
        t.validityDays = validity != null ? validity : base != null && base.validityDays > 0 ? base.validityDays
                : defaultValidityDays;
        if (t.validityDays < 1 || t.validityDays > 180) {
            throw new IllegalArgumentException("validityDays must be from 1 to 180.");
        }
        BigDecimal negotiated = null;
        if (QuoteTerms.NEGOTIATED.equals(source)) {
            negotiated = changes != null && changes.hasNonNull("ratePerMille") ? new BigDecimal(text(changes, "ratePerMille"))
                    : previous != null ? previous.ratePerMille : q.ratePerMille;
        }
        QuoteCensus census = rating.census(t.censusId);
        GroupRating.Pricing p = rating.price(new GroupRating.PricingRequest(List.of(q.benefits.split(",")), q.ratingBasis,
                QuoteTerms.NEGOTIATED.equals(source), negotiated, q.salaryMultiple, design, census, q.lives, q.salaryRoll,
                decimal(changes, "loanBalance", BigDecimal.ZERO), LocalDate.now(), t.expensePercent, t.commissionPercent,
                t.profitPercent, t.adjustmentPercent));
        t.pricingStatus = p.status();
        t.technicalPremium = p.technicalPremium();
        t.rateTables = p.tables().isEmpty() ? null : String.join(", ", p.tables());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("indicativeReason", p.indicativeReason());
        detail.put("sumAssured", p.sumAssured());
        detail.put("benefits", p.benefits());
        detail.put("categories", p.categories());
        try {
            t.pricingDetail = mapper.writeValueAsString(detail);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        q.annualPremium = p.annualPremium();
        q.ratePerMille = p.officeRate();
        if (QuoteTerms.RATED.equals(p.status())) {
            q.lives = p.lives();
            q.salaryRoll = p.salaryRoll();
        }
        q.validUntil = LocalDate.now().plusDays(t.validityDays);
    }

    /** SUBMIT needs the version rated on a census that passed validation (NS-GL01-002). */
    void requireRated(Quote q) {
        QuoteTerms t = terms(q);
        if (t != null && !QuoteTerms.RATED.equals(t.pricingStatus)) {
            JsonNode detail = json(t.pricingDetail);
            String reason = detail == null ? "" : detail.path("indicativeReason").asText("");
            throw new IllegalArgumentException(q.ref + " v" + q.versionNo + " is priced indicatively. " + reason
                    + " Rate it on a validated census before submitting it for approval.");
        }
    }

    /** The version's terms for the quote view: census summary, sponsor, pricing, loadings, design and history. */
    Map<String, Object> termsView(Quote q) {
        Map<String, Object> v = new LinkedHashMap<>();
        QuoteTerms t = terms(q);
        v.put("rateUnit", GroupRating.unit(q.ratingBasis));
        if (t == null) {
            v.put("pricingStatus", "RATED");
            return v;
        }
        v.put("sponsorRef", t.sponsorRef);
        v.put("rateSource", t.rateSource);
        v.put("pricingStatus", t.pricingStatus);
        v.put("technicalPremium", t.technicalPremium);
        v.put("adjustmentPercent", t.adjustmentPercent);
        Map<String, Object> assumptions = new LinkedHashMap<>();
        assumptions.put("expensePercent", t.expensePercent);
        assumptions.put("commissionPercent", t.commissionPercent);
        assumptions.put("profitPercent", t.profitPercent);
        v.put("assumptions", assumptions);
        v.put("validityDays", t.validityDays);
        v.put("rateTables", t.rateTables);
        v.put("pricing", json(t.pricingDetail));
        v.put("design", json(t.design));
        v.put("census", rating.censusView(t.censusId, false));
        return v;
    }

    /** The quotation's approval history (every version and decision from the evidence chain), oldest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String ref) {
        user("READ_SCHEME");
        latest(ref);
        List<Map<String, Object>> out = new ArrayList<>();
        audit.search("SchemeQuote", ref, null, 200).forEach(e -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("at", e.getOccurredAt());
            v.put("actor", e.getActor());
            v.put("action", e.getAction());
            v.put("detail", e.getReason());
            out.add(v);
        });
        out.sort(java.util.Comparator.comparing(m -> (LocalDateTime) m.get("at")));
        return out;
    }

    QuoteTerms terms(Quote q) {
        if (q.id == null) {
            return null;
        }
        return em.createQuery("select t from SchemeQuoteTerms t where t.quoteId = :id", QuoteTerms.class)
                .setParameter("id", q.id).getResultStream().findFirst().orElse(null);
    }

    private String designOf(JsonNode categories) {
        com.fasterxml.jackson.databind.node.ArrayNode out = mapper.createArrayNode();
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode c : categories) {
            String code = text(c, "code");
            if (code == null || !code.toUpperCase(Locale.ROOT).matches("[A-Z0-9_-]{1,20}") || !codes.add(code.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Each design category needs a unique code of letters, digits, - or _.");
            }
            String basis = text(c, "benefitBasis") == null ? BenefitCategory.SALARY_MULTIPLE
                    : text(c, "benefitBasis").toUpperCase(Locale.ROOT);
            ObjectNode d = mapper.createObjectNode();
            d.put("code", code.toUpperCase(Locale.ROOT));
            d.put("name", text(c, "name") == null ? code : text(c, "name"));
            d.put("benefitBasis", basis);
            if (BenefitCategory.SALARY_MULTIPLE.equals(basis)) {
                BigDecimal m = decimal(c, "salaryMultiple", null);
                if (m == null || m.signum() <= 0 || m.compareTo(BigDecimal.valueOf(50)) > 0) {
                    throw new IllegalArgumentException("Category " + code + " needs a salary multiple above 0 and at most 50.");
                }
                d.put("salaryMultiple", m.toPlainString());
            } else if (BenefitCategory.FIXED.equals(basis)) {
                BigDecimal f = decimal(c, "fixedCover", null);
                if (f == null || f.signum() <= 0) {
                    throw new IllegalArgumentException("Category " + code + " needs a fixed cover above 0.");
                }
                d.put("fixedCover", f.toPlainString());
            } else {
                throw new IllegalArgumentException("Category " + code + ": benefitBasis must be SALARY_MULTIPLE or FIXED.");
            }
            for (String f : new String[] {"freeCoverLimit", "minEntryAge", "maxEntryAge"}) {
                if (text(c, f) != null) {
                    d.put(f, text(c, f));
                }
            }
            out.add(d);
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static Map<String, GroupRating.DesignCategory> designMap(JsonNode design) {
        Map<String, GroupRating.DesignCategory> out = new LinkedHashMap<>();
        if (design != null && design.isArray()) {
            for (JsonNode d : design) {
                out.put(d.path("code").asText(), new GroupRating.DesignCategory(d.path("code").asText(),
                        d.path("name").asText(), d.path("benefitBasis").asText(),
                        d.hasNonNull("salaryMultiple") ? new BigDecimal(d.get("salaryMultiple").asText()) : null,
                        d.hasNonNull("fixedCover") ? new BigDecimal(d.get("fixedCover").asText()) : null));
            }
        }
        return out;
    }

    private JsonNode json(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    private static BigDecimal or(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static BigDecimal percent(JsonNode body, String field, BigDecimal fallback, int min, int max) {
        BigDecimal v = decimal(body, field, fallback);
        if (v == null) {
            return null;
        }
        if (v.compareTo(BigDecimal.valueOf(min)) < 0 || v.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalArgumentException(field + " must be from " + min + " to " + max + " percent.");
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(JsonNode body, String field, BigDecimal fallback) {
        String v = text(body, field);
        if (v == null) {
            return fallback;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field + " must be a number.");
        }
    }

    // ── wording, sponsors ──────────────────────────────────────────────────────────────────────────

    /** The approved group wordings a master policy can be issued on (SH09A library, product "Group …"). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> wordings() {
        user("READ_SCHEME");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : em.createNativeQuery("select record_ref, version_no, product, clause_type, title from doc_wording"
                + " where status = 'APPROVED' and lower(product) like 'group%' order by record_ref").getResultList()) {
            Object[] r = (Object[]) row;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("ref", r[0]);
            v.put("version", ((Number) r[1]).intValue());
            v.put("product", r[2]);
            v.put("clauseType", r[3]);
            v.put("title", r[4]);
            out.add(v);
        }
        return out;
    }

    /** [ref, version] of the approved group wording, the given version or else the approved one. */
    private Object[] wording(String ref, Integer version) {
        if (ref == null) {
            throw new IllegalArgumentException("Choose the approved scheme wording (wordingRef).");
        }
        List<?> rows = em.createNativeQuery("select version_no, status, product from doc_wording where record_ref = ?1"
                + " order by version_no desc").setParameter(1, ref).getResultList();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Wording " + ref + " is not in the wording library.");
        }
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            int v = ((Number) r[0]).intValue();
            if (version != null && version != v) {
                continue;
            }
            if (!String.valueOf(r[2]).toLowerCase(Locale.ROOT).startsWith("group")) {
                throw new IllegalArgumentException("Wording " + ref + " is " + r[2] + " wording, not a group scheme wording.");
            }
            if ("APPROVED".equals(r[1])) {
                return new Object[] {ref, v};
            }
            if (version != null) {
                throw new IllegalArgumentException("Wording " + ref + " v" + v + " is "
                        + String.valueOf(r[1]).toLowerCase(Locale.ROOT) + ", not approved.");
            }
        }
        throw new IllegalArgumentException("Wording " + ref + (version == null ? " has no approved version."
                : " has no version " + version + "."));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> sponsors() {
        user("READ_SCHEME");
        return em.createQuery("select s from GroupSponsor s order by s.ref desc", GroupSponsor.class).getResultList()
                .stream().map(SchemeSetup::sponsorView).toList();
    }

    /** {legalName, registrationNo?, sector, contactName?, contactEmail?}: a prospect sponsor (NS-GL01-001). */
    @Transactional
    public Map<String, Object> createSponsor(JsonNode body) {
        AppUser user = user("MANAGE_SCHEME");
        return sponsorView(newSponsor(required(body, "legalName"), text(body, "registrationNo"), required(body, "sector"),
                text(body, "contactName"), text(body, "contactEmail"), user.getUsername()));
    }

    GroupSponsor sponsor(String ref) {
        return em.createQuery("select s from GroupSponsor s where s.ref = :ref", GroupSponsor.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Sponsor " + ref + " not found"));
    }

    private GroupSponsor sponsorFor(Quote q, JsonNode body, String actor) {
        String ref = text(body, "sponsorRef");
        if (ref != null) {
            return sponsor(ref);
        }
        return em.createQuery("select s from GroupSponsor s where lower(s.legalName) = :name", GroupSponsor.class)
                .setParameter("name", q.sponsorName.toLowerCase(Locale.ROOT)).getResultStream().findFirst()
                .orElseGet(() -> newSponsor(q.sponsorName, text(body, "registrationNo"), q.sector, null, null, actor));
    }

    private GroupSponsor newSponsor(String legalName, String registrationNo, String sector, String contactName,
            String contactEmail, String actor) {
        if (legalName.length() > 150 || (registrationNo != null && registrationNo.length() > 40)) {
            throw new IllegalArgumentException("Legal name is at most 150 characters and the registration number 40.");
        }
        if (contactEmail != null && !contactEmail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Contact e-mail is not an e-mail address.");
        }
        if (registrationNo != null && em.createQuery("select count(s) from GroupSponsor s where s.registrationNo = :r",
                Long.class).setParameter("r", registrationNo).getSingleResult() > 0) {
            throw new ConflictException("A sponsor with registration number " + registrationNo + " already exists.");
        }
        GroupSponsor s = new GroupSponsor(numbers.next("SPN-", 4, () -> 0), legalName, registrationNo, sector,
                contactName, contactEmail, actor);
        em.persist(s);
        return s;
    }

    static Map<String, Object> sponsorView(GroupSponsor s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ref", s.getRef());
        v.put("legalName", s.getLegalName());
        v.put("registrationNo", s.getRegistrationNo());
        v.put("sector", s.getSector());
        v.put("contactName", s.getContactName());
        v.put("contactEmail", s.getContactEmail());
        v.put("status", s.getStatus());
        v.put("createdAt", s.getCreatedAt());
        return v;
    }

    // ── request parsing ────────────────────────────────────────────────────────────────────────────

    private static List<String> conditions(JsonNode body) {
        List<String> out = new ArrayList<>();
        JsonNode list = body == null ? null : body.get("conditions");
        if (list instanceof ArrayNode array) {
            array.forEach(c -> out.add(c.asText().trim().toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    private static String billingMethod(JsonNode body) {
        String m = text(body, "billingMethod");
        m = m == null ? "EFT" : m.toUpperCase(Locale.ROOT);
        if (!BILLING_METHODS.contains(m)) {
            throw new IllegalArgumentException("billingMethod must be EFT, DEBIT_ORDER or STOP_ORDER.");
        }
        return m;
    }

    private static Integer dueDay(JsonNode body) {
        Integer d = integer(body, "billingDueDay");
        d = d == null ? 1 : d;
        if (d < 1 || d > 28) {
            throw new IllegalArgumentException("billingDueDay must be a day of the month from 1 to 28.");
        }
        return d;
    }

    private static String aawRule(JsonNode body) {
        String r = text(body, "aawRule");
        r = r == null ? "REFER" : r.toUpperCase(Locale.ROOT);
        if (!AAW_RULES.contains(r)) {
            throw new IllegalArgumentException("aawRule must be REFER (refer the cover to underwriting) or DEFER (defer"
                    + " cover until the member is actively at work).");
        }
        return r;
    }

    private static boolean partialCover(JsonNode body) {
        return body == null || !body.hasNonNull("partialCoverAllowed") || body.get("partialCoverAllowed").asBoolean(true);
    }

    private static void expectAccepted(Quote q) {
        if (!"ACCEPTED".equals(q.status)) {
            throw new ConflictException(q.ref + " v" + q.versionNo + " is " + q.status.toLowerCase(Locale.ROOT)
                    + "; it must be accepted for this step.");
        }
    }

    private Quote latest(String ref) {
        return em.createQuery("select q from SchemeQuote q where q.ref = :ref order by q.versionNo desc", Quote.class)
                .setParameter("ref", ref).setMaxResults(1).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Quote " + ref + " not found"));
    }

    private GroupScheme scheme(String ref) {
        return em.createQuery("select s from GroupScheme s where s.ref = :ref", GroupScheme.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Scheme " + ref + " not found"));
    }

    static String label(String code) {
        String t = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    static String text(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        String v = body.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }

    private static String required(JsonNode body, String field) {
        String v = text(body, field);
        if (v == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return v;
    }

    private static Integer integer(JsonNode body, String field) {
        String v = text(body, field);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field + " must be a whole number.");
        }
    }
}

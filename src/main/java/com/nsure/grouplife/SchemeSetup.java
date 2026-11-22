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
    /** Demo default (D06/D11 open): the inception conditions every activation must confirm. */
    private final List<String> requiredConditions;

    public SchemeSetup(EntityManager em, PlatformSecurityContext security, Outbox outbox, ReferenceNumbers numbers,
            EvidenceChain audit, GroupSchemeService schemes, ObjectMapper mapper,
            @Value("${nsure.group.activation.inception-conditions:SIGNED_PROPOSAL,SIGNED_CENSUS,FIRST_PREMIUM_OR_DEFERRAL}")
            List<String> requiredConditions) {
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
                schemes.addCategory(s.getRef(), c);
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

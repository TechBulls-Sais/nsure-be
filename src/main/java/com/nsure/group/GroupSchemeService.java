package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.portfolio.GroupScheme;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Schemes (GL01), benefit categories (GL02) and member underwriting decisions (GL03-lite). */
@Service
public class GroupSchemeService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_-]{1,20}");
    private static final String[] COLORS = {"green", "purple", "blue"};

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final GroupRecords records;
    private final com.nsure.audit.evidence.EvidenceChain audit;
    private final BenefitRules benefitRules;
    private final MemberUnderwriting underwriting;

    public GroupSchemeService(EntityManager em, PlatformSecurityContext security, GroupRecords records,
            com.nsure.audit.evidence.EvidenceChain audit, BenefitRules benefitRules, MemberUnderwriting underwriting) {
        this.benefitRules = benefitRules;
        this.underwriting = underwriting;
        this.em = em;
        this.security = security;
        this.records = records;
        this.audit = audit;
    }

    // ── schemes ────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<GroupViews.SchemeView> schemes() {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        Map<Long, int[]> members = new HashMap<>();
        Map<Long, BigDecimal> premium = new HashMap<>();
        for (SchemeMember m : em.createQuery("select m from SchemeMember m join fetch m.category"
                        + " where m.status in :statuses", SchemeMember.class)
                .setParameter("statuses", SchemeMember.ON_COVER)
                .getResultList()) {
            Long schemeId = m.getScheme().getId();
            int[] counts = members.computeIfAbsent(schemeId, id -> new int[2]);
            counts[0]++;
            if (SchemeMember.UNDERWRITING_PENDING.equals(m.getStatus())) {
                counts[1]++;
            }
            premium.merge(schemeId, m.monthlyPremium(), BigDecimal::add);
        }
        Map<Long, BigDecimal> outstanding = new HashMap<>();
        for (Object[] row : em.createQuery("select i.scheme.id, sum(i.outstanding) from SchemeInvoice i"
                + " group by i.scheme.id", Object[].class).getResultList()) {
            outstanding.put((Long) row[0], (BigDecimal) row[1]);
        }
        Map<Long, Integer> categories = new HashMap<>();
        for (Object[] row : em.createQuery("select c.scheme.id, count(c) from BenefitCategory c"
                + " group by c.scheme.id", Object[].class).getResultList()) {
            categories.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return em.createQuery("select s from GroupScheme s order by s.displayOrder, s.id", GroupScheme.class)
                .getResultList()
                .stream()
                .map(s -> {
                    int[] counts = members.getOrDefault(s.getId(), new int[2]);
                    return records.schemeView(s, counts[0], counts[1],
                            premium.getOrDefault(s.getId(), BigDecimal.ZERO),
                            outstanding.getOrDefault(s.getId(), BigDecimal.ZERO),
                            categories.getOrDefault(s.getId(), 0));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupViews.SchemeDetailView scheme(String ref) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        return detail(records.scheme(ref));
    }

    /**
     * Creates the scheme's master record in SETUP (NS-GL01-004): its categories can be prepared, but it takes no census,
     * underwriting or cover until it is activated from an accepted quotation with the approved wording and inception
     * conditions ({@code com.nsure.grouplife.SchemeSetup}), which publishes SchemeActivated.
     */
    @Transactional
    public GroupViews.SchemeDetailView createScheme(JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        String name = GroupRecords.requiredText(body, "name", "Scheme name", 100);
        String sector = GroupRecords.requiredText(body, "sector", "Sector", 80);
        LocalDate inception = GroupRecords.date(body, "inceptionDate", "Inception date");
        LocalDate renewal = GroupRecords.date(body, "renewalDate", "Renewal date");
        if (!renewal.isAfter(inception)) {
            throw new IllegalArgumentException("Renewal date must be after the inception date");
        }
        String frequency = GroupRecords.text(body, "billingFrequency");
        frequency = frequency == null ? "MONTHLY" : frequency.toUpperCase(Locale.ROOT);
        if (!"MONTHLY".equals(frequency)) {
            throw new IllegalArgumentException("Only MONTHLY billing is supported");
        }
        Long sameName = em.createQuery("select count(s) from GroupScheme s where lower(s.name) = :name", Long.class)
                .setParameter("name", name.toLowerCase(Locale.ROOT))
                .getSingleResult();
        if (sameName > 0) {
            throw new ConflictException("A scheme named " + name + " already exists");
        }
        Integer maxOrder = em.createQuery("select max(s.displayOrder) from GroupScheme s", Integer.class)
                .getSingleResult();
        int order = maxOrder == null ? 1 : maxOrder + 1;
        GroupScheme scheme = new GroupScheme(records.nextRef(GroupScheme.class, "SCH-", 3), name, sector,
                initials(name), COLORS[(order - 1) % COLORS.length], order, inception, renewal,
                renewal.format(DISPLAY), frequency);
        em.persist(scheme);
        audit.record(user.getUsername(), "Scheme set up", "Scheme", scheme.getRef(), "In set-up until activated");
        return detail(scheme);
    }

    /**
     * Publishes SchemeActivated for a scheme just put in force from accepted terms (GL01), with the quotation, wording
     * version and sponsor it was activated on.
     */
    public void activated(GroupScheme scheme, String actor) {
        records.refreshTotals(scheme);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("schemeRef", scheme.getRef());
        data.put("quoteRef", scheme.getQuoteRef());
        data.put("sponsor", scheme.getName());
        data.put("sponsorRef", scheme.getSponsorRef());
        data.put("inceptionDate", scheme.getInceptionDate().toString());
        data.put("wording", scheme.getWordingRef() + " v" + scheme.getWordingVersion());
        records.publish(com.nsure.integration.Events.SCHEME_ACTIVATED, scheme.getRef(), data, actor);
    }

    /** The scheme row locked for update, for the activation that changes it. */
    public GroupScheme lockScheme(String ref) {
        return records.lockedScheme(ref);
    }

    /** The scheme's categories, in code order. */
    public List<BenefitCategory> categoriesOf(GroupScheme scheme) {
        return records.categories(scheme);
    }

    /**
     * Deletes a scheme set up by mistake. Once it has any contractual or financial activity (activated from a quote,
     * members, census, invoices, claims, loans or lifecycle requests) it is refused: such a scheme is closed instead,
     * so its cover history is kept (NS-GL01-006).
     */
    @Transactional
    public void deleteScheme(String ref) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        GroupScheme scheme = records.lockedScheme(ref);
        List<String> activity = new java.util.ArrayList<>();
        if (scheme.getQuoteRef() != null) {
            activity.add("its master policy was activated from " + scheme.getQuoteRef());
        }
        count(activity, "member episode(s)", "select count(m) from SchemeMember m where m.scheme = :s", scheme);
        count(activity, "census batch(es)", "select count(b) from CensusBatch b where b.scheme = :s", scheme);
        count(activity, "invoice(s)", "select count(i) from SchemeInvoice i where i.scheme = :s", scheme);
        count(activity, "claim(s)", "select count(c) from GroupClaim c where c.scheme = :s", scheme);
        countByRef(activity, "credit loan(s)", "select count(l) from GroupCreditLoan l where l.schemeRef = :ref", ref);
        countByRef(activity, "lifecycle request(s)", "select count(e) from SchemeEvent e where e.schemeRef = :ref", ref);
        if (!activity.isEmpty()) {
            throw new ConflictException("Scheme " + ref + " cannot be deleted: " + String.join(", ", activity)
                    + ". Close it instead; its cover history is kept.");
        }
        em.createQuery("delete from SchemeBenefit b where b.schemeRef = :ref").setParameter("ref", ref).executeUpdate();
        em.createQuery("delete from GroupBenefitTerms t where t.schemeRef = :ref").setParameter("ref", ref).executeUpdate();
        em.createQuery("delete from GroupCategoryVersion v where v.category in (select c from BenefitCategory c"
                + " where c.scheme = :s)").setParameter("s", scheme).executeUpdate();
        em.createQuery("delete from BenefitCategory c where c.scheme = :s").setParameter("s", scheme).executeUpdate();
        em.remove(scheme);
        audit.record(user.getUsername(), "Scheme deleted", "Scheme", ref, "No contractual or financial activity");
        // Its SchemeActivated was published, so downstream consumers hear that it has gone.
        records.publish(com.nsure.integration.Events.SCHEME_CLOSED, ref, Map.of("schemeRef", ref, "closedOn",
                LocalDate.now().toString(), "reason", "Deleted before any contractual or financial activity"),
                user.getUsername());
    }

    private void count(List<String> activity, String label, String jpql, GroupScheme scheme) {
        long n = em.createQuery(jpql, Long.class).setParameter("s", scheme).getSingleResult();
        if (n > 0) {
            activity.add(n + " " + label);
        }
    }

    private void countByRef(List<String> activity, String label, String jpql, String ref) {
        long n = em.createQuery(jpql, Long.class).setParameter("ref", ref).getSingleResult();
        if (n > 0) {
            activity.add(n + " " + label);
        }
    }

    private static String initials(String name) {
        String[] words = name.trim().split("[^\\p{L}\\p{N}]+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty() && out.length() < 2) {
                out.append(word.charAt(0));
            }
        }
        if (out.length() < 2 && words.length > 0 && words[0].length() > 1) {
            out.append(words[0].charAt(1));
        }
        return out.length() == 0 ? "GS" : out.toString().toUpperCase(Locale.ROOT);
    }

    private GroupViews.SchemeDetailView detail(GroupScheme scheme) {
        List<BenefitCategory> categories = records.categories(scheme);
        List<SchemeMember> members = em.createQuery("select m from SchemeMember m join fetch m.category"
                        + " where m.scheme = :scheme order by m.lastName, m.firstName, m.id", SchemeMember.class)
                .setParameter("scheme", scheme)
                .getResultList();
        Map<String, Integer> perCategory = new HashMap<>();
        int active = 0;
        int pending = 0;
        BigDecimal premium = BigDecimal.ZERO;
        for (SchemeMember m : members) {
            if (m.onCover()) {
                active++;
                perCategory.merge(m.getCategory().getCode(), 1, Integer::sum);
                premium = premium.add(m.monthlyPremium());
                if (SchemeMember.UNDERWRITING_PENDING.equals(m.getStatus())) {
                    pending++;
                }
            }
        }
        List<SchemeInvoice> invoices = em.createQuery("select i from SchemeInvoice i where i.scheme = :scheme"
                        + " order by i.period desc", SchemeInvoice.class)
                .setParameter("scheme", scheme)
                .getResultList();
        BigDecimal outstanding = invoices.stream().map(SchemeInvoice::getOutstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<GroupViews.CensusBatchSummaryView> batches = em.createQuery("select b from CensusBatch b"
                        + " where b.scheme = :scheme order by b.id desc", CensusBatch.class)
                .setParameter("scheme", scheme)
                .getResultList()
                .stream()
                .map(GroupRecords::batchSummary)
                .toList();
        return new GroupViews.SchemeDetailView(
                records.schemeView(scheme, active, pending, premium, outstanding, categories.size()),
                categories.stream().map(c -> GroupRecords.categoryView(c, perCategory.getOrDefault(c.getCode(), 0)))
                        .toList(),
                members.stream().map(GroupRecords::memberView).toList(),
                batches,
                records.invoiceViews(invoices));
    }

    // ── benefit categories ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public GroupViews.CategoryView addCategory(String schemeRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        GroupScheme scheme = records.lockedScheme(schemeRef);
        com.nsure.common.ExpectedVersion.check("Scheme " + schemeRef, scheme.getRowVersion());
        GroupRecords.requireOpen(scheme);
        String code = GroupRecords.requiredText(body, "code", "Category code", 20).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Category code may use only letters, digits, - and _");
        }
        if (findCategory(scheme, code) != null) {
            throw new ConflictException("Category " + code + " already exists on scheme " + schemeRef);
        }
        BenefitCategory category = new BenefitCategory(scheme, code);
        define(category, body, user.getUsername());
        em.persist(category);
        // Version 1 applies from the scheme's inception (GL02: categories carry dated versions).
        em.persist(category.snapshot(scheme.getInceptionDate(), "Category added", null, user.getUsername()));
        termsChanged(scheme, category, "ADDED", user.getUsername());
        return GroupRecords.categoryView(category, 0);
    }

    /** Amends a category prospectively: existing members keep the cover recorded on their episodes. */
    @Transactional
    public GroupViews.CategoryView updateCategory(String schemeRef, String code, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        GroupScheme scheme = records.lockedScheme(schemeRef);
        com.nsure.common.ExpectedVersion.check("Scheme " + schemeRef, scheme.getRowVersion());
        GroupRecords.requireOpen(scheme);
        BenefitCategory category = findCategory(scheme, code.toUpperCase(Locale.ROOT));
        if (category == null) {
            throw new NotFoundException("Category " + code + " not found on scheme " + schemeRef);
        }
        LocalDate effective = amendmentDate(scheme, category, body, user);
        Map<String, Object> impact = categoryImpact(scheme, category, body, effective);
        if (category.getVersions().isEmpty()) {
            // Terms set up before versions were kept become version 1, from the scheme's inception.
            em.persist(category.snapshot(scheme.getInceptionDate(), "Terms before versions were kept", null,
                    category.getUpdatedBy()));
        }
        define(category, body, user.getUsername());
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) impact.get("notes");
        em.persist(category.snapshot(effective, GroupRecords.text(body, "reason"), String.join(" ", notes),
                user.getUsername()));
        Long active = em.createQuery("select count(m) from SchemeMember m where m.category = :category"
                        + " and m.status in :statuses", Long.class)
                .setParameter("category", category)
                .setParameter("statuses", SchemeMember.ON_COVER)
                .getSingleResult();
        // The rate applies to future invoices, so the dashboard premium follows it.
        records.refreshTotals(scheme);
        termsChanged(scheme, category, "AMENDED", user.getUsername());
        return GroupRecords.categoryView(category, active.intValue());
    }

    /** GL02 CategoryTermsChanged: the category's terms as they now stand, for downstream rating and documents. */
    private void termsChanged(GroupScheme scheme, BenefitCategory c, String change, String actor) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("schemeRef", scheme.getRef());
        data.put("categoryCode", c.getCode());
        data.put("change", change);
        data.put("benefitBasis", c.getBenefitBasis());
        data.put("salaryMultiple", c.getSalaryMultiple() == null ? null : c.getSalaryMultiple().toPlainString());
        data.put("fixedCover", c.getFixedCover() == null ? null : c.getFixedCover().toPlainString());
        data.put("freeCoverLimit", c.getFreeCoverLimit().toPlainString());
        data.put("ratePerMille", c.getRatePerMille().toPlainString());
        records.publish(com.nsure.integration.Events.CATEGORY_TERMS_CHANGED, scheme.getRef(), data, actor);
    }

    private BenefitCategory findCategory(GroupScheme scheme, String code) {
        return em.createQuery("select c from BenefitCategory c where c.scheme = :scheme and c.code = :code",
                        BenefitCategory.class)
                .setParameter("scheme", scheme)
                .setParameter("code", code)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private static void define(BenefitCategory category, JsonNode body, String actor) {
        String name = GroupRecords.requiredText(body, "name", "Category name", 100);
        String basis = GroupRecords.requiredText(body, "benefitBasis", "Benefit basis", 20).toUpperCase(Locale.ROOT);
        BigDecimal multiple = null;
        BigDecimal fixed = null;
        if (BenefitCategory.SALARY_MULTIPLE.equals(basis)) {
            multiple = GroupRecords.decimal(body, "salaryMultiple", "Salary multiple");
            if (multiple == null || multiple.signum() <= 0) {
                throw new IllegalArgumentException("Salary multiple must be more than zero for a salary-multiple category");
            }
            if (multiple.compareTo(BigDecimal.valueOf(50)) > 0 || multiple.stripTrailingZeros().scale() > 4) {
                throw new IllegalArgumentException("Salary multiple must be at most 50 with up to four decimals");
            }
            multiple = multiple.setScale(4);
        } else if (BenefitCategory.FIXED.equals(basis)) {
            fixed = GroupRecords.money(GroupRecords.decimal(body, "fixedCover", "Fixed cover"), "Fixed cover", false);
        } else {
            throw new IllegalArgumentException("Benefit basis must be SALARY_MULTIPLE or FIXED");
        }
        BigDecimal freeCover = GroupRecords.money(
                GroupRecords.decimal(body, "freeCoverLimit", "Free cover limit"), "Free cover limit", true);
        BigDecimal rate = GroupRecords.decimal(body, "ratePerMille", "Rate per mille");
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("Rate per mille must be more than zero");
        }
        if (rate.compareTo(BigDecimal.valueOf(1000)) >= 0 || rate.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("Rate per mille must be below 1000 with up to four decimals");
        }
        Integer minAge = GroupRecords.integer(body, "minEntryAge", "Minimum entry age");
        Integer maxAge = GroupRecords.integer(body, "maxEntryAge", "Maximum entry age");
        if (minAge == null || maxAge == null) {
            throw new IllegalArgumentException("Minimum and maximum entry ages are required");
        }
        if (minAge < 0 || maxAge > 100 || minAge >= maxAge) {
            throw new IllegalArgumentException("Entry ages must satisfy 0 <= minimum < maximum <= 100");
        }
        category.define(name, basis, multiple, fixed, freeCover, rate.setScale(4), minAge, maxAge, actor);
        // Optional: no window means no late-entrant rule (the insurer sets it per category).
        Integer lateEntryDays = GroupRecords.integer(body, "lateEntryDays", "Late-entry window");
        if (lateEntryDays != null && (lateEntryDays < 0 || lateEntryDays > 3650)) {
            throw new IllegalArgumentException("Late-entry window must be between 0 and 3650 days");
        }
        category.lateEntry(lateEntryDays);
        // NS-GL02-001: the approved factors the category is defined by, its cessation age, waiting period, cap and
        // eligibility rule (all optional; none set means no such rule).
        String employmentClass = GroupRecords.text(body, "employmentClass");
        if (employmentClass != null && employmentClass.length() > 60) {
            throw new IllegalArgumentException("Employment class must be at most 60 characters");
        }
        String occupationClass = upper(GroupRecords.text(body, "occupationClass"));
        if (occupationClass != null && !Set.of("CLASS_1", "CLASS_2", "CLASS_3", "CLASS_4").contains(occupationClass)) {
            throw new IllegalArgumentException("Occupation class must be CLASS_1, CLASS_2, CLASS_3 or CLASS_4");
        }
        String salaryBasis = upper(GroupRecords.text(body, "salaryBasis"));
        if (salaryBasis != null && !Set.of("BASIC", "PENSIONABLE", "TOTAL_EARNINGS").contains(salaryBasis)) {
            throw new IllegalArgumentException("Salary basis must be BASIC, PENSIONABLE or TOTAL_EARNINGS");
        }
        Integer exitAge = GroupRecords.integer(body, "exitAge", "Exit age");
        if (exitAge != null && (exitAge <= maxAge || exitAge > 80)) {
            throw new IllegalArgumentException("Exit age must be above the maximum entry age and at most 80");
        }
        Integer waitingDays = GroupRecords.integer(body, "waitingDays", "Waiting period");
        if (waitingDays != null && (waitingDays < 0 || waitingDays > 730)) {
            throw new IllegalArgumentException("Waiting period must be between 0 and 730 days");
        }
        BigDecimal maxCover = GroupRecords.decimal(body, "maxCover", "Maximum cover");
        if (maxCover != null) {
            maxCover = GroupRecords.money(maxCover, "Maximum cover", false);
        }
        String eligibility = GroupRecords.text(body, "eligibilityRule");
        if (eligibility != null && eligibility.length() > 300) {
            throw new IllegalArgumentException("Eligibility rule must be at most 300 characters");
        }
        category.factors(employmentClass, occupationClass, salaryBasis, exitAge, waitingDays, maxCover, eligibility);
    }

    private static String upper(String v) {
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    /**
     * The date an amendment applies from (NS-GL02-006): today by default; an earlier date needs the underwriting
     * authority and a reason, and cannot go back before the category's latest version or the scheme's inception.
     */
    private LocalDate amendmentDate(GroupScheme scheme, BenefitCategory category, JsonNode body, AppUser user) {
        LocalDate today = LocalDate.now();
        LocalDate effective = GroupRecords.text(body, "effectiveFrom") == null ? today
                : GroupRecords.date(body, "effectiveFrom", "Effective date");
        if (effective.isAfter(today)) {
            throw new IllegalArgumentException("An amendment applies from today; enter it on its effective date.");
        }
        if (effective.isBefore(scheme.getInceptionDate())) {
            throw new IllegalArgumentException("The effective date cannot be before the scheme's inception on "
                    + scheme.getInceptionDate());
        }
        List<PlanRecords.CategoryVersion> versions = category.getVersions();
        if (!versions.isEmpty() && effective.isBefore(versions.get(versions.size() - 1).effectiveFrom)) {
            throw new IllegalArgumentException("The effective date cannot be before version "
                    + versions.get(versions.size() - 1).versionNo + " of " + category.getCode() + " ("
                    + versions.get(versions.size() - 1).effectiveFrom + ")");
        }
        String reason = GroupRecords.text(body, "reason");
        if (reason != null && reason.length() > 500) {
            throw new IllegalArgumentException("Reason must be at most 500 characters");
        }
        if (effective.isBefore(today)) {
            user.validateHasPermissionTo("UNDERWRITE_MEMBER");
            if (reason == null) {
                throw new IllegalArgumentException("A backdated amendment needs a reason (the authority for it)");
            }
        }
        return effective;
    }

    /**
     * What amending the category as proposed would do (NS-GL02-006), with nothing changed: members on cover, their
     * cover now and on the new formula at their next change, the monthly premium on the current and proposed rate,
     * members whose new cover would need evidence above the free-cover limit, and certificates to reissue.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> previewCategoryChange(String schemeRef, String code, JsonNode body) {
        security.authenticatedUser().validateHasPermissionTo("MANAGE_SCHEME");
        GroupScheme scheme = records.scheme(schemeRef);
        BenefitCategory category = findCategory(scheme, code.toUpperCase(Locale.ROOT));
        if (category == null) {
            throw new NotFoundException("Category " + code + " not found on scheme " + schemeRef);
        }
        LocalDate effective = GroupRecords.text(body, "effectiveFrom") == null ? LocalDate.now()
                : GroupRecords.date(body, "effectiveFrom", "Effective date");
        return categoryImpact(scheme, category, body, effective);
    }

    private Map<String, Object> categoryImpact(GroupScheme scheme, BenefitCategory category, JsonNode body,
            LocalDate effective) {
        BenefitCategory proposed = new BenefitCategory(scheme, category.getCode());
        ObjectNode merged = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (body != null) {
            merged.setAll((ObjectNode) body.deepCopy());
        }
        if (!merged.hasNonNull("name")) {
            merged.put("name", category.getName());
        }
        define(proposed, merged, "preview");
        List<SchemeMember> members = em.createQuery("select m from SchemeMember m where m.category = :c"
                + " and m.status in :statuses", SchemeMember.class).setParameter("c", category)
                .setParameter("statuses", SchemeMember.ON_COVER).getResultList();
        BigDecimal coverNow = BigDecimal.ZERO;
        BigDecimal coverNew = BigDecimal.ZERO;
        BigDecimal premiumNow = BigDecimal.ZERO;
        BigDecimal premiumNew = BigDecimal.ZERO;
        int needEvidence = 0;
        int changedCover = 0;
        for (SchemeMember m : members) {
            BigDecimal next = proposed.coverFor(m.getSalary());
            coverNow = coverNow.add(m.getAcceptedCover());
            coverNew = coverNew.add(next);
            premiumNow = premiumNow.add(category.monthlyPremium(m.getAcceptedCover()));
            premiumNew = premiumNew.add(proposed.monthlyPremium(m.getAcceptedCover()));
            BigDecimal ceiling = m.getUnderwrittenCover() != null && m.getUnderwrittenCover()
                    .compareTo(proposed.getFreeCoverLimit()) > 0 ? m.getUnderwrittenCover() : proposed.getFreeCoverLimit();
            if (next.compareTo(ceiling) > 0) {
                needEvidence++;
            }
            if (next.compareTo(m.getRequestedCover()) != 0) {
                changedCover++;
            }
        }
        Map<String, Object> v = new java.util.LinkedHashMap<>();
        v.put("category", category.getCode());
        v.put("effectiveFrom", effective);
        v.put("backdated", effective.isBefore(LocalDate.now()));
        v.put("membersOnCover", members.size());
        v.put("coverInForce", coverNow.setScale(2, java.math.RoundingMode.HALF_UP));
        v.put("coverOnNewFormula", coverNew.setScale(2, java.math.RoundingMode.HALF_UP));
        v.put("monthlyPremiumNow", premiumNow.setScale(2, java.math.RoundingMode.HALF_UP));
        v.put("monthlyPremiumProposed", premiumNew.setScale(2, java.math.RoundingMode.HALF_UP));
        v.put("membersNeedingEvidence", needEvidence);
        v.put("certificatesToReissue", members.stream().filter(m -> m.getAcceptedCover().signum() > 0).count());
        List<String> notes = new java.util.ArrayList<>();
        notes.add(members.size() + " member(s) on cover keep their recorded cover; " + changedCover + " would get a"
                + " different amount on the new formula at their next change dated from " + effective + ".");
        notes.add("Monthly premium on cover in force: " + com.nsure.common.Money.display(premiumNow) + " now, "
                + com.nsure.common.Money.display(premiumNew) + " at the proposed rate (future invoices).");
        if (needEvidence > 0) {
            notes.add(needEvidence + " member(s) would need underwriting evidence for cover above the free-cover limit.");
        }
        if (effective.isBefore(LocalDate.now())) {
            notes.add("Backdated to " + effective + ": joins and salary changes dated from then use the new formula.");
        }
        v.put("notes", notes);
        return v;
    }

    // ── benefit cessation (section 08 GLA: separate from scheme renewal; NS-GL03-005 eligibility changes) ──

    /**
     * Members on cover who have reached their cessation age by {@code asAt}: the category's exit age, else the scheme's
     * resolved GLA cessation age. Cover ceases on its own date, not with the scheme's renewal.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> cessations(String schemeRef, String asAtText) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        GroupScheme scheme = records.scheme(schemeRef);
        LocalDate asAt = asAtText == null || asAtText.isBlank() ? LocalDate.now() : LocalDate.parse(asAtText.trim());
        return dueForCessation(scheme, asAt).stream().map(d -> {
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("memberId", d.member().getId());
            v.put("memberKey", d.member().getMemberKey());
            v.put("name", d.member().fullName());
            v.put("category", d.member().getCategory().getCode());
            v.put("dateOfBirth", d.member().getDateOfBirth());
            v.put("cessationAge", d.age());
            v.put("ceasesOn", d.lastDay());
            v.put("acceptedCover", d.member().getAcceptedCover());
            v.put("rule", d.rule());
            return v;
        }).toList();
    }

    /** {asAt?}: ceases the cover of every member due (see {@link #cessations}); each keeps their history. */
    @Transactional
    public List<Map<String, Object>> applyCessations(String schemeRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        GroupScheme scheme = records.lockedScheme(schemeRef);
        GroupRecords.requireActive(scheme, "a cessation run");
        String asAtText = GroupRecords.text(body, "asAt");
        LocalDate asAt = asAtText == null ? LocalDate.now() : GroupRecords.date(body, "asAt", "As-at date");
        if (asAt.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Cessation runs up to today, not ahead of it.");
        }
        List<Map<String, Object>> done = cessations(schemeRef, asAt.toString());
        for (Due d : dueForCessation(scheme, asAt)) {
            BigDecimal cover = d.member().getAcceptedCover();
            d.member().cease(d.lastDay());
            records.memberChanged(new MemberChange(d.member(), "CESSATION", d.lastDay().plusDays(1),
                    d.member().getSalary(), cover, null, "Reached the cessation age of " + d.age() + " (" + d.rule()
                            + "); cover ended " + d.lastDay(), user.getUsername()));
        }
        records.refreshTotals(scheme);
        audit.record(user.getUsername(), "Benefit cessation run", "Scheme", schemeRef, done.size() + " member(s) as at "
                + asAt);
        return done;
    }

    private record Due(SchemeMember member, int age, LocalDate lastDay, String rule) {
    }

    private List<Due> dueForCessation(GroupScheme scheme, LocalDate asAt) {
        List<Due> out = new java.util.ArrayList<>();
        for (SchemeMember m : em.createQuery("select m from SchemeMember m join fetch m.category where m.scheme = :s"
                + " and m.status in :statuses order by m.memberKey", SchemeMember.class).setParameter("s", scheme)
                .setParameter("statuses", SchemeMember.ON_COVER).getResultList()) {
            BenefitCategory c = m.getCategory();
            Integer age = c.getExitAge();
            String rule = "category " + c.getCode() + " exit age";
            if (age == null) {
                String resolved = benefitRules.resolved(scheme.getRef(), c.getCode(), "GLA", asAt).get("CESSATION_AGE");
                age = resolved == null ? null : Integer.valueOf(resolved);
                rule = "GLA cessation age";
            }
            if (age == null || m.getDateOfBirth() == null) {
                continue;
            }
            LocalDate birthday = m.getDateOfBirth().plusYears(age);
            if (!birthday.isAfter(asAt)) {
                LocalDate last = birthday.minusDays(1);
                out.add(new Due(m, age, last.isBefore(m.getJoinDate()) ? m.getJoinDate() : last, rule));
            }
        }
        return out;
    }

    /** The category's dated versions, newest first (GL02 workspace). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> categoryVersions(String schemeRef, String code) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        GroupScheme scheme = records.scheme(schemeRef);
        BenefitCategory category = findCategory(scheme, code.toUpperCase(Locale.ROOT));
        if (category == null) {
            throw new NotFoundException("Category " + code + " not found on scheme " + schemeRef);
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (PlanRecords.CategoryVersion cv : category.getVersions()) {
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("version", cv.versionNo);
            v.put("effectiveFrom", cv.effectiveFrom);
            v.put("benefitBasis", cv.benefitBasis);
            v.put("salaryMultiple", cv.salaryMultiple);
            v.put("fixedCover", cv.fixedCover);
            v.put("maxCover", cv.maxCover);
            v.put("freeCoverLimit", cv.freeCoverLimit);
            v.put("ratePerMille", cv.ratePerMille);
            v.put("reason", cv.changeReason);
            v.put("impact", cv.impact);
            v.put("changedBy", cv.changedBy);
            v.put("changedAt", cv.changedAt);
            out.add(0, v);
        }
        return out;
    }

    /**
     * The member underwriting queue across schemes (GL03 workspace): every member with cover pending underwriting,
     * oldest first, with the free-cover comparison, the actively-at-work declaration and why it was referred, beside
     * the scheme's aggregate exposure in force and pending.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> underwritingQueue() {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        Map<Long, BigDecimal[]> exposure = new HashMap<>();
        for (Object[] row : em.createQuery("select m.scheme.id, sum(m.acceptedCover), sum(m.pendingCover)"
                        + " from SchemeMember m where m.status in :statuses group by m.scheme.id", Object[].class)
                .setParameter("statuses", SchemeMember.ON_COVER).getResultList()) {
            exposure.put((Long) row[0], new BigDecimal[] {(BigDecimal) row[1], (BigDecimal) row[2]});
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (SchemeMember m : em.createQuery("select m from SchemeMember m join fetch m.category join fetch m.scheme"
                        + " where m.status = :pending order by m.updatedAt, m.id", SchemeMember.class)
                .setParameter("pending", SchemeMember.UNDERWRITING_PENDING).getResultList()) {
            String reason = em.createQuery("select c.detail from MemberChange c where c.member = :m"
                            + " and c.changeType in ('JOIN', 'SALARY', 'TRANSFER_IN') order by c.id desc", String.class)
                    .setParameter("m", m).setMaxResults(1).getResultStream().findFirst().orElse(null);
            BigDecimal[] scheme = exposure.getOrDefault(m.getScheme().getId(), new BigDecimal[] {BigDecimal.ZERO,
                    BigDecimal.ZERO});
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("memberId", m.getId());
            v.put("schemeRef", m.getScheme().getRef());
            v.put("schemeName", m.getScheme().getName());
            v.put("schemeStatus", m.getScheme().getStatus());
            v.put("memberKey", m.getMemberKey());
            v.put("name", m.fullName());
            v.put("category", m.getCategory().getCode());
            v.put("requestedCover", m.getRequestedCover());
            v.put("freeCoverLimit", m.getCategory().getFreeCoverLimit());
            v.put("acceptedCover", m.getAcceptedCover());
            v.put("pendingCover", m.getPendingCover());
            v.put("activelyAtWork", m.getActivelyAtWork());
            v.put("referredBecause", reason);
            v.put("waitingSince", m.getUpdatedAt());
            v.put("schemeCoverInForce", scheme[0]);
            v.put("schemeCoverPending", scheme[1]);
            v.put("version", m.getRowVersion());
            // NS-GL03-003/004: evidence still outstanding and any decision waiting for authority or the member.
            v.put("evidenceOutstanding", em.createQuery("select count(e) from GroupMemberEvidence e where e.member = :m"
                    + " and e.status = 'REQUESTED'", Long.class).setParameter("m", m).getSingleResult());
            v.put("decisionStatus", em.createQuery("select d.status from GroupMemberDecision d where d.member = :m"
                    + " and d.status in ('AWAITING_AUTHORITY', 'OFFERED') order by d.id", String.class)
                    .setParameter("m", m).setMaxResults(1).getResultStream().findFirst().orElse(null));
            out.add(v);
        }
        return out;
    }

    // ── member underwriting (GL03) ─────────────────────────────────────────────────────────────────

    /** Decides the cover pending underwriting, per benefit, within authority and offer rules: see MemberUnderwriting. */
    @Transactional
    public GroupViews.MemberView underwrite(Long memberId, JsonNode body) {
        return underwriting.decide(memberId, body);
    }

}

package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
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

    public GroupSchemeService(EntityManager em, PlatformSecurityContext security, GroupRecords records) {
        this.em = em;
        this.security = security;
        this.records = records;
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
            premium.merge(schemeId, m.getCategory().monthlyPremium(m.getAcceptedCover()), BigDecimal::add);
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
        return detail(scheme);
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
                premium = premium.add(m.getCategory().monthlyPremium(m.getAcceptedCover()));
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
        return GroupRecords.categoryView(category, 0);
    }

    /** Amends a category prospectively: existing members keep the cover recorded on their episodes. */
    @Transactional
    public GroupViews.CategoryView updateCategory(String schemeRef, String code, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        GroupScheme scheme = records.lockedScheme(schemeRef);
        BenefitCategory category = findCategory(scheme, code.toUpperCase(Locale.ROOT));
        if (category == null) {
            throw new NotFoundException("Category " + code + " not found on scheme " + schemeRef);
        }
        define(category, body, user.getUsername());
        Long active = em.createQuery("select count(m) from SchemeMember m where m.category = :category"
                        + " and m.status in :statuses", Long.class)
                .setParameter("category", category)
                .setParameter("statuses", SchemeMember.ON_COVER)
                .getSingleResult();
        // The rate applies to future invoices, so the dashboard premium follows it.
        records.refreshTotals(scheme);
        return GroupRecords.categoryView(category, active.intValue());
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
    }

    // ── member underwriting (GL03-lite) ────────────────────────────────────────────────────────────

    /**
     * Decides the cover pending above the free-cover limit. ACCEPT authorises up to the requested cover (all of it
     * by default); DECLINE needs a reason and keeps the free-cover portion in force.
     */
    @Transactional
    public GroupViews.MemberView underwrite(Long memberId, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UNDERWRITE_MEMBER");
        SchemeMember member = em.find(SchemeMember.class, memberId);
        if (member == null) {
            throw new NotFoundException("Member " + memberId + " not found");
        }
        records.lockedScheme(member.getScheme().getRef());
        em.refresh(member);
        if (!SchemeMember.UNDERWRITING_PENDING.equals(member.getStatus())) {
            throw new IllegalArgumentException("Member " + member.getMemberKey() + " has no cover pending underwriting");
        }
        String decision = GroupRecords.requiredText(body, "decision", "Decision", 20).toUpperCase(Locale.ROOT);
        String reason = GroupRecords.text(body, "reason");
        if (reason != null && reason.length() > 500) {
            throw new IllegalArgumentException("Reason must be at most 500 characters");
        }
        BigDecimal coverBefore = member.getAcceptedCover();
        if ("ACCEPT".equals(decision)) {
            BigDecimal amount = GroupRecords.decimal(body, "acceptedAmount", "Accepted amount");
            amount = amount == null ? member.getRequestedCover() : GroupRecords.money(amount, "Accepted amount", false);
            if (amount.compareTo(member.getRequestedCover()) > 0) {
                throw new IllegalArgumentException("Accepted amount cannot exceed the requested cover of "
                        + member.getRequestedCover().toPlainString());
            }
            if (amount.compareTo(member.getAcceptedCover()) < 0) {
                throw new IllegalArgumentException("Accepted amount cannot be below the free cover already in force ("
                        + member.getAcceptedCover().toPlainString() + ")");
            }
            member.accept(amount, reason, user.getUsername());
        } else if ("DECLINE".equals(decision)) {
            if (reason == null) {
                throw new IllegalArgumentException("Please enter a reason for declining the pending cover");
            }
            member.decline(reason, user.getUsername());
        } else {
            throw new IllegalArgumentException("Decision must be ACCEPT or DECLINE");
        }
        em.persist(new MemberChange(member, "UNDERWRITING", LocalDate.now(), member.getSalary(), coverBefore, null,
                decision + (reason == null ? "" : ": " + reason), user.getUsername()));
        records.refreshTotals(member.getScheme());
        return GroupRecords.memberView(member);
    }

}

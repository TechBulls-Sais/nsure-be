package com.nsure.rating;

import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.portfolio.CoverTier;
import com.nsure.reference.RelationshipType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate tables in the demo server's format (docs/spec/03 section 3-6): a table per product plan, immutable
 * versions, and a grid of life type x inclusive age band. Rows are per month; frequency is applied later (R-17).
 */
@Service
public class RateTableService {

    private static final int MAX_ERRORS = 12;

    private final EntityManager em;

    public RateTableService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public List<RateTableViews.Summary> list() {
        List<RateTableViews.Summary> rows = new ArrayList<>();
        for (RateTable table : tables()) {
            Optional<RateTableVersion> current = currentVersion(table);
            List<RateTableItem> items = current.map(this::items).orElse(List.of());
            rows.add(new RateTableViews.Summary(
                    table.getRef(), table.getTitle(), table.getProduct().getName(),
                    blank(table.getProduct().getProductType()), table.getTier().getName(),
                    table.getTier().getCoverAmount(), blank(table.getProduct().getCoverUnit()), table.isActive(),
                    current.map(v -> "v" + v.getVersionNo()).orElse(""),
                    current.map(v -> v.getValidFrom().toString()).orElse(""),
                    items.size(),
                    items.stream().map(i -> i.getLifeType().getRelationshipName()).distinct().toList(),
                    fromPrice(items)));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public RateTableViews.Detail detail(String ref, Integer versionNo) {
        RateTable table = table(ref);
        List<RateTableVersion> versions = versions(table);
        RateTableVersion shown = versionNo == null
                ? currentVersion(table).orElse(null)
                : versions.stream().filter(v -> v.getVersionNo() == versionNo).findFirst()
                        .orElseThrow(() -> new NotFoundException("Version v" + versionNo + " of " + ref + " not found"));
        return detail(table, versions, shown);
    }

    /**
     * Saves the editor's grid. Only a genuine price or band change cuts a new version (spec 03 section 6.1);
     * the current version is closed, never edited.
     */
    @Transactional
    public RateTableViews.Detail saveGrid(String ref, RateTableViews.GridSave body) {
        RateTable table = table(ref);
        RateTableVersion current = currentVersion(table).orElse(null);
        if (body.baseVersion() != null && current != null && body.baseVersion() != current.getVersionNo()) {
            throw new ConflictException(table.getTitle() + " changed to v" + current.getVersionNo()
                    + " while you were editing v" + body.baseVersion() + ". Reload to see the latest rates.");
        }
        List<LifeAssuredRule> rules = rules(table.getTier());
        List<Row> rows = validate(table, rules, body.rows() == null ? List.of() : body.rows());
        List<RateTableItem> before = current == null ? List.of() : items(current);
        if (current != null && signature(before).equals(signature(rows))) {
            return detail(table, versions(table), current);
        }
        LocalDate today = LocalDate.now();
        int next = current == null ? 1 : versions(table).stream().mapToInt(RateTableVersion::getVersionNo).max().orElse(0) + 1;
        if (current != null) {
            current.close(today);
        }
        RateTableVersion version = new RateTableVersion(table, next, today, username(),
                changeSummary(current, before, next, rows));
        em.persist(version);
        Map<String, Integer> order = new HashMap<>();
        for (LifeAssuredRule rule : rules) {
            order.put(rule.getRelationship().getRelationshipName(), rule.getDisplayOrder());
        }
        rows.sort(Comparator.comparing((Row r) -> order.getOrDefault(r.lifeType().getRelationshipName(), 99))
                .thenComparingInt(Row::ageFrom));
        int position = 0;
        for (Row row : rows) {
            em.persist(new RateTableItem(version, row.lifeType(), row.ageFrom(), row.ageTo(),
                    row.cover(), row.premium(), ++position));
        }
        em.flush();
        return detail(table, versions(table), version);
    }

    /**
     * Prices every covered life on a rate-table-priced plan (spec 03 sections 4.2-4.3) from the current version,
     * after checking the lives against the plan's life assured rules (R-7): relationship allowed, age in range,
     * at most max_count of each. Empty when the plan has no active rate table, so the caller falls back to the
     * tier's flat premium (R-13). Every problem is reported at once, like a rejected rate grid.
     */
    @Transactional(readOnly = true)
    public Optional<PolicyPrice> pricePolicy(CoverTier tier, List<LifeToPrice> lives, LocalDate onDate) {
        Optional<RateTableVersion> version = activeTable(tier).flatMap(this::currentVersion);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        String plan = tier.getName();
        LocalDate today = onDate == null ? LocalDate.now() : onDate;
        Map<String, LifeAssuredRule> rules = new LinkedHashMap<>();
        rules(tier).forEach(rule -> rules.put(rule.getRelationship().getRelationshipName(), rule));
        List<RateTableItem> rows = items(version.get());
        List<String> errors = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<PricedLife> lines = new ArrayList<>();
        for (LifeToPrice life : lives) {
            String relationship = life.relationship() == null ? "" : life.relationship().trim();
            String who = life.fullName() == null || life.fullName().isBlank()
                    ? relationship : life.fullName().trim() + " (" + relationship + ")";
            LifeAssuredRule rule = rules.get(relationship);
            if (rule == null) {
                errors.add("'" + relationship + "' is not covered on the " + plan + " plan. Allowed: "
                        + String.join(", ", rules.keySet()));
                continue;
            }
            counts.merge(relationship, 1, Integer::sum);
            if (life.fullName() == null || life.fullName().isBlank()) {
                errors.add("A name is required for each " + relationship + " life.");
            }
            if (life.dateOfBirth() == null) {
                errors.add("Date of birth is required to calculate the premium for " + who + ".");
                continue;
            }
            if (life.dateOfBirth().isAfter(today)) {
                errors.add(who + ": date of birth cannot be in the future.");
                continue;
            }
            int age = Period.between(life.dateOfBirth(), today).getYears();
            if ((rule.getMinAge() != null && age < rule.getMinAge()) || (rule.getMaxAge() != null && age > rule.getMaxAge())) {
                errors.add(who + " is " + age + "; the " + plan + " plan covers " + relationship + " lives aged "
                        + ageRange(rule) + ".");
                continue;
            }
            // R-14 / R-15: inclusive bands, matched on the relationship itself rather than its name.
            Long typeId = rule.getRelationship().getId();
            Optional<RateTableItem> row = rows.stream()
                    .filter(item -> item.getLifeType().getId().equals(typeId))
                    .filter(item -> item.getAgeFrom() <= age && age <= item.getAgeTo())
                    .findFirst();
            if (row.isEmpty()) {
                errors.add("No premium available for " + relationship + " aged " + age + " on the " + plan + " plan.");
                continue;
            }
            lines.add(new PricedLife(rule.getRelationship(), life.fullName().trim(), life.dateOfBirth(), age,
                    row.get().getCoverAmount(), row.get().getMonthlyPremium()));
        }
        counts.forEach((relationship, count) -> {
            int max = rules.get(relationship).getMaxCount();
            if (count > max) {
                errors.add("The " + plan + " plan covers at most " + max + " " + relationship
                        + (max == 1 ? " life" : " lives") + "; " + count + " were added.");
            }
        });
        if (!errors.isEmpty()) {
            List<String> shown = errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors;
            String more = errors.size() > MAX_ERRORS ? "\n…and " + (errors.size() - MAX_ERRORS) + " more." : "";
            throw new IllegalArgumentException(String.join("\n", shown) + more);
        }
        BigDecimal total = lines.stream().map(PricedLife::monthlyPremium)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return Optional.of(new PolicyPrice(version.get().getLabel(), lines, total));
    }

    /** Each tier's life assured rules, keyed by tier id: who its "add a life" screen may offer (R-7). */
    @Transactional(readOnly = true)
    public Map<Long, List<RateTableViews.LifeRule>> lifeRules() {
        Map<Long, List<RateTableViews.LifeRule>> byTier = new HashMap<>();
        for (LifeAssuredRule rule : em.createQuery(
                "select r from LifeAssuredRule r join fetch r.relationship order by r.displayOrder, r.id",
                LifeAssuredRule.class).getResultList()) {
            byTier.computeIfAbsent(rule.getTier().getId(), id -> new ArrayList<>()).add(new RateTableViews.LifeRule(
                    rule.getRelationship().getRelationshipName(), rule.getMinAge(), rule.getMaxAge(), rule.getMaxCount()));
        }
        return byTier;
    }

    /** A life to price: its relationship to the main insured (a Relationship Type name), name and birth date. */
    public record LifeToPrice(String relationship, String fullName, LocalDate dateOfBirth) {
    }

    /** One rated life (spec 03 section 4.3 lines[]); maps 1:1 onto a Policy Life Cover row (R-16). */
    public record PricedLife(RelationshipType relationship, String fullName, LocalDate dateOfBirth, int age,
                             BigDecimal coverAmount, BigDecimal monthlyPremium) {
    }

    /** A rate-table price: the version stamped on the record, a line per life and their monthly total. */
    public record PolicyPrice(String rateTableVersion, List<PricedLife> lines, BigDecimal monthlyTotal) {
    }

    private static String ageRange(LifeAssuredRule rule) {
        if (rule.getMinAge() != null && rule.getMaxAge() != null) {
            return range(rule.getMinAge(), rule.getMaxAge());
        }
        return rule.getMinAge() != null ? rule.getMinAge() + " and over" : "up to " + rule.getMaxAge();
    }

    /**
     * "From" prices for plan cards (spec 03 section 4.4): per rate-table-priced tier, the cheapest Main Insured
     * rate, or the cheapest of all rows when the table has no Main Insured rows. Keyed by tier id.
     */
    @Transactional(readOnly = true)
    public Map<Long, Priced> pricedTiers() {
        Map<Long, Priced> priced = new HashMap<>();
        for (RateTable table : tables()) {
            if (!table.isActive()) {
                continue;
            }
            currentVersion(table).ifPresent(version -> {
                BigDecimal from = fromPrice(items(version));
                if (from != null) {
                    priced.put(table.getTier().getId(), new Priced(table.getRef(), version.getLabel(), from));
                }
            });
        }
        return priced;
    }

    /** A tier priced from a rate table: its table, current version and "from" premium. */
    public record Priced(String rateTable, String version, BigDecimal fromPremium) {
    }

    private RateTableViews.Detail detail(RateTable table, List<RateTableVersion> versions, RateTableVersion shown) {
        List<LifeAssuredRule> rules = rules(table.getTier());
        RateTableVersion current = versions.stream().filter(v -> v.getValidTo() == null)
                .max(Comparator.comparingInt(RateTableVersion::getVersionNo)).orElse(null);
        List<RateTableViews.VersionSummary> history = new ArrayList<>();
        for (RateTableVersion version : versions) {
            history.add(new RateTableViews.VersionSummary(
                    version.getVersionNo(), version.getLabel(), version.getValidFrom().toString(),
                    version.getValidTo() == null ? "" : version.getValidTo().toString(),
                    version.getSource(), blank(version.getCreatedBy()), blank(version.getChangeSummary()),
                    items(version).size(), version == current));
        }
        return new RateTableViews.Detail(
                table.getRef(), table.getTitle(), table.getProduct().getName(),
                blank(table.getProduct().getProductType()), table.getTier().getName(),
                table.getTier().getCoverAmount(), blank(table.getProduct().getCoverUnit()),
                table.getTier().getPremium(), table.isActive(),
                rules.stream().map(rule -> new RateTableViews.LifeRule(
                        rule.getRelationship().getRelationshipName(), rule.getMinAge(), rule.getMaxAge(),
                        rule.getMaxCount())).toList(),
                shown == null ? null : grid(shown, rules, shown == current),
                history);
    }

    private RateTableViews.Grid grid(RateTableVersion version, List<LifeAssuredRule> rules, boolean current) {
        List<RateTableItem> items = items(version);
        Set<String> lifeTypes = new LinkedHashSet<>();
        rules.forEach(rule -> lifeTypes.add(rule.getRelationship().getRelationshipName()));
        items.forEach(item -> lifeTypes.add(item.getLifeType().getRelationshipName()));
        Map<String, BigDecimal> cover = new LinkedHashMap<>();
        TreeMap<String, Map<String, BigDecimal>> bands = new TreeMap<>();
        Map<String, int[]> bandAges = new HashMap<>();
        for (RateTableItem item : items) {
            String lifeType = item.getLifeType().getRelationshipName();
            cover.putIfAbsent(lifeType, item.getCoverAmount());
            String key = String.format("%04d-%04d", item.getAgeFrom(), item.getAgeTo());
            bandAges.put(key, new int[] {item.getAgeFrom(), item.getAgeTo()});
            bands.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(lifeType, item.getMonthlyPremium());
        }
        List<RateTableViews.Band> rows = bands.entrySet().stream()
                .map(e -> new RateTableViews.Band(bandAges.get(e.getKey())[0], bandAges.get(e.getKey())[1], e.getValue()))
                .toList();
        return new RateTableViews.Grid(version.getVersionNo(), version.getLabel(), current,
                List.copyOf(lifeTypes), cover, rows, gaps(items, rules));
    }

    /** Ages a life type's rule allows that no band prices; they would fail at quote time (spec 03 section 5 note). */
    private List<String> gaps(List<RateTableItem> items, List<LifeAssuredRule> rules) {
        List<String> issues = new ArrayList<>();
        for (LifeAssuredRule rule : rules) {
            String lifeType = rule.getRelationship().getRelationshipName();
            List<int[]> bands = items.stream()
                    .filter(item -> item.getLifeType().getRelationshipName().equals(lifeType))
                    .map(item -> new int[] {item.getAgeFrom(), item.getAgeTo()})
                    .sorted(Comparator.comparingInt(b -> b[0]))
                    .toList();
            if (bands.isEmpty()) {
                issues.add(lifeType + ": no rates, so this life cannot be priced on the plan.");
                continue;
            }
            int from = rule.getMinAge() == null ? bands.get(0)[0] : rule.getMinAge();
            int to = rule.getMaxAge() == null ? bands.get(bands.size() - 1)[1] : rule.getMaxAge();
            int next = from;
            for (int[] band : bands) {
                if (band[0] > next && next <= to) {
                    issues.add(lifeType + ": no band covers ages " + range(next, Math.min(band[0] - 1, to)) + ".");
                }
                next = Math.max(next, band[1] + 1);
            }
            if (next <= to) {
                issues.add(lifeType + ": no band covers ages " + range(next, to) + ".");
            }
        }
        return issues;
    }

    /** Rules V3-V8 (spec 03 section 5) plus a positive cover amount. Collects every error before refusing. */
    private List<Row> validate(RateTable table, List<LifeAssuredRule> rules, List<RateTableViews.RowInput> input) {
        String plan = table.getTier().getName();
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("Plan '" + plan
                    + "' has no Life Assured Rules. Add them to the plan on the Products page first.");
        }
        if (input.isEmpty()) {
            throw new IllegalArgumentException("A rate table needs at least one rate row.");
        }
        Map<String, RelationshipType> allowed = new LinkedHashMap<>();
        rules.forEach(rule -> allowed.put(rule.getRelationship().getRelationshipName(), rule.getRelationship()));
        List<String> errors = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            RateTableViews.RowInput in = input.get(i);
            String label = "Row " + (i + 1) + ": ";
            String lifeType = in.lifeType() == null ? "" : in.lifeType().trim();
            RelationshipType type = allowed.get(lifeType);
            if (type == null) {
                errors.add(label + "'" + lifeType + "' is not in the Life Assured Rules of plan '" + plan
                        + "'. Allowed: " + String.join(", ", allowed.keySet()));
            }
            if (in.ageFrom() == null || in.ageTo() == null || in.ageFrom() > in.ageTo()) {
                errors.add(label + "'Age From' must be less than or equal to 'Age To'");
            } else if (in.ageFrom() < 0) {
                errors.add(label + "age cannot be negative");
            }
            if (in.monthlyPremium() == null || in.monthlyPremium().signum() <= 0) {
                errors.add(label + "premium must be greater than 0");
            }
            if (in.coverAmount() == null || in.coverAmount().signum() <= 0) {
                errors.add(label + "cover amount must be greater than 0");
            }
            if (type != null && in.ageFrom() != null && in.ageTo() != null && in.monthlyPremium() != null
                    && in.coverAmount() != null) {
                rows.add(new Row(type, in.ageFrom(), in.ageTo(), money(in.coverAmount()), money(in.monthlyPremium())));
            }
        }
        Map<String, List<Row>> byType = rows.stream()
                .collect(Collectors.groupingBy(r -> r.lifeType().getRelationshipName(), LinkedHashMap::new, Collectors.toList()));
        byType.forEach((lifeType, list) -> {
            List<Row> sorted = list.stream().sorted(Comparator.comparingInt(Row::ageFrom)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                Row a = sorted.get(i - 1);
                Row b = sorted.get(i);
                if (b.ageFrom() <= a.ageTo()) {
                    errors.add(lifeType + ": age band " + range(b.ageFrom(), b.ageTo()) + " overlaps with "
                            + range(a.ageFrom(), a.ageTo()));
                }
            }
        });
        if (!errors.isEmpty()) {
            List<String> shown = errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors;
            String more = errors.size() > MAX_ERRORS ? "\n…and " + (errors.size() - MAX_ERRORS) + " more." : "";
            throw new IllegalArgumentException(String.join("\n", shown) + more);
        }
        return rows;
    }

    /** The human-readable rate diff (R-20), e.g. "Main Insured 40–44 premium: P12.95 → P13.40". */
    private String changeSummary(RateTableVersion current, List<RateTableItem> before, int next, List<Row> after) {
        List<String> lines = new ArrayList<>();
        lines.add(current == null ? "Version v" + next : "Version v" + current.getVersionNo() + " → v" + next);
        Map<String, Row> old = new LinkedHashMap<>();
        for (RateTableItem item : before) {
            old.put(key(item.getLifeType().getRelationshipName(), item.getAgeFrom(), item.getAgeTo()),
                    new Row(item.getLifeType(), item.getAgeFrom(), item.getAgeTo(),
                            money(item.getCoverAmount()), money(item.getMonthlyPremium())));
        }
        Map<String, Row> fresh = new LinkedHashMap<>();
        after.forEach(row -> fresh.put(key(row.lifeType().getRelationshipName(), row.ageFrom(), row.ageTo()), row));
        fresh.forEach((key, row) -> {
            Row was = old.get(key);
            String band = row.lifeType().getRelationshipName() + " " + range(row.ageFrom(), row.ageTo());
            if (was == null) {
                lines.add("Added " + band + ": cover " + pula(row.cover()) + ", " + pula(row.premium()));
                return;
            }
            if (was.premium().compareTo(row.premium()) != 0) {
                lines.add(band + " premium: " + pula(was.premium()) + " → " + pula(row.premium()));
            }
            if (was.cover().compareTo(row.cover()) != 0) {
                lines.add(band + " cover: " + pula(was.cover()) + " → " + pula(row.cover()));
            }
        });
        old.forEach((key, row) -> {
            if (!fresh.containsKey(key)) {
                lines.add("Removed " + row.lifeType().getRelationshipName() + " " + range(row.ageFrom(), row.ageTo()));
            }
        });
        String summary = String.join("\n", lines);
        return summary.length() > 4000 ? summary.substring(0, 3990) + "\n…" : summary;
    }

    private static Set<String> signature(List<?> rows) {
        Set<String> signature = new java.util.TreeSet<>();
        for (Object row : rows) {
            if (row instanceof RateTableItem item) {
                signature.add(key(item.getLifeType().getRelationshipName(), item.getAgeFrom(), item.getAgeTo())
                        + "|" + money(item.getCoverAmount()) + "|" + money(item.getMonthlyPremium()));
            } else if (row instanceof Row r) {
                signature.add(key(r.lifeType().getRelationshipName(), r.ageFrom(), r.ageTo())
                        + "|" + r.cover() + "|" + r.premium());
            }
        }
        return signature;
    }

    private static BigDecimal fromPrice(List<RateTableItem> items) {
        List<RateTableItem> main = items.stream().filter(item -> item.getLifeType().isSelfRelationship()).toList();
        return (main.isEmpty() ? items : main).stream()
                .map(RateTableItem::getMonthlyPremium)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private List<RateTable> tables() {
        return em.createQuery(
                "select t from RateTable t join fetch t.product join fetch t.tier order by t.ref", RateTable.class)
                .getResultList();
    }

    private RateTable table(String ref) {
        return em.createQuery(
                        "select t from RateTable t join fetch t.product join fetch t.tier where t.ref = :ref", RateTable.class)
                .setParameter("ref", ref)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Rate table " + ref + " not found"));
    }

    private Optional<RateTable> activeTable(CoverTier tier) {
        return em.createQuery(
                        "select t from RateTable t where t.tier.id = :tier and t.active = true order by t.id", RateTable.class)
                .setParameter("tier", tier.getId())
                .getResultStream()
                .findFirst();
    }

    private List<RateTableVersion> versions(RateTable table) {
        return em.createQuery(
                        "select v from RateTableVersion v where v.rateTable = :table order by v.versionNo desc",
                        RateTableVersion.class)
                .setParameter("table", table)
                .getResultList();
    }

    private Optional<RateTableVersion> currentVersion(RateTable table) {
        return versions(table).stream().filter(v -> v.getValidTo() == null).findFirst();
    }

    private List<RateTableItem> items(RateTableVersion version) {
        return em.createQuery(
                        "select i from RateTableItem i join fetch i.lifeType where i.version = :version"
                                + " order by i.displayOrder, i.id", RateTableItem.class)
                .setParameter("version", version)
                .getResultList();
    }

    private List<LifeAssuredRule> rules(CoverTier tier) {
        return em.createQuery(
                        "select r from LifeAssuredRule r join fetch r.relationship where r.tier.id = :tier"
                                + " order by r.displayOrder, r.id", LifeAssuredRule.class)
                .setParameter("tier", tier.getId())
                .getResultList();
    }

    private static String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    private static String key(String lifeType, int from, int to) {
        return lifeType + "|" + from + "|" + to;
    }

    private static String range(int from, int to) {
        return from + "–" + to;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String pula(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.ENGLISH);
        format.setMinimumFractionDigits(value.stripTrailingZeros().scale() > 0 ? 2 : 0);
        format.setMaximumFractionDigits(2);
        return "P" + format.format(value);
    }

    private static String blank(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    private record Row(RelationshipType lifeType, int ageFrom, int ageTo, BigDecimal cover, BigDecimal premium) {
    }
}

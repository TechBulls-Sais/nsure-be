package com.nsure.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.rating.LifeAssuredRule;
import com.nsure.rating.RateTable;
import com.nsure.rating.RateTableService;
import com.nsure.rating.RateTableVersion;
import com.nsure.reference.RelationshipType;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SH02 release of a product configuration: Draft → In review → Approved → Scheduled/Active → Withdrawn.
 *
 * <ul>
 *   <li>A release checklist (AT-SH02-01) checks the typed rules and their combinations (NS-SH02-005), the refund and
 *       termination rules, the age basis, the rates the plan is priced from, the wording and approved issue template,
 *       the accounting posting rules, the commission rule for intermediated channels, the reinsurance treaty (for
 *       information) and the approved golden cases (NS-SH03-006).</li>
 *   <li>Approval by someone other than the maker freezes a release manifest (NS-SH02-002) — the component versions,
 *       the content hash and the golden baselines — with its checksum; activation re-checks it.</li>
 *   <li>Activation publishes the configuration to the catalogue (NS-SH02-001): the plan, its entry ages and the
 *       contract terms version, so quoting, applications and issue sell exactly what was configured. Withdrawal takes
 *       the plan off sale; issued policies keep their pinned terms (NS-SH02-004, NS-SH02-006).</li>
 *   <li>A configuration exports as a signed package with checksum and dependencies; an import is refused before
 *       anything is created when a dependency is missing in this installation (AT-SH02-03).</li>
 * </ul>
 */
@Service
public class ProductRelease {

    public static final String DRAFT = "Draft";
    public static final String IN_REVIEW = ProductConfigHistory.IN_REVIEW;
    public static final String APPROVED = "Approved";
    public static final String SCHEDULED = "Scheduled";
    public static final String ACTIVE = "Active";
    public static final String WITHDRAWN = "Withdrawn";
    /** The statuses a configuration may have (NS-SH02-003); any other is refused. */
    public static final Set<String> STATUSES = Set.of(DRAFT, IN_REVIEW, APPROVED, SCHEDULED, ACTIVE, WITHDRAWN);

    static final String PACKAGE_FORMAT = "nsure-product-package/1";
    /** Channels sold through intermediaries, which need a commission rule in the manifest. */
    private static final Set<String> INTERMEDIATED = Set.of("Broker portal");

    public record CheckItem(String key, String label, boolean ok, boolean blocking, String detail) {
    }

    public record GoldenCaseView(Long id, String label, LocalDate dateOfBirth, LocalDate pricedOn,
            BigDecimal expectedPremium, String status, int baseline, Long supersedes, String reason, String proposedBy,
            LocalDateTime proposedAt, String approvedBy, LocalDateTime approvedAt, String result, String actual) {
    }

    public record ChecklistView(String configRef, int version, String status, boolean passed, List<CheckItem> items,
            JsonNode manifest, String manifestChecksum, boolean manifestFrozen, boolean manifestMatches,
            List<GoldenCaseView> goldenCases, List<ProductRules.RuleDef> ruleCatalog, List<String> actions) {
    }

    private final EntityManager em;
    private final ObjectMapper mapper;
    private final PlatformSecurityContext security;
    private final ProductConfigHistory history;
    private final Outbox outbox;
    private final EvidenceChain evidence;
    private final RateTableService rates;
    private final JdbcTemplate jdbc;
    private final ReferenceNumbers references;
    private final String signingKey;
    private final List<String> requiredPostings;

    public ProductRelease(EntityManager em, ObjectMapper mapper, PlatformSecurityContext security,
            ProductConfigHistory history, Outbox outbox, EvidenceChain evidence, RateTableService rates,
            JdbcTemplate jdbc, ReferenceNumbers references,
            @Value("${nsure.product-packages.signing-key:DEMO-ONLY-product-package-key}") String signingKey,
            @Value("${nsure.product-release.required-postings:PremiumReceived,ClaimPaid,PremiumRefunded}")
            List<String> requiredPostings) {
        this.em = em;
        this.mapper = mapper;
        this.security = security;
        this.history = history;
        this.outbox = outbox;
        this.evidence = evidence;
        this.rates = rates;
        this.jdbc = jdbc;
        this.references = references;
        this.signingKey = signingKey;
        this.requiredPostings = requiredPostings;
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public ChecklistView checklist(String ref) {
        user("READ_PRODUCT");
        ProductConfiguration row = config(ref);
        return checklistView(row, evaluate(row, true));
    }

    public List<ProductRules.RuleDef> ruleCatalog() {
        user("READ_PRODUCT");
        return ProductRules.CATALOG;
    }

    /** The configuration as the API shows it, with its release data. */
    public PortfolioViews.ConfigView view(ProductConfiguration row) {
        return new PortfolioViews.ConfigView(
                row.getRef(), row.getName(), row.getCode(), row.getDomain(), row.getCurrency(), row.getDescription(),
                row.getEffectiveOn(), row.getMinAge(), row.getMaxAge(), row.getWaitingMonths(), row.getCoverAmount(),
                row.getPremiumAmount(), row.getFrequency(), row.getGraceDays(), row.getUnderwriting(),
                split(row.getBenefits()), split(row.getChannels()), row.getWording(), row.isApprovals(),
                row.getStatus(), row.getVersionNo(), history.madeBy(row), row.getCatalogueProduct(), row.getPlanName(),
                ProductRules.parse(mapper, row.getRules()), row.getRateTableRef(), row.getApprovedBy(),
                row.getApprovedAt(), row.getReleasedBy(), row.getReleasedAt(), row.getWithdrawnBy(),
                row.getWithdrawnAt(), row.getReleaseNote(), row.getManifestChecksum(), row.getPublishedTierId() != null,
                targetProduct(row), planName(row), row.getImportedFrom());
    }

    // ── saving (called by PortfolioService.saveConfig) ───────────────────────────────────────────

    /** Applies the release fields a save sends (only those present), validating the rules' schema. */
    void applyReleaseFields(ProductConfiguration row, JsonNode body) {
        if (body.has("rules")) {
            ObjectNode rules = ProductRules.clean(mapper, body.get("rules"));
            row.setRules(rules.isEmpty() ? null : write(rules));
        }
        if (body.has("catalogueProduct")) {
            row.setCatalogueProduct(blankToNull(body.get("catalogueProduct").asText(null), 100));
        }
        if (body.has("plan")) {
            row.setPlanName(blankToNull(body.get("plan").asText(null), 60));
        }
        if (body.has("rateTableRef")) {
            row.setRateTableRef(blankToNull(body.get("rateTableRef").asText(null), 20));
        }
    }

    /** A status a save may set: the release statuses only, and Approved/Scheduled only through their actions. */
    static String requireSaveStatus(String requested) {
        String match = STATUSES.stream().filter(s -> s.equalsIgnoreCase(requested)).findFirst().orElse(null);
        if (match == null) {
            if ("Demo active".equalsIgnoreCase(requested)) {
                return ACTIVE;
            }
            throw new IllegalArgumentException("Status must be one of Draft, In review, Active or Withdrawn ("
                    + requested + " is not a product status).");
        }
        if (APPROVED.equals(match) || SCHEDULED.equals(match)) {
            throw new IllegalArgumentException(match + " is set by the approve and activate actions, not by saving.");
        }
        return match;
    }

    /**
     * Content changed on an approved or released configuration: its approval and frozen manifest no longer describe it.
     */
    void contentChanged(ProductConfiguration row) {
        row.setApprovedBy(null);
        row.setApprovedAt(null);
        row.setManifest(null);
        row.setManifestChecksum(null);
    }

    /**
     * Activation through a save (In review → Active by a second person): the checker's activation is the approval.
     * The release checklist must pass; the plan is then published, or scheduled when its effective date is ahead.
     */
    void activateFromSave(ProductConfiguration row, String actor) {
        Evaluation evaluation = evaluate(row, true);
        if (row.getManifestChecksum() != null
                && !row.getManifestChecksum().equals(sha256(write(evaluation.manifest())))) {
            throw new IllegalArgumentException("Something in the release changed since " + row.getApprovedBy()
                    + " approved it (" + changedComponents(row, evaluation.manifest()) + "). Reject it and approve the"
                    + " release as it stands now.");
        }
        requirePassed(row, evaluation, false);
        if (row.getApprovedBy() == null) {
            row.setApprovedBy(actor);
            row.setApprovedAt(LocalDateTime.now());
        }
        row.setManifest(write(evaluation.manifest()));
        row.setManifestChecksum(sha256(write(evaluation.manifest())));
        release(row, actor);
    }

    /** Leaving Active or Scheduled through a save is a withdrawal: the plan comes off sale. */
    void withdrawFromSave(ProductConfiguration row, String actor) {
        takeOffSale(row, actor, "Withdrawn by saving it as " + row.getStatus());
    }

    // ── lifecycle actions ────────────────────────────────────────────────────────────────────────

    @Transactional
    public PortfolioViews.ConfigView submit(String ref) {
        AppUser actor = user("UPDATE_PRODUCT");
        ProductConfiguration row = config(ref);
        requireStatus(row, Set.of(DRAFT, WITHDRAWN), "submitted for review");
        List<String> conflicts = ProductRules.conflicts(ProductRules.parse(mapper, row.getRules()), row);
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException("Fix these before review:\n" + String.join("\n", conflicts));
        }
        return transition(row, IN_REVIEW, actor.getUsername(), "Submitted for review", null);
    }

    /**
     * The checker approves: someone other than the maker, with product.activate, when the checklist passes. The
     * release manifest is frozen with its checksum (NS-SH02-002) and ProductVersionApproved raised.
     */
    @Transactional
    public PortfolioViews.ConfigView approve(String ref, String note) {
        AppUser actor = user("ACTIVATE_PRODUCT");
        ProductConfiguration row = config(ref);
        requireStatus(row, Set.of(IN_REVIEW), "approved");
        requireChecker(row, actor, "approve");
        Evaluation evaluation = evaluate(row, true);
        requirePassed(row, evaluation, false);
        row.setApprovedBy(actor.getUsername());
        row.setApprovedAt(LocalDateTime.now());
        String manifest = write(evaluation.manifest());
        row.setManifest(manifest);
        row.setManifestChecksum(sha256(manifest));
        PortfolioViews.ConfigView view = transition(row, APPROVED, actor.getUsername(), "Approved for release"
                + (blank(note) ? "" : ": " + note.trim()), note);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configRef", row.getRef());
        data.put("version", row.getVersionNo());
        data.put("product", targetProduct(row));
        data.put("plan", planName(row));
        data.put("manifestChecksum", row.getManifestChecksum());
        data.put("approvedBy", actor.getUsername());
        data.put("source", "ProductConfiguration");
        outbox.publish(Events.PRODUCT_VERSION_APPROVED, row.getRef(), data, actor.getUsername());
        return view;
    }

    @Transactional
    public PortfolioViews.ConfigView reject(String ref, String reason) {
        AppUser actor = user("ACTIVATE_PRODUCT");
        ProductConfiguration row = config(ref);
        requireStatus(row, Set.of(IN_REVIEW, APPROVED), "rejected");
        requireChecker(row, actor, "reject");
        String text = required(reason, "reason", 500);
        contentChanged(row);
        return transition(row, DRAFT, actor.getUsername(), "Returned to draft: " + text, text);
    }

    /**
     * Releases an approved configuration: the frozen manifest must still match (no component changed since
     * approval) and the checklist must pass. Active at once, or Scheduled until its effective date.
     */
    @Transactional
    public PortfolioViews.ConfigView activate(String ref) {
        AppUser actor = user("ACTIVATE_PRODUCT");
        ProductConfiguration row = config(ref);
        requireStatus(row, Set.of(APPROVED), "activated (approve it first)");
        requireChecker(row, actor, "activate");
        Evaluation evaluation = evaluate(row, true);
        String checksum = sha256(write(evaluation.manifest()));
        if (!checksum.equals(row.getManifestChecksum())) {
            throw new IllegalArgumentException("Something in the release changed since " + row.getApprovedBy()
                    + " approved it (" + changedComponents(row, evaluation.manifest()) + "). Reject it and approve the"
                    + " release as it stands now.");
        }
        requirePassed(row, evaluation, false);
        String previous = row.getStatus();
        JsonNode before = history.content(row);
        release(row, actor.getUsername());
        row.setVersionNo(row.getVersionNo() + 1);
        history.record(row, before, actor.getUsername());
        history.publishStatusChange(row, previous, actor.getUsername());
        return view(row);
    }

    @Transactional
    public PortfolioViews.ConfigView withdraw(String ref, String reason) {
        AppUser actor = user("ACTIVATE_PRODUCT");
        ProductConfiguration row = config(ref);
        requireStatus(row, Set.of(ACTIVE, SCHEDULED), "withdrawn");
        String text = required(reason, "reason", 500);
        String previous = row.getStatus();
        JsonNode before = history.content(row);
        row.setStatus(WITHDRAWN);
        row.setReleaseNote(text);
        takeOffSale(row, actor.getUsername(), text);
        row.setVersionNo(row.getVersionNo() + 1);
        history.record(row, before, actor.getUsername());
        history.publishStatusChange(row, previous, actor.getUsername());
        return view(row);
    }

    /**
     * Rolls the content back to an earlier saved version as a new draft (NS-SH02-006). It goes through review again;
     * policies already issued keep the terms version they were sold under.
     */
    @Transactional
    public PortfolioViews.ConfigView rollback(String ref, int version) {
        AppUser actor = user("UPDATE_PRODUCT");
        ProductConfiguration row = config(ref);
        if (Set.of(ACTIVE, SCHEDULED).contains(row.getStatus())) {
            throw new IllegalArgumentException(ref + " is " + row.getStatus() + ". Withdraw it first; the plan stays on"
                    + " sale as released until a rolled-back version is approved and activated.");
        }
        ProductConfigVersion target = em.createQuery("select v from ProductConfigVersion v where v.configRef = :ref"
                        + " and v.versionNo = :n order by v.id desc", ProductConfigVersion.class)
                .setParameter("ref", ref).setParameter("n", version).setMaxResults(1).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException(ref + " v" + version + " not found"));
        JsonNode snapshot;
        try {
            snapshot = mapper.readTree(target.getSnapshot());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(ref + " v" + version + " cannot be read back.");
        }
        JsonNode before = history.content(row);
        applyContent(row, snapshot);
        contentChanged(row);
        row.setStatus(DRAFT);
        row.setReleaseNote("Rolled back to the content of v" + version);
        row.setVersionNo(row.getVersionNo() + 1);
        history.record(row, before, actor.getUsername());
        evidence.record(actor.getUsername(), "Rolled back product configuration", "ProductConfiguration", ref,
                "Content of v" + version + " restored as draft v" + row.getVersionNo());
        return view(row);
    }

    /** Scheduled releases whose effective date has come go on sale (checked hourly). */
    @Scheduled(cron = "${nsure.product-release.promote-cron:0 7 * * * *}")
    @Transactional
    public int promoteDue() {
        int promoted = 0;
        for (ProductConfiguration row : em.createQuery("select c from ProductConfiguration c where c.status = :s",
                ProductConfiguration.class).setParameter("s", SCHEDULED).getResultList()) {
            LocalDate effective = date(row.getEffectiveOn());
            if (effective != null && !effective.isAfter(LocalDate.now())) {
                CoverTier tier = row.getPublishedTierId() == null ? null : em.find(CoverTier.class, row.getPublishedTierId());
                if (tier != null) {
                    tier.putOnSale();
                }
                JsonNode before = history.content(row);
                row.setStatus(ACTIVE);
                row.setVersionNo(row.getVersionNo() + 1);
                history.record(row, before, WorkflowHistory.SYSTEM);
                history.publishStatusChange(row, SCHEDULED, WorkflowHistory.SYSTEM);
                promoted++;
            }
        }
        return promoted;
    }

    // ── golden cases (NS-SH03-006) ───────────────────────────────────────────────────────────────

    /** {label, dateOfBirth, pricedOn?, expectedPremium (null = must be refused), reason?, supersedes?}. */
    @Transactional
    public GoldenCaseView proposeGolden(String ref, JsonNode body) {
        AppUser actor = security.authenticatedUser();
        if (!actor.hasPermissionTo("UPDATE_PRODUCT")) {
            actor.validateHasPermissionTo("UPDATE_RATE_TABLE");
        }
        config(ref);
        String label = required(text(body, "label"), "label", 120);
        LocalDate dob = parseDate(text(body, "dateOfBirth"), "dateOfBirth");
        LocalDate pricedOn = text(body, "pricedOn") == null ? LocalDate.now() : parseDate(text(body, "pricedOn"), "pricedOn");
        BigDecimal expected = null;
        if (body.hasNonNull("expectedPremium") && !body.get("expectedPremium").asText().isBlank()) {
            try {
                expected = new BigDecimal(body.get("expectedPremium").asText().trim()).setScale(2, RoundingMode.UNNECESSARY);
            } catch (NumberFormatException | ArithmeticException ex) {
                throw new com.nsure.common.exception.InvalidFieldException("expectedPremium",
                        "expectedPremium must be an amount with at most two decimals.");
            }
        }
        Long supersedes = body.hasNonNull("supersedes") ? body.get("supersedes").asLong() : null;
        int baseline = 1;
        if (supersedes != null) {
            ProductGoldenCase old = golden(ref, supersedes);
            if (!ProductGoldenCase.APPROVED.equals(old.getStatus())) {
                throw new IllegalArgumentException("Only an approved case gets a revised baseline.");
            }
            baseline = old.getBaselineNo() + 1;
        }
        String reason = text(body, "reason");
        if (supersedes != null && (reason == null || reason.length() < 5)) {
            throw new IllegalArgumentException("Say why the validated result changes (baseline revision).");
        }
        ProductGoldenCase c = new ProductGoldenCase(ref, label, dob, pricedOn, expected, baseline, supersedes,
                reason == null ? null : reason.substring(0, Math.min(300, reason.length())), actor.getUsername());
        em.persist(c);
        evidence.record(actor.getUsername(), supersedes == null ? "Proposed golden case" : "Proposed golden baseline",
                "ProductConfiguration", ref, label + ": " + (expected == null ? "must be refused" : Money.display(expected)));
        return golden(c, null);
    }

    /** Actuarial approval (rate.publish) by someone other than the proposer; a revised baseline supersedes the old. */
    @Transactional
    public GoldenCaseView decideGolden(String ref, long id, boolean approve) {
        AppUser actor = user("PUBLISH_RATE_TABLE");
        ProductGoldenCase c = golden(ref, id);
        if (!ProductGoldenCase.PROPOSED.equals(c.getStatus())) {
            throw new ConflictException("Golden case " + id + " is " + c.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        if (actor.getUsername().equalsIgnoreCase(c.getProposedBy())) {
            throw new NoAuthorizationException("You proposed this golden case, so another actuary must decide it.");
        }
        if (approve) {
            c.approve(actor.getUsername());
            if (c.getSupersedesId() != null) {
                golden(ref, c.getSupersedesId()).supersede();
            }
        } else {
            c.reject(actor.getUsername());
        }
        evidence.record(actor.getUsername(), approve ? "Approved golden case" : "Rejected golden case",
                "ProductConfiguration", ref, c.getLabel() + " (baseline " + c.getBaselineNo() + ")");
        return golden(c, null);
    }

    @Transactional
    public List<GoldenCaseView> runGolden(String ref) {
        user("READ_PRODUCT");
        ProductConfiguration row = config(ref);
        return evaluate(row, true).golden();
    }

    // ── packages (NS-SH02-006, AT-SH02-03) ──────────────────────────────────────────────────────

    /** A signed package: content, rules, dependencies, approved golden cases, checksum and signature. */
    @Transactional(readOnly = true)
    public ObjectNode exportPackage(String ref) {
        AppUser actor = user("READ_PRODUCT");
        ProductConfiguration row = config(ref);
        ObjectNode body = mapper.createObjectNode();
        body.put("format", PACKAGE_FORMAT);
        ObjectNode content = history.content(row).deepCopy();
        content.put("catalogueProduct", row.getCatalogueProduct());
        content.put("plan", row.getPlanName());
        content.put("rateTableRef", row.getRateTableRef());
        content.set("rules", ProductRules.parse(mapper, row.getRules()));
        content.put("approvals", row.isApprovals());
        content.put("sourceRef", row.getRef());
        content.put("sourceVersion", row.getVersionNo());
        body.set("configuration", content);
        ArrayNode dependencies = body.putArray("dependencies");
        if (row.getRateTableRef() != null) {
            ObjectNode d = dependencies.addObject();
            d.put("type", "RATE_TABLE");
            d.put("ref", row.getRateTableRef());
            rateTable(row.getRateTableRef()).flatMap(this::published).ifPresent(v -> d.put("version", v.getLabel()));
        }
        if (row.getCatalogueProduct() != null) {
            dependencies.addObject().put("type", "PRODUCT").put("ref", row.getCatalogueProduct());
        }
        for (String event : postingsFor(row)) {
            dependencies.addObject().put("type", "POSTING_RULE").put("ref", event);
        }
        dependencies.addObject().put("type", "TEMPLATE").put("ref", "PolicyIssued");
        ArrayNode cases = body.putArray("goldenCases");
        for (ProductGoldenCase c : goldenCases(ref)) {
            if (ProductGoldenCase.APPROVED.equals(c.getStatus())) {
                ObjectNode g = cases.addObject();
                g.put("label", c.getLabel());
                g.put("dateOfBirth", c.getDateOfBirth().toString());
                g.put("pricedOn", c.getPricedOn().toString());
                g.put("expectedPremium", c.getExpectedPremium() == null ? null : c.getExpectedPremium().toPlainString());
                g.put("baseline", c.getBaselineNo());
            }
        }
        String checksum = sha256(write(body));
        ObjectNode out = body.deepCopy();
        out.put("exportedBy", actor.getUsername());
        out.put("exportedAt", LocalDateTime.now().withNano(0).toString());
        out.put("checksum", checksum);
        out.put("signature", sign(checksum));
        out.put("signatureNote", "HMAC-SHA256 with this installation's package key (demo key unless"
                + " nsure.product-packages.signing-key is set)");
        evidence.record(actor.getUsername(), "Exported product package", "ProductConfiguration", ref,
                "v" + row.getVersionNo() + " checksum " + checksum);
        return out;
    }

    /**
     * Imports a package as a new draft. The signature and checksum must verify and every dependency must exist here
     * (rate table and its version, catalogue product, posting rules, issue template); otherwise nothing is created and
     * every problem is listed (AT-SH02-03). Golden cases come in as proposals for this installation's actuary.
     */
    @Transactional
    public PortfolioViews.ConfigView importPackage(JsonNode pkg) {
        AppUser actor = user("CREATE_PRODUCT");
        if (pkg == null || !pkg.isObject() || !PACKAGE_FORMAT.equals(pkg.path("format").asText())) {
            throw new IllegalArgumentException("This is not a product package (" + PACKAGE_FORMAT + ").");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("format", pkg.path("format").asText());
        body.set("configuration", pkg.path("configuration"));
        body.set("dependencies", pkg.path("dependencies"));
        body.set("goldenCases", pkg.path("goldenCases"));
        String checksum = sha256(write(body));
        List<String> problems = new ArrayList<>();
        if (!checksum.equals(pkg.path("checksum").asText())) {
            problems.add("The checksum does not match the content: the package was changed after it was exported.");
        } else if (!MessageDigest.isEqual(sign(checksum).getBytes(StandardCharsets.UTF_8),
                pkg.path("signature").asText().getBytes(StandardCharsets.UTF_8))) {
            problems.add("The signature does not verify with this installation's package key.");
        }
        JsonNode content = pkg.path("configuration");
        String code = content.path("code").asText("");
        if (!code.isBlank() && !em.createQuery("select c.ref from ProductConfiguration c where c.code = :code",
                String.class).setParameter("code", code).getResultList().isEmpty()) {
            problems.add("A configuration with code " + code + " already exists here.");
        }
        for (JsonNode d : pkg.path("dependencies")) {
            String type = d.path("type").asText();
            String ref = d.path("ref").asText();
            switch (type) {
                case "RATE_TABLE" -> {
                    Optional<RateTable> table = rateTable(ref);
                    if (table.isEmpty()) {
                        problems.add("Rate table " + ref + " does not exist in this installation.");
                    } else if (d.hasNonNull("version") && versions(table.get()).stream().noneMatch(v ->
                            v.getLabel().equals(d.get("version").asText())
                                    && RateTableVersion.PUBLISHED.equals(v.getStatus()))) {
                        problems.add("Rate table " + ref + " has no published version " + d.get("version").asText()
                                + " here.");
                    }
                }
                case "PRODUCT" -> {
                    if (product(ref).isEmpty()) {
                        problems.add("Catalogue product " + ref + " does not exist in this installation.");
                    }
                }
                case "POSTING_RULE" -> {
                    if (!postingRuleActive(ref)) {
                        problems.add("No active, approved posting rule for " + ref + " here.");
                    }
                }
                case "TEMPLATE" -> {
                    if (approvedTemplate(ref) == null) {
                        problems.add("No approved customer template for " + ref + " here.");
                    }
                }
                default -> problems.add("Unknown dependency type " + type + ".");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The package cannot be imported:\n" + String.join("\n", problems));
        }
        ProductConfiguration row = new ProductConfiguration();
        row.setRef(references.next("CFG-", 3, () -> references.highest("ProductConfiguration", "CFG-")));
        row.setDisplayOrder(0);
        row.setVersionNo(1);
        applyContent(row, content);
        row.setStatus(DRAFT);
        row.setApprovals(false);
        row.setImportedFrom(("Package " + checksum.substring(0, 12) + " of " + content.path("sourceRef").asText("?")
                + " v" + content.path("sourceVersion").asText("?") + " exported by " + pkg.path("exportedBy").asText("?"))
                .substring(0, Math.min(200, ("Package " + checksum.substring(0, 12) + " of "
                        + content.path("sourceRef").asText("?") + " v" + content.path("sourceVersion").asText("?")
                        + " exported by " + pkg.path("exportedBy").asText("?")).length())));
        List<String> conflicts = ProductRules.conflicts(ProductRules.parse(mapper, row.getRules()), row);
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException("The package cannot be imported:\n" + String.join("\n", conflicts));
        }
        em.persist(row);
        history.record(row, null, actor.getUsername());
        for (JsonNode g : pkg.path("goldenCases")) {
            BigDecimal expected = g.hasNonNull("expectedPremium") ? new BigDecimal(g.get("expectedPremium").asText()) : null;
            em.persist(new ProductGoldenCase(row.getRef(), g.path("label").asText("Imported case"),
                    LocalDate.parse(g.path("dateOfBirth").asText()), LocalDate.parse(g.path("pricedOn").asText()),
                    expected, 1, null, "Imported with the package; approve it in this installation",
                    actor.getUsername()));
        }
        evidence.record(actor.getUsername(), "Imported product package", "ProductConfiguration", row.getRef(),
                row.getImportedFrom());
        return view(row);
    }

    // ── quoting: the configured eligibility of a released plan ───────────────────────────────────

    /**
     * For a plan released from a configuration: every life must be within the configured entry ages under the
     * configured age basis, so a quote never sells outside what was approved (flat plans have no rate table to check).
     */
    public void requireEligible(CoverTier tier, List<RateTableService.LifeToPrice> lives, LocalDate on) {
        if (tier.getId() == null) {
            return;
        }
        ProductConfiguration row = em.createQuery("select c from ProductConfiguration c where c.publishedTierId = :id"
                        + " and c.status = :active", ProductConfiguration.class)
                .setParameter("id", tier.getId()).setParameter("active", ACTIVE).setMaxResults(1)
                .getResultStream().findFirst().orElse(null);
        if (row == null) {
            return;
        }
        String basis = ProductRules.text(ProductRules.parse(mapper, row.getRules()), "ageBasis");
        LocalDate day = on == null ? LocalDate.now() : on;
        List<String> problems = new ArrayList<>();
        for (RateTableService.LifeToPrice life : lives) {
            if (life.dateOfBirth() == null) {
                problems.add("Date of birth is required for " + (life.fullName() == null ? "each life" : life.fullName()) + ".");
                continue;
            }
            int age = ProductRules.age(basis, life.dateOfBirth(), day);
            if (age < row.getMinAge() || age > row.getMaxAge()) {
                problems.add((life.fullName() == null ? "The life" : life.fullName()) + " is " + age + " ("
                        + (basis == null ? "age last birthday" : basis.toLowerCase(Locale.ROOT).replace('_', ' '))
                        + "); " + targetProduct(row) + " " + planName(row) + " accepts lives aged " + row.getMinAge()
                        + "–" + row.getMaxAge() + " (" + row.getRef() + " v" + row.getVersionNo() + ").");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", problems));
        }
    }

    // ── internals: evaluation ────────────────────────────────────────────────────────────────────

    private record Evaluation(List<CheckItem> items, ObjectNode manifest, List<GoldenCaseView> golden) {

        boolean passed(boolean includeReview) {
            return items.stream().filter(i -> includeReview || !"review".equals(i.key()))
                    .allMatch(i -> i.ok() || !i.blocking());
        }
    }

    private Evaluation evaluate(ProductConfiguration row, boolean recordGolden) {
        ObjectNode rules = ProductRules.parse(mapper, row.getRules());
        List<CheckItem> items = new ArrayList<>();
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("configRef", row.getRef());
        manifest.put("contentHash", sha256(history.content(row).toString()));
        manifest.put("product", targetProduct(row));
        manifest.put("plan", planName(row));
        manifest.put("effectiveOn", row.getEffectiveOn());
        manifest.set("rules", rules);

        List<String> conflicts = ProductRules.conflicts(rules, row);
        items.add(new CheckItem("rules", "Typed rules and combinations", conflicts.isEmpty(), true,
                conflicts.isEmpty() ? rules.size() + " rules set, each within its schema; no prohibited combination"
                        : String.join(" ", conflicts)));
        List<String> refund = ProductRules.missingForRelease(rules, "Refund");
        items.add(new CheckItem("refund", "Refund and cancellation rules", refund.isEmpty(), true,
                refund.isEmpty() ? "Cooling-off " + rules.path("coolingOffDays").asText() + " days; "
                        + rules.path("refundOnCancellation").asText().toLowerCase(Locale.ROOT).replace('_', ' ')
                        : "Missing: " + String.join(", ", refund)));
        List<String> termination = ProductRules.missingForRelease(rules, "Termination");
        items.add(new CheckItem("termination", "Termination and lapse rules", termination.isEmpty(), true,
                termination.isEmpty() ? "Cover ceases at " + rules.path("cessationAge").asText() + "; lapses after "
                        + rules.path("lapseAfterMissedPremiums").asText() + " missed premium(s)"
                        : "Missing: " + String.join(", ", termination)));
        List<String> calculation = ProductRules.missingForRelease(rules, "Calculation");
        items.add(new CheckItem("calculation", "Age basis stated", calculation.isEmpty(), true,
                calculation.isEmpty() ? rules.path("ageBasis").asText().toLowerCase(Locale.ROOT).replace('_', ' ')
                        : "Missing: " + String.join(", ", calculation) + ". No engineering default is assumed."));

        // Catalogue target and plan.
        Optional<InsuranceProduct> product = product(targetProduct(row));
        CoverTier existing = product.flatMap(p -> tier(p, planName(row))).orElse(null);
        ProductConfiguration owner = existing == null ? null : em.createQuery("select c from ProductConfiguration c"
                        + " where c.publishedTierId = :id and c.ref <> :ref and c.status in (:live)", ProductConfiguration.class)
                .setParameter("id", existing.getId()).setParameter("ref", row.getRef() == null ? "" : row.getRef())
                .setParameter("live", List.of(ACTIVE, SCHEDULED)).setMaxResults(1).getResultStream().findFirst().orElse(null);
        long pendingTerms = product.isEmpty() ? 0 : em.createQuery("select count(v) from ProductVersion v where"
                        + " v.product = :p and v.approvalStatus = :pending", Long.class)
                .setParameter("p", product.get().getName()).setParameter("pending", ProductVersion.PENDING_APPROVAL)
                .getSingleResult();
        boolean targetOk = owner == null && pendingTerms == 0
                && (row.getCatalogueProduct() == null || product.isPresent());
        items.add(new CheckItem("target", "Catalogue product and plan", targetOk, true,
                owner != null ? planName(row) + " is already released by " + owner.getRef()
                        : pendingTerms > 0 ? "A product terms change for " + targetProduct(row) + " awaits approval"
                        : row.getCatalogueProduct() != null && product.isEmpty()
                                ? "Catalogue product " + row.getCatalogueProduct() + " does not exist"
                        : (product.isPresent() ? "Plan " + planName(row) + " of " + targetProduct(row)
                                + (existing == null ? " (new plan)" : " (updates the plan)")
                                : "Creates the catalogue product " + targetProduct(row) + " with plan " + planName(row))));

        // Rates: a rate table version, or the configured flat premium.
        ObjectNode ratesNode = manifest.putObject("rates");
        if (row.getRateTableRef() != null) {
            Optional<RateTable> table = rateTable(row.getRateTableRef());
            Optional<RateTableVersion> live = table.flatMap(this::published);
            boolean sameTarget = table.map(t -> t.getProduct().getName().equals(targetProduct(row))
                    && t.getTier().getName().equals(planName(row))).orElse(false);
            ratesNode.put("basis", "RATE_TABLE");
            ratesNode.put("rateTable", row.getRateTableRef());
            live.ifPresent(v -> ratesNode.put("version", v.getLabel()));
            items.add(new CheckItem("rates", "Rates", table.isPresent() && live.isPresent() && sameTarget, true,
                    table.isEmpty() ? "Rate table " + row.getRateTableRef() + " does not exist"
                            : !sameTarget ? "Rate table " + row.getRateTableRef() + " is for " + table.get().getTitle()
                                    + ", not " + targetProduct(row) + " – " + planName(row)
                            : live.isEmpty() ? "Rate table " + row.getRateTableRef() + " has no published version"
                            : "Rate table " + row.getRateTableRef() + " version " + live.get().getLabel()));
        } else {
            ratesNode.put("basis", "FLAT");
            ratesNode.put("premium", row.getPremiumAmount());
            ratesNode.put("frequency", row.getFrequency());
            items.add(new CheckItem("rates", "Rates", row.getPremiumAmount() > 0, true,
                    "Flat premium " + Money.display(BigDecimal.valueOf(row.getPremiumAmount())) + " " + row.getFrequency()
                            .toLowerCase(Locale.ROOT) + " for cover of " + Money.display(BigDecimal.valueOf(row.getCoverAmount()))));
        }

        // Wording and the approved issue template.
        String[] template = approvedTemplate("PolicyIssued");
        ObjectNode wording = manifest.putObject("wording");
        wording.put("text", row.getWording());
        if (template != null) {
            wording.put("template", template[0]);
            wording.put("templateVersion", template[1]);
        }
        boolean wordingOk = !blank(row.getWording()) && template != null;
        items.add(new CheckItem("wording", "Wording and issue template", wordingOk, true,
                blank(row.getWording()) ? "No wording named"
                        : template == null ? "No approved PolicyIssued customer template"
                        : row.getWording() + " · template " + template[0] + " v" + template[1]));

        // Accounting posting rules.
        ArrayNode accounting = manifest.putArray("accounting");
        List<String> missingPostings = new ArrayList<>();
        for (String event : postingsFor(row)) {
            Map<String, Object> rule = postingRule(event);
            if (rule == null) {
                missingPostings.add(event);
            } else {
                accounting.addObject().put("event", event).put("rule", String.valueOf(rule.get("id")))
                        .put("version", String.valueOf(rule.get("version")));
            }
        }
        items.add(new CheckItem("accounting", "Accounting mappings", missingPostings.isEmpty(), true,
                missingPostings.isEmpty() ? "Posting rules for " + String.join(", ", postingsFor(row))
                        : "No active, approved posting rule for " + String.join(", ", missingPostings)));

        // Commission for intermediated channels.
        boolean intermediated = split(row.getChannels()).stream().anyMatch(INTERMEDIATED::contains);
        List<Map<String, Object>> commission = jdbc.queryForList("select id, intermediary_type, initial_rate_percent,"
                + " renewal_rate_percent, effective_from from dist_commission_rule where product = ? order by"
                + " intermediary_type, effective_from desc", targetProduct(row));
        ArrayNode commissionNode = manifest.putArray("commission");
        commission.forEach(c -> commissionNode.addObject().put("rule", String.valueOf(c.get("id")))
                .put("type", String.valueOf(c.get("intermediary_type")))
                .put("initial", String.valueOf(c.get("initial_rate_percent")))
                .put("renewal", String.valueOf(c.get("renewal_rate_percent"))));
        items.add(new CheckItem("commission", "Commission rules", !intermediated || !commission.isEmpty(), intermediated,
                !intermediated ? "Not sold through intermediaries"
                        : commission.isEmpty() ? "Sold through an intermediary channel but " + targetProduct(row)
                                + " has no commission rule"
                        : commission.size() + " commission rule(s) for " + targetProduct(row)));

        // Reinsurance (information: treaty terms are decision D09).
        List<Map<String, Object>> treaties = jdbc.queryForList("select record_ref, name, ceded_percent from ri_treaty"
                + " where status = 'ACTIVE' and products like ?", "%" + targetProduct(row) + "%");
        ArrayNode reinsurance = manifest.putArray("reinsurance");
        treaties.forEach(t -> reinsurance.addObject().put("treaty", String.valueOf(t.get("record_ref")))
                .put("name", String.valueOf(t.get("name"))));
        items.add(new CheckItem("reinsurance", "Reinsurance treaty", true, false,
                treaties.isEmpty() ? "No active treaty covers " + targetProduct(row) + " (retained in full; D09 open)"
                        : "Treaty " + treaties.get(0).get("record_ref") + " " + treaties.get(0).get("name")));

        // Golden cases.
        List<GoldenCaseView> golden = new ArrayList<>();
        ArrayNode goldenNode = manifest.putArray("goldenCases");
        int approved = 0;
        int failing = 0;
        for (ProductGoldenCase c : goldenCases(row.getRef())) {
            String[] outcome = ProductGoldenCase.APPROVED.equals(c.getStatus())
                    || ProductGoldenCase.PROPOSED.equals(c.getStatus()) ? run(row, c) : null;
            if (outcome != null && recordGolden) {
                c.ran(outcome[0], outcome[1]);
            }
            if (ProductGoldenCase.APPROVED.equals(c.getStatus())) {
                approved++;
                if (!"PASS".equals(outcome[0])) {
                    failing++;
                }
                goldenNode.addObject().put("id", c.getId()).put("baseline", c.getBaselineNo())
                        .put("expected", c.getExpectedPremium() == null ? "REFUSED" : c.getExpectedPremium().toPlainString());
            }
            golden.add(golden(c, outcome));
        }
        items.add(new CheckItem("golden", "Golden cases", approved > 0 && failing == 0, true,
                approved == 0 ? "No approved golden case: an actuary must approve at least one"
                        : failing > 0 ? failing + " of " + approved + " approved case(s) no longer give the validated"
                                + " result: revise the configuration or approve a new baseline"
                        : approved + " approved case(s) pass"));

        // Independent approval.
        String maker = history.madeBy(row);
        boolean reviewed = row.getApprovedBy() != null && (maker == null || !maker.equalsIgnoreCase(row.getApprovedBy()));
        items.add(new CheckItem("review", "Independent approval", reviewed, true,
                row.getApprovedBy() == null ? "Not approved yet (by someone other than " + (maker == null ? "the maker" : maker) + ")"
                        : "Approved by " + row.getApprovedBy() + (maker == null ? "" : "; made by " + maker)));
        return new Evaluation(items, manifest, golden);
    }

    /** {result, actual}: PASS or FAIL with what the configuration gives now. */
    private String[] run(ProductConfiguration row, ProductGoldenCase c) {
        String actual = premiumFor(row, c.getDateOfBirth(), c.getPricedOn());
        String expected = c.getExpectedPremium() == null ? null : c.getExpectedPremium().toPlainString();
        boolean refused = actual.startsWith("REFUSED");
        boolean pass = expected == null ? refused : !refused && new BigDecimal(actual).compareTo(c.getExpectedPremium()) == 0;
        return new String[] {pass ? "PASS" : "FAIL", actual};
    }

    /** What quoting would charge a main insured with this date of birth on that day, or "REFUSED: why". */
    String premiumFor(ProductConfiguration row, LocalDate dob, LocalDate on) {
        String basis = ProductRules.text(ProductRules.parse(mapper, row.getRules()), "ageBasis");
        int age = ProductRules.age(basis, dob, on);
        if (age < row.getMinAge() || age > row.getMaxAge()) {
            return "REFUSED: age " + age + " is outside entry ages " + row.getMinAge() + "–" + row.getMaxAge();
        }
        if (row.getRateTableRef() == null) {
            return BigDecimal.valueOf(row.getPremiumAmount()).setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        Optional<RateTable> table = rateTable(row.getRateTableRef());
        Optional<RateTableVersion> live = table.flatMap(this::published);
        if (table.isEmpty() || live.isEmpty()) {
            return "REFUSED: no published rate table version";
        }
        try {
            return rates.priceAtVersion(table.get().getTier(), List.of(new RateTableService.LifeToPrice(selfRelationship(),
                    "Golden case", dob)), on, live.get().getLabel())
                    .map(p -> p.monthlyTotal().setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .orElse("REFUSED: no rate");
        } catch (IllegalArgumentException ex) {
            return "REFUSED: " + ex.getMessage();
        }
    }

    // ── internals: publication ───────────────────────────────────────────────────────────────────

    /**
     * Publishes to the catalogue: the product (created if new), the plan with the configured cover, premium and
     * frequency, the main insured's entry ages, and an approved contract terms version for new business. On sale now,
     * or Scheduled until the effective date.
     */
    private void release(ProductConfiguration row, String actor) {
        ObjectNode rules = ProductRules.parse(mapper, row.getRules());
        LocalDate effective = date(row.getEffectiveOn());
        boolean due = effective == null || !effective.isAfter(LocalDate.now());
        // Demo conversion (D02 open): a month of waiting period counts as 30 days in the contract terms.
        int waitingDays = row.getWaitingMonths() * 30;
        int maxBeneficiaries = ProductRules.intRule(rules, "maxBeneficiaries") == null ? 5
                : ProductRules.intRule(rules, "maxBeneficiaries");
        InsuranceProduct product = product(targetProduct(row)).orElseGet(() -> {
            InsuranceProduct created = InsuranceProduct.released(targetProduct(row), row.getDescription(),
                    productType(row), String.join("\n", split(row.getBenefits())), waitingDays, row.getGraceDays(),
                    maxBeneficiaries);
            em.persist(created);
            return created;
        });
        CoverTier tier = tier(product, planName(row)).orElseGet(() -> {
            int order = em.createQuery("select coalesce(max(t.displayOrder), 0) from CoverTier t where t.product = :p",
                    Integer.class).setParameter("p", product).getSingleResult() + 1;
            CoverTier created = new CoverTier(product, planName(row), order);
            em.persist(created);
            return created;
        });
        tier.release(row.getCoverAmount(), row.getPremiumAmount(), row.getFrequency(),
                String.join(", ", split(row.getBenefits())), due);
        RelationshipType self = em.createQuery("select r from RelationshipType r where r.selfRelationship = true",
                RelationshipType.class).setMaxResults(1).getResultStream().findFirst().orElse(null);
        if (self != null) {
            LifeAssuredRule rule = em.createQuery("select r from LifeAssuredRule r where r.tier = :t and r.relationship = :s",
                    LifeAssuredRule.class).setParameter("t", tier).setParameter("s", self).getResultStream().findFirst()
                    .orElse(null);
            if (rule == null) {
                em.persist(new LifeAssuredRule(tier, self, row.getMinAge(), row.getMaxAge(), 1, 1));
            } else {
                rule.ages(row.getMinAge(), row.getMaxAge());
            }
        }
        // Contract terms for new business from the effective date (or today); issued policies keep theirs.
        List<ProductVersion> versions = em.createQuery("select v from ProductVersion v where v.product = :p order by"
                + " v.versionNo desc", ProductVersion.class).setParameter("p", product.getName()).getResultList();
        LocalDate from = due ? LocalDate.now() : effective;
        for (ProductVersion v : versions) {
            if (v.isApproved() && v.getEffectiveFrom().isAfter(from)) {
                from = v.getEffectiveFrom();
            }
        }
        String maker = history.madeBy(row);
        ProductVersion terms = new ProductVersion(product.getName(), versions.isEmpty() ? 1 : versions.get(0).getVersionNo() + 1,
                from, waitingDays, row.getGraceDays(), maxBeneficiaries, clip(row.getWording(), 120),
                "Released from product configuration " + row.getRef() + " v" + row.getVersionNo(),
                maker == null ? actor : maker);
        em.persist(terms);
        terms.approve(actor);
        em.flush();
        row.setPublishedTierId(tier.getId());
        row.setProductVersionId(terms.getId());
        row.setReleasedBy(actor);
        row.setReleasedAt(LocalDateTime.now());
        row.setWithdrawnBy(null);
        row.setWithdrawnAt(null);
        row.setStatus(due ? ACTIVE : SCHEDULED);
        evidence.record(actor, due ? "Released product configuration" : "Scheduled product configuration",
                "ProductConfiguration", row.getRef(), product.getName() + " – " + tier.getName() + ": cover "
                        + row.getCoverAmount() + ", premium " + row.getPremiumAmount() + " " + row.getFrequency()
                        + ", ages " + row.getMinAge() + "–" + row.getMaxAge() + ", terms " + terms.getLabel()
                        + " from " + from + ", manifest " + row.getManifestChecksum());
    }

    private void takeOffSale(ProductConfiguration row, String actor, String reason) {
        if (row.getPublishedTierId() != null) {
            CoverTier tier = em.find(CoverTier.class, row.getPublishedTierId());
            if (tier != null) {
                tier.withdraw();
            }
        }
        row.setWithdrawnBy(actor);
        row.setWithdrawnAt(LocalDateTime.now());
        evidence.record(actor, "Withdrew product from new business", "ProductConfiguration", row.getRef(),
                targetProduct(row) + " – " + planName(row) + ": " + reason + ". Issued policies keep their terms.");
    }

    private PortfolioViews.ConfigView transition(ProductConfiguration row, String status, String actor, String note,
            String releaseNote) {
        JsonNode before = history.content(row);
        String previous = row.getStatus();
        row.setStatus(status);
        if (releaseNote != null) {
            row.setReleaseNote(clip(releaseNote, 500));
        }
        row.setVersionNo(row.getVersionNo() + 1);
        history.record(row, before, actor);
        history.publishStatusChange(row, previous, actor);
        evidence.record(actor, note, "ProductConfiguration", row.getRef(), "v" + row.getVersionNo() + " " + status);
        return view(row);
    }

    private ChecklistView checklistView(ProductConfiguration row, Evaluation evaluation) {
        String checksum = sha256(write(evaluation.manifest()));
        JsonNode frozen = row.getManifest() == null ? null : read(row.getManifest());
        List<String> actions = new ArrayList<>();
        switch (row.getStatus() == null ? DRAFT : row.getStatus()) {
            case DRAFT, WITHDRAWN -> actions.add("submit");
            case IN_REVIEW -> {
                actions.add("approve");
                actions.add("reject");
            }
            case APPROVED -> {
                actions.add("activate");
                actions.add("reject");
            }
            case ACTIVE, SCHEDULED -> actions.add("withdraw");
            default -> {
            }
        }
        if (!Set.of(ACTIVE, SCHEDULED).contains(row.getStatus())) {
            actions.add("rollback");
        }
        return new ChecklistView(row.getRef(), row.getVersionNo(), row.getStatus(),
                evaluation.passed(!Set.of(DRAFT, IN_REVIEW, WITHDRAWN).contains(row.getStatus())), evaluation.items(),
                frozen == null ? evaluation.manifest() : frozen, row.getManifestChecksum() == null ? checksum
                        : row.getManifestChecksum(), frozen != null,
                row.getManifestChecksum() == null || checksum.equals(row.getManifestChecksum()), evaluation.golden(),
                ProductRules.CATALOG, actions);
    }

    private void requirePassed(ProductConfiguration row, Evaluation evaluation, boolean includeReview) {
        List<String> failing = evaluation.items().stream()
                .filter(i -> i.blocking() && !i.ok() && (includeReview || !"review".equals(i.key())))
                .map(i -> i.label() + ": " + i.detail()).toList();
        if (!failing.isEmpty()) {
            throw new IllegalArgumentException(row.getRef() + " does not pass its release checklist:\n"
                    + String.join("\n", failing));
        }
    }

    private void requireChecker(ProductConfiguration row, AppUser actor, String action) {
        String maker = history.madeBy(row);
        if (maker != null && maker.equalsIgnoreCase(actor.getUsername())) {
            throw new NoAuthorizationException("You made the current version of " + row.getRef()
                    + ", so another user must " + action + " it (maker-checker).");
        }
    }

    private static void requireStatus(ProductConfiguration row, Set<String> allowed, String action) {
        if (!allowed.contains(row.getStatus())) {
            throw new IllegalArgumentException(row.getRef() + " is " + row.getStatus() + " and cannot be " + action + ".");
        }
    }

    private String changedComponents(ProductConfiguration row, ObjectNode now) {
        JsonNode frozen = read(row.getManifest());
        List<String> changed = new ArrayList<>();
        now.fieldNames().forEachRemaining(field -> {
            if (!now.get(field).equals(frozen.path(field))) {
                changed.add(field);
            }
        });
        return changed.isEmpty() ? "manifest" : String.join(", ", changed);
    }

    private void applyContent(ProductConfiguration row, JsonNode c) {
        row.setName(clip(c.path("name").asText(row.getName()), 100));
        row.setCode(clip(c.path("code").asText(row.getCode()), 20));
        row.setDomain(clip(c.path("domain").asText("Individual Life"), 40));
        row.setCurrency(clip(c.path("currency").asText("BWP"), 8));
        row.setDescription(clip(c.path("description").asText(row.getName()), 500));
        row.setEffectiveOn(clip(c.path("effective").asText("2026-10-01"), 20));
        row.setMinAge(c.path("minAge").asInt(18));
        row.setMaxAge(c.path("maxAge").asInt(65));
        row.setWaitingMonths(c.path("waitingMonths").asInt(3));
        row.setCoverAmount(c.path("cover").asInt(0));
        row.setPremiumAmount(c.path("premium").asInt(0));
        row.setFrequency(clip(c.path("frequency").asText("Monthly"), 20));
        row.setGraceDays(c.path("graceDays").asInt(30));
        row.setUnderwriting(clip(c.path("underwriting").asText("Manual review"), 40));
        row.setBenefits(clip(c.path("benefits").asText(""), 500));
        row.setChannels(clip(c.path("channels").asText(""), 300));
        row.setWording(clip(c.path("wording").asText(""), 200));
        row.setCatalogueProduct(c.hasNonNull("catalogueProduct") ? clip(c.get("catalogueProduct").asText(), 100) : null);
        row.setPlanName(c.hasNonNull("plan") ? clip(c.get("plan").asText(), 60) : null);
        row.setRateTableRef(c.hasNonNull("rateTableRef") ? clip(c.get("rateTableRef").asText(), 20) : null);
        ObjectNode rules = c.has("rules") ? ProductRules.clean(mapper, c.get("rules")) : mapper.createObjectNode();
        row.setRules(rules.isEmpty() ? null : write(rules));
    }

    // ── internals: lookups ───────────────────────────────────────────────────────────────────────

    String targetProduct(ProductConfiguration row) {
        return row.getCatalogueProduct() == null ? row.getName() : row.getCatalogueProduct();
    }

    String planName(ProductConfiguration row) {
        return row.getPlanName() == null ? "Standard" : row.getPlanName();
    }

    private List<String> postingsFor(ProductConfiguration row) {
        List<String> events = new ArrayList<>(requiredPostings.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
        if (row.getBenefits() != null && row.getBenefits().contains("Hospital cash")
                && !events.contains(Events.BENEFIT_PAID)) {
            events.add(Events.BENEFIT_PAID);
        }
        String refund = ProductRules.text(ProductRules.parse(mapper, row.getRules()), "refundOnCancellation");
        if ("NO_REFUND".equals(refund)) {
            events.remove(Events.PREMIUM_REFUNDED);
        }
        return events;
    }

    private Map<String, Object> postingRule(String event) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, version from fin_posting_rule where event_type = ?"
                + " and status = 'ACTIVE' and (approval_status is null or approval_status = 'APPROVED') order by version"
                + " desc", event);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean postingRuleActive(String event) {
        return postingRule(event) != null;
    }

    /** {code, version} of the latest approved customer template for an event, or null. */
    private String[] approvedTemplate(String event) {
        List<Map<String, Object>> rows = jdbc.queryForList("select code, version from com_template where event_type = ?"
                + " and status = 'APPROVED' order by version desc", event);
        return rows.isEmpty() ? null : new String[] {String.valueOf(rows.get(0).get("code")),
                String.valueOf(rows.get(0).get("version"))};
    }

    private Optional<InsuranceProduct> product(String name) {
        return em.createQuery("select p from InsuranceProduct p where p.name = :n", InsuranceProduct.class)
                .setParameter("n", name).getResultStream().findFirst();
    }

    private Optional<CoverTier> tier(InsuranceProduct product, String name) {
        return em.createQuery("select t from CoverTier t where t.product = :p and t.name = :n", CoverTier.class)
                .setParameter("p", product).setParameter("n", name).getResultStream().findFirst();
    }

    private Optional<RateTable> rateTable(String ref) {
        return em.createQuery("select t from RateTable t where t.ref = :r", RateTable.class).setParameter("r", ref)
                .getResultStream().findFirst();
    }

    private List<RateTableVersion> versions(RateTable table) {
        return em.createQuery("select v from RateTableVersion v where v.rateTable = :t order by v.versionNo desc",
                RateTableVersion.class).setParameter("t", table).getResultList();
    }

    private Optional<RateTableVersion> published(RateTable table) {
        return versions(table).stream().filter(v -> RateTableVersion.PUBLISHED.equals(v.getStatus())).findFirst();
    }

    private String selfRelationship() {
        return em.createQuery("select r.relationshipName from RelationshipType r where r.selfRelationship = true",
                String.class).setMaxResults(1).getResultStream().findFirst().orElse("Main Insured");
    }

    private static String productType(ProductConfiguration row) {
        String benefits = row.getBenefits() == null ? "" : row.getBenefits();
        return benefits.contains("Hospital cash") ? "Health" : benefits.contains("Funeral") ? "Funeral" : "Life Cover";
    }

    private ProductConfiguration config(String ref) {
        return em.createQuery("select c from ProductConfiguration c where c.ref = :r", ProductConfiguration.class)
                .setParameter("r", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Product configuration " + ref + " not found"));
    }

    private List<ProductGoldenCase> goldenCases(String ref) {
        return em.createQuery("select g from ProductGoldenCase g where g.configRef = :r order by g.id",
                ProductGoldenCase.class).setParameter("r", ref == null ? "" : ref).getResultList();
    }

    private ProductGoldenCase golden(String ref, long id) {
        ProductGoldenCase c = em.find(ProductGoldenCase.class, id);
        if (c == null || !c.getConfigRef().equals(ref)) {
            throw new NotFoundException("Golden case " + id + " not found on " + ref);
        }
        return c;
    }

    private GoldenCaseView golden(ProductGoldenCase c, String[] outcome) {
        return new GoldenCaseView(c.getId(), c.getLabel(), c.getDateOfBirth(), c.getPricedOn(), c.getExpectedPremium(),
                c.getStatus(), c.getBaselineNo(), c.getSupersedesId(), c.getReason(), c.getProposedBy(),
                c.getProposedAt(), c.getApprovedBy(), c.getApprovedAt(),
                outcome == null ? c.getLastResult() : outcome[0], outcome == null ? c.getLastActual() : outcome[1]);
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private String sign(String checksum) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(checksum.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return mapper.createObjectNode();
        }
    }

    static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    private static LocalDate date(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(required(value, field, 20));
        } catch (DateTimeParseException ex) {
            throw new com.nsure.common.exception.InvalidFieldException(field, field + " must be a date like 1990-04-12.");
        }
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        String v = body.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new com.nsure.common.exception.InvalidFieldException(field, field + " is required.");
        }
        if (value.trim().length() > max) {
            throw new IllegalArgumentException(field + " is too long (" + max + " characters at most).");
        }
        return value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value, int max) {
        return value == null || value.isBlank() ? null : clip(value.trim(), max);
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

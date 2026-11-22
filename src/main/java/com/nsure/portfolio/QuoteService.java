package com.nsure.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.rating.RateTableService;
import com.nsure.reference.RelationshipType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stored, versioned quotes (NS-IL01-001/004, NS-SH03-003/005). A quote keeps the inputs it was priced from, the rate
 * version, the calculation trace and an input hash, so the same price can be replayed later. It can be accepted
 * while valid; after that, or when the facts change, a re-quote makes the next version, shows what changed and needs
 * a fresh acceptance. Nothing here creates cover; issue uses the accepted version as it stands.
 */
@Service
public class QuoteService {

    /** The pricing logic's own version, stamped on every trace (NS-SH03-005). */
    static final String SERVICE_VERSION = "nsure-pricing/2026.11";

    /** A priced offer for one set of inputs: the tier, every life priced (main insured first) and the price. */
    public record PricedOffer(CoverTier tier, List<RateTableService.LifeToPrice> lives,
            Optional<RateTableService.PolicyPrice> price) {

        List<RateTableService.PricedLife> lines() {
            return price.map(RateTableService.PolicyPrice::lines).orElse(List.of());
        }

        BigDecimal premium() {
            return price.map(RateTableService.PolicyPrice::monthlyTotal)
                    .orElse(BigDecimal.valueOf(tier.getPremium()).setScale(2, java.math.RoundingMode.HALF_UP));
        }
    }

    private final EntityManager em;
    private final ObjectMapper mapper;
    private final ReferenceNumbers references;
    private final RateTableService rates;
    private final Outbox outbox;
    private final int validityDays;
    /** NS-IL01-001: every quote names the approved product terms (wording included) it was priced under. */
    private final ProductTerms terms;

    public QuoteService(EntityManager em, ObjectMapper mapper, ReferenceNumbers references, RateTableService rates,
            Outbox outbox, @Value("${nsure.quotes.validity-days:30}") int validityDays, ProductTerms terms) {
        this.terms = terms;
        this.em = em;
        this.mapper = mapper;
        this.references = references;
        this.rates = rates;
        this.outbox = outbox;
        this.validityDays = validityDays;
    }

    // ── recording ────────────────────────────────────────────────────────────────────────────────

    /** The first version of a new quote, priced today. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Quote record(CustomerAccount customer, String product, PricedOffer offer, String actor) {
        Quote quote = build(references.next("QTE-", 6, () -> 0L), 1, customer, product, offer, actor);
        em.persist(quote);
        return quote;
    }

    /**
     * The next version of {@code previous}, priced again from the current facts and rates. What changed is recorded
     * on it; an unaccepted earlier version is superseded and stays readable (AT-IL01-01).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Quote revise(Quote previous, CustomerAccount customer, PricedOffer offer, String actor) {
        Quote latest = latest(previous.getRef());
        if (latest.getVersionNo() != previous.getVersionNo()) {
            throw new ConflictException(previous.getRef() + " already has version " + latest.getVersionNo()
                    + "; re-quote from the latest version.");
        }
        Quote next = build(previous.getRef(), previous.getVersionNo() + 1, customer, previous.getProduct(), offer, actor);
        next.differences(differences(previous, next));
        if (previous.getApplicationRef() != null) {
            next.linkApplication(previous.getApplicationRef());
        }
        if (Quote.QUOTED.equals(previous.getStatus())) {
            previous.supersede();
        }
        em.persist(next);
        return next;
    }

    /**
     * The customer accepts the latest version while it is valid (NS-IL01-004). A superseded or expired version
     * cannot be accepted: the caller re-quotes and the customer accepts the revised offer instead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Quote accept(String ref, Integer version, String actor, String note) {
        Quote quote = latest(ref);
        if (version != null && version != quote.getVersionNo()) {
            throw new ConflictException(ref + " version " + version + " was replaced by version "
                    + quote.getVersionNo() + "; accept the latest offer.");
        }
        if (!Quote.QUOTED.equals(quote.getStatus())) {
            throw new ConflictException(ref + " version " + quote.getVersionNo() + " is already "
                    + quote.getStatus().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        if (quote.isExpired(LocalDate.now())) {
            throw new IllegalArgumentException(ref + " expired on " + quote.getValidUntil()
                    + ". Re-quote it, show the customer what changed, and accept the revised offer.");
        }
        quote.accept(actor, note);
        publishAccepted(quote, actor);
        return quote;
    }

    // ── underwriting terms on the price (NS-IL02-005, AT-IL02-02) ────────────────────────────────

    /**
     * The applicant accepted an underwriting offer that changes the premium: the next version of the quote carries
     * that premium, with the loading, the case and the offer on its trace, and is recorded as accepted. Each accepted
     * offer is its own version, so the price trace always matches what the policy will be issued at. Returns the
     * latest version unchanged when its premium already is the offer's.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Quote underwritten(String ref, BigDecimal basePremium, BigDecimal premium, BigDecimal loadingPercent,
            String caseRef, int offerVersion, String actor, String note) {
        Quote latest = latest(ref);
        if (latest.getPremium().compareTo(premium) == 0 && underwriting(latest) != null) {
            return latest;
        }
        Quote base = priced(ref);
        ObjectNode trace = (ObjectNode) read(base.getTraceJson()).deepCopy();
        ObjectNode uw = trace.putObject("underwriting");
        uw.put("caseRef", caseRef);
        uw.put("offerVersion", offerVersion);
        uw.put("basePremium", basePremium.toPlainString());
        if (loadingPercent != null) {
            uw.put("loadingPercent", loadingPercent.stripTrailingZeros().toPlainString());
        }
        uw.put("premium", premium.toPlainString());
        uw.put("step", "Underwriting offer v" + offerVersion + " on " + caseRef
                + (loadingPercent == null ? "" : ": loading " + loadingPercent.stripTrailingZeros().toPlainString()
                + "% on BWP " + Money.text(basePremium)) + " → BWP " + Money.text(premium));
        trace.put("premium", premium.toPlainString());
        Quote next = new Quote(latest, latest.getVersionNo() + 1, premium, write(trace),
                sha256(base.getInputsJson() + "|" + base.getRateVersion() + "|" + caseRef + "#" + offerVersion), actor);
        next.differences("Premium BWP " + Money.text(latest.getPremium()) + " → BWP " + Money.text(premium)
                + " (underwriting offer v" + offerVersion + " on " + caseRef + ")");
        next.accept(actor, clip("Underwriting offer v" + offerVersion + " accepted: " + note, 300));
        em.persist(next);
        // OfferAccepted for this acceptance is published by underwriting, with the case and this version.
        return next;
    }

    /**
     * Underwriting was reopened: the terms the applicant accepted no longer stand, so the price goes back to the
     * version priced before them, as a new accepted version. Returns null when no underwriting terms were on it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Quote withdrawUnderwriting(String ref, String caseRef, String actor, String reason) {
        Quote latest = latest(ref);
        if (underwriting(latest) == null) {
            return null;
        }
        Quote base = priced(ref);
        Quote next = new Quote(latest, latest.getVersionNo() + 1, base.getPremium(), base.getTraceJson(),
                base.getInputHash(), actor);
        next.differences("Premium BWP " + Money.text(latest.getPremium()) + " → BWP " + Money.text(base.getPremium())
                + " (underwriting on " + caseRef + " reopened; its offer terms are withdrawn)");
        next.accept(actor, clip("Price before underwriting terms, reinstated when " + caseRef + " was reopened: "
                + reason, 300));
        em.persist(next);
        return next;
    }

    /** The newest version priced from the rates alone, without underwriting terms. */
    private Quote priced(String ref) {
        return versions(ref).stream().filter(q -> underwriting(q) == null).findFirst()
                .orElseThrow(() -> new IllegalStateException(ref + " has no priced version"));
    }

    private JsonNode underwriting(Quote quote) {
        JsonNode uw = read(quote.getTraceJson()).get("underwriting");
        return uw == null || uw.isNull() ? null : uw;
    }

    private void publishAccepted(Quote quote, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quoteRef", quote.getRef());
        data.put("version", quote.getVersionNo());
        data.put("customerRef", quote.getCustomerRef());
        data.put("product", quote.getProduct());
        data.put("premium", quote.getPremium().toPlainString());
        data.put("rateVersion", quote.getRateVersion());
        outbox.publish(Events.OFFER_ACCEPTED, quote.getRef(), data, actor);
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Issue may use only an accepted, still-valid offer (NS-SH03-003, AT-IL01-01). An expired one needs a re-quote
     * and a fresh acceptance first, even if the rates have not moved.
     */
    public Quote requireIssuable(String ref, Integer version) {
        Quote quote = version == null ? latest(ref) : version(ref, version);
        Quote latest = latest(ref);
        if (latest.getVersionNo() != quote.getVersionNo()) {
            throw new IllegalArgumentException(ref + " version " + quote.getVersionNo() + " was replaced by version "
                    + latest.getVersionNo() + ". The customer must accept the revised offer before issue.");
        }
        if (!Quote.ACCEPTED.equals(quote.getStatus())) {
            throw new IllegalArgumentException(ref + " version " + quote.getVersionNo() + " has not been accepted."
                    + " Record the customer's acceptance before issue.");
        }
        if (quote.isExpired(LocalDate.now())) {
            throw new IllegalArgumentException(ref + " expired on " + quote.getValidUntil() + " and cannot be issued."
                    + " Re-quote it, show the customer what changed, and record their acceptance of the revised offer.");
        }
        return quote;
    }

    /** Ties every version of the quote to the application it was taken up on. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void linkApplication(Quote quote, String applicationRef) {
        versions(quote.getRef()).forEach(q -> q.linkApplication(applicationRef));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void linkPolicy(Quote quote, String policyRef) {
        quote.linkPolicy(policyRef);
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────────

    public Quote latest(String ref) {
        return em.createQuery("select q from Quote q where q.ref = :ref order by q.versionNo desc", Quote.class)
                .setParameter("ref", ref).setMaxResults(1).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Quote " + ref + " not found"));
    }

    public Quote version(String ref, int version) {
        return em.createQuery("select q from Quote q where q.ref = :ref and q.versionNo = :v", Quote.class)
                .setParameter("ref", ref).setParameter("v", version).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Quote " + ref + " has no version " + version));
    }

    public List<Quote> versions(String ref) {
        List<Quote> all = em.createQuery("select q from Quote q where q.ref = :ref order by q.versionNo desc",
                Quote.class).setParameter("ref", ref).getResultList();
        if (all.isEmpty()) {
            throw new NotFoundException("Quote " + ref + " not found");
        }
        return all;
    }

    /** The latest version of each quote, newest first, optionally for one customer. */
    public List<Quote> latestQuotes(String customerRef) {
        return em.createQuery("select q from Quote q where (:customer is null or q.customerRef = :customer)"
                        + " and q.versionNo = (select max(o.versionNo) from Quote o where o.ref = q.ref)"
                        + " order by q.id desc", Quote.class)
                .setParameter("customer", customerRef == null || customerRef.isBlank() ? null : customerRef)
                .setMaxResults(200).getResultList();
    }

    /** The lives the quote priced, ready to store on an application or policy exactly as quoted. */
    public List<RateTableService.PricedLife> pricedLives(Quote quote) {
        List<RateTableService.PricedLife> lines = new ArrayList<>();
        for (JsonNode line : read(quote.getTraceJson()).path("lines")) {
            RelationshipType relationship = em.createQuery("select r from RelationshipType r"
                            + " where r.relationshipName = :name", RelationshipType.class)
                    .setParameter("name", line.path("relationship").asText()).getResultStream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Relationship " + line.path("relationship").asText()
                            + " on " + quote.getRef() + " no longer exists"));
            lines.add(new RateTableService.PricedLife(relationship, line.path("fullName").asText(),
                    LocalDate.parse(line.path("dateOfBirth").asText()), line.path("age").asInt(),
                    new BigDecimal(line.path("coverAmount").asText()), new BigDecimal(line.path("monthlyPremium").asText())));
        }
        return lines;
    }

    /**
     * Prices the stored inputs again under the stored rate version and date (NS-SH03-005). A flat-tier quote is
     * reproduced from the tier premium it recorded.
     */
    public PortfolioViews.QuoteReplayView replay(Quote quote) {
        JsonNode inputs = read(quote.getInputsJson());
        JsonNode uw = underwriting(quote);
        if (Quote.FLAT_TIER.equals(quote.getPricingMethod())) {
            BigDecimal recorded = loaded(new BigDecimal(read(quote.getTraceJson()).path("flatTierPremium").asText("0")), uw);
            return new PortfolioViews.QuoteReplayView(quote.getRef(), quote.getVersionNo(), quote.getRateVersion(),
                    quote.getPremium(), recorded, recorded.compareTo(quote.getPremium()) == 0,
                    "Flat tier premium recorded on the trace" + (uw == null ? "." : ", with the underwriting loading."));
        }
        CoverTier tier = em.createQuery("select t from CoverTier t where t.product.name = :product and t.name = :tier",
                        CoverTier.class)
                .setParameter("product", inputs.path("product").asText())
                .setParameter("tier", inputs.path("tier").asText())
                .getResultStream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Tier " + inputs.path("tier").asText() + " no longer exists"));
        List<RateTableService.LifeToPrice> lives = new ArrayList<>();
        for (JsonNode life : inputs.path("lives")) {
            lives.add(new RateTableService.LifeToPrice(life.path("relationship").asText(), life.path("fullName").asText(),
                    LocalDate.parse(life.path("dateOfBirth").asText())));
        }
        Optional<RateTableService.PolicyPrice> price = rates.priceAtVersion(tier, lives, quote.getPricedOn(),
                quote.getRateVersion());
        BigDecimal replayed = price.map(RateTableService.PolicyPrice::monthlyTotal).map(p -> loaded(p, uw)).orElse(null);
        return new PortfolioViews.QuoteReplayView(quote.getRef(), quote.getVersionNo(), quote.getRateVersion(),
                quote.getPremium(), replayed, replayed != null && replayed.compareTo(quote.getPremium()) == 0,
                replayed == null ? "Rate version " + quote.getRateVersion() + " is no longer on file."
                        : "Re-priced from the stored inputs under " + quote.getRateVersion() + " as of " + quote.getPricedOn() + ".");
    }

    public PortfolioViews.QuoteRecordView view(Quote q) {
        boolean latest = latest(q.getRef()).getVersionNo() == q.getVersionNo();
        return new PortfolioViews.QuoteRecordView(q.getRef(), q.getVersionNo(), latest, q.getCustomerRef(),
                q.getCustomerName(), q.getProduct(), q.getTier(), q.getSumAssured(), q.getPremium(), q.getCurrency(),
                q.getPricingMethod(), q.getRateVersion(), q.getPricedOn(), q.getValidUntil(),
                q.isExpired(LocalDate.now()), q.getStatus(), q.getInputHash(), q.getServiceVersion(),
                read(q.getTraceJson()), q.getDifferences(), q.getAcceptedBy(), q.getAcceptedAt(),
                q.getAcceptanceNote(), q.getApplicationRef(), q.getPolicyRef(), q.getCreatedBy(), q.getCreatedAt());
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** A replayed premium with the underwriting loading on the trace, rounded as underwriting rounds it. */
    private static BigDecimal loaded(BigDecimal premium, JsonNode uw) {
        if (uw == null || !uw.hasNonNull("loadingPercent")) {
            return premium;
        }
        BigDecimal hundred = BigDecimal.valueOf(100);
        return premium.multiply(hundred.add(new BigDecimal(uw.get("loadingPercent").asText())))
                .divide(hundred, 2, java.math.RoundingMode.HALF_UP);
    }

    private Quote build(String ref, int version, CustomerAccount customer, String product, PricedOffer offer,
            String actor) {
        LocalDate today = LocalDate.now();
        CoverTier tier = offer.tier();
        boolean rated = offer.price().isPresent();
        String rateVersion = rated ? offer.price().get().rateTableVersion()
                : "TIER-" + tier.getId() + "@" + tier.getPremium();

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("customerRef", customer.getRef());
        inputs.put("product", product);
        inputs.put("tier", tier.getName());
        inputs.put("pricedOn", today.toString());
        ArrayNode lives = inputs.putArray("lives");
        for (RateTableService.LifeToPrice life : offer.lives()) {
            ObjectNode node = lives.addObject();
            node.put("relationship", life.relationship());
            node.put("fullName", life.fullName());
            node.put("dateOfBirth", life.dateOfBirth() == null ? null : life.dateOfBirth().toString());
        }

        // NS-IL01-001: only a product with approved terms in force today can be quoted; the version is on the trace.
        ProductVersion terms;
        try {
            terms = this.terms.inForce(product, today);
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException(product + " cannot be quoted: it has no approved product terms in force"
                    + " today.");
        }
        ObjectNode trace = mapper.createObjectNode();
        trace.put("method", rated ? Quote.RATE_TABLE : Quote.FLAT_TIER);
        ObjectNode productVersion = trace.putObject("productVersion");
        productVersion.put("id", terms.getId());
        productVersion.put("label", terms.getLabel());
        productVersion.put("effectiveFrom", terms.getEffectiveFrom().toString());
        productVersion.put("wordingRef", terms.getWordingRef());
        productVersion.put("waitingPeriodDays", terms.getWaitingPeriodDays());
        // The benefits the plan was offered with, as the tier described them when quoted.
        ArrayNode benefits = trace.putArray("benefits");
        for (String benefit : (tier.getBenefits() == null ? "" : tier.getBenefits()).split("[\\n;|]")) {
            if (!benefit.isBlank()) {
                benefits.add(benefit.trim());
            }
        }
        if (benefits.isEmpty()) {
            benefits.add(tier.getName() + " cover of BWP " + Money.text(Money.of(tier.getCoverAmount())));
        }
        trace.put("rateVersion", rateVersion);
        trace.put("pricedOn", today.toString());
        ArrayNode lines = trace.putArray("lines");
        BigDecimal sum = BigDecimal.ZERO;
        for (RateTableService.PricedLife line : offer.lines()) {
            ObjectNode node = lines.addObject();
            node.put("relationship", line.relationship().getRelationshipName());
            node.put("fullName", line.fullName());
            node.put("dateOfBirth", line.dateOfBirth().toString());
            node.put("age", line.age());
            node.put("coverAmount", line.coverAmount().toPlainString());
            node.put("monthlyPremium", line.monthlyPremium().toPlainString());
            node.put("step", "Age " + line.age() + " on " + today + " → band rate BWP " + Money.text(line.monthlyPremium()));
            sum = sum.add(line.monthlyPremium());
        }
        if (rated) {
            trace.put("sumOfLines", sum.toPlainString());
            trace.put("rounding", "Sum of the lines, rounded half-up to 2 decimal places");
        } else {
            trace.put("flatTierPremium", offer.premium().toPlainString());
            trace.put("rounding", "Flat tier premium; no rating factors");
        }
        trace.put("premium", offer.premium().toPlainString());
        trace.put("serviceVersion", SERVICE_VERSION);

        String inputsJson = write(inputs);
        return new Quote(ref, version, customer, product, tier.getName(), Money.of(tier.getCoverAmount()),
                offer.premium(), rated ? Quote.RATE_TABLE : Quote.FLAT_TIER, rateVersion, today,
                today.plusDays(validityDays), inputsJson, write(trace), sha256(inputsJson + "|" + rateVersion),
                SERVICE_VERSION, actor);
    }

    /** What a revised offer changes, in words: premium, rate version and each life's age or rate. */
    private String differences(Quote before, Quote after) {
        List<String> changes = new ArrayList<>();
        if (before.getPremium().compareTo(after.getPremium()) != 0) {
            changes.add("Premium BWP " + Money.text(before.getPremium()) + " → BWP " + Money.text(after.getPremium()));
        }
        if (!before.getRateVersion().equals(after.getRateVersion())) {
            changes.add("Rates " + before.getRateVersion() + " → " + after.getRateVersion());
        }
        if (before.getSumAssured().compareTo(after.getSumAssured()) != 0) {
            changes.add("Cover BWP " + Money.text(before.getSumAssured()) + " → BWP " + Money.text(after.getSumAssured()));
        }
        JsonNode oldLines = read(before.getTraceJson()).path("lines");
        JsonNode newLines = read(after.getTraceJson()).path("lines");
        for (int i = 0; i < Math.max(oldLines.size(), newLines.size()); i++) {
            JsonNode o = oldLines.path(i);
            JsonNode n = newLines.path(i);
            String who = (n.isMissingNode() ? o : n).path("fullName").asText();
            if (o.isMissingNode()) {
                changes.add(who + " added");
            } else if (n.isMissingNode()) {
                changes.add(who + " removed");
            } else if (o.path("age").asInt() != n.path("age").asInt()
                    || new BigDecimal(o.path("monthlyPremium").asText()).compareTo(new BigDecimal(n.path("monthlyPremium").asText())) != 0) {
                changes.add(who + ": age " + o.path("age").asInt() + " → " + n.path("age").asInt() + ", BWP "
                        + Money.text(new BigDecimal(o.path("monthlyPremium").asText())) + " → BWP "
                        + Money.text(new BigDecimal(n.path("monthlyPremium").asText())));
            }
        }
        String text = changes.isEmpty() ? "No change to the price or rates; a new validity period only." : String.join("; ", changes);
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A stored quote record cannot be read", ex);
        }
    }

    private String write(JsonNode node) {
        try {
            String json = mapper.writeValueAsString(node);
            if (json.length() > 4000) {
                throw new IllegalArgumentException("Too many lives to quote at once.");
            }
            return json;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

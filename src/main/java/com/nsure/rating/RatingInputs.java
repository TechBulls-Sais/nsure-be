package com.nsure.rating;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.InvalidFieldException;
import com.nsure.portfolio.CoverTier;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The explicit inputs of a calculation (NS-SH03-001): the effective date it is priced on, the currency, and the rate
 * and product versions the caller expects. Missing values take the documented defaults (today, the installation's
 * currency, the versions in force on that day); a value that is unsupported or does not match what is in force on
 * that day is refused — the service never guesses a nearby version, an age basis or a fallback tier.
 */
@Component
public class RatingInputs {

    /** Age basis of rate-table bands (D02 open; stated on every quote instead of being assumed). */
    public static final String AGE_BASIS = "AGE_LAST_BIRTHDAY";

    public record Inputs(LocalDate effectiveDate, String currency, String rateTableVersion, Integer productVersion) {
    }

    private final EntityManager em;
    private final String currency;
    private final int maxDaysAhead;
    private final boolean manualPremiumAllowed;

    public RatingInputs(EntityManager em, @Value("${nsure.insurer.currency:BWP}") String currency,
            @Value("${nsure.rating.max-days-ahead:90}") int maxDaysAhead,
            @Value("${nsure.rating.manual-premium-allowed:true}") boolean manualPremiumAllowed) {
        this.em = em;
        this.currency = currency;
        this.maxDaysAhead = maxDaysAhead;
        this.manualPremiumAllowed = manualPremiumAllowed;
    }

    /** Reads and checks {effectiveDate?, currency?, rateTableVersion?, productVersion?}. */
    public Inputs read(JsonNode body) {
        LocalDate today = LocalDate.now();
        LocalDate effective = today;
        if (body != null && body.hasNonNull("effectiveDate") && !body.get("effectiveDate").asText().isBlank()) {
            try {
                effective = LocalDate.parse(body.get("effectiveDate").asText().trim());
            } catch (DateTimeParseException ex) {
                throw new InvalidFieldException("effectiveDate", "effectiveDate must be a date like 2026-12-01.");
            }
            if (effective.isBefore(today) || effective.isAfter(today.plusDays(maxDaysAhead))) {
                throw new IllegalArgumentException("A quote is priced on a date from today to " + maxDaysAhead
                        + " days ahead; " + effective + " is outside that.");
            }
        }
        String asked = body == null || !body.hasNonNull("currency") ? currency : body.get("currency").asText().trim();
        if (!currency.equalsIgnoreCase(asked)) {
            throw new IllegalArgumentException("Currency " + asked + " is not supported: this installation prices in "
                    + currency + ".");
        }
        String rateVersion = body == null || !body.hasNonNull("rateTableVersion") ? null
                : body.get("rateTableVersion").asText().trim();
        Integer productVersion = body == null || !body.hasNonNull("productVersion") ? null
                : body.get("productVersion").asInt();
        return new Inputs(effective, currency.toUpperCase(java.util.Locale.ROOT),
                rateVersion == null || rateVersion.isBlank() ? null : rateVersion, productVersion);
    }

    /**
     * The rate table version that prices {@code tier} on {@code day}: a SCHEDULED version already effective then, else
     * the PUBLISHED one; empty for a flat-priced tier. A version the caller names must be exactly that one.
     */
    public Optional<String> rateVersionFor(CoverTier tier, LocalDate day, String expected) {
        List<RateTableVersion> versions = em.createQuery("select v from RateTableVersion v join v.rateTable t"
                        + " where t.tier.id = :tier and t.active = true order by v.versionNo desc", RateTableVersion.class)
                .setParameter("tier", tier.getId()).getResultList();
        Optional<RateTableVersion> applies = versions.stream()
                .filter(v -> RateTableVersion.SCHEDULED.equals(v.getStatus()) && v.getEffectiveFrom() != null
                        && !v.getEffectiveFrom().isAfter(day))
                .findFirst()
                .or(() -> versions.stream().filter(v -> RateTableVersion.PUBLISHED.equals(v.getStatus())).findFirst());
        if (expected != null) {
            if (applies.isEmpty()) {
                throw new IllegalArgumentException("The " + tier.getName() + " plan has no rate table, so rate version "
                        + expected + " cannot price it.");
            }
            if (!applies.get().getLabel().equals(expected)) {
                throw new IllegalArgumentException("Rate version " + expected + " is not the version in force on " + day
                        + " for the " + tier.getName() + " plan (that is " + applies.get().getLabel() + "). Re-quote under"
                        + " the version in force.");
            }
        }
        return applies.map(RateTableVersion::getLabel);
    }

    /** The approved product terms version for new business on {@code day}; a named one must match it. */
    public Integer productVersionFor(String product, LocalDate day, Integer expected) {
        Integer inForce = em.createQuery("select v.versionNo from ProductVersion v where v.product = :p and"
                        + " v.approvalStatus = 'APPROVED' and v.effectiveFrom <= :d order by v.effectiveFrom desc,"
                        + " v.versionNo desc", Integer.class)
                .setParameter("p", product).setParameter("d", day).setMaxResults(1).getResultStream().findFirst()
                .orElse(null);
        if (expected != null && !expected.equals(inForce)) {
            throw new IllegalArgumentException(product + " v" + expected + " is not the product version in force on " + day
                    + (inForce == null ? "." : " (that is v" + inForce + ")."));
        }
        return inForce;
    }

    /**
     * A record saved without a plan keeps the premium the caller sent, uncalculated (NS-SH03-003). Refused for a
     * product sold through plans unless the installation allows manual premiums (demo default: allowed, and recorded).
     */
    public void requireManualPremiumAllowed(String product) {
        if (manualPremiumAllowed) {
            return;
        }
        Long plans = em.createQuery("select count(t) from CoverTier t where t.product.name = :p and t.active = true",
                Long.class).setParameter("p", product).getSingleResult();
        if (plans > 0) {
            throw new IllegalArgumentException(product + " is priced from its plans: choose a plan. A manual premium"
                    + " without a calculation is switched off on this installation.");
        }
    }

    public boolean manualPremiumAllowed() {
        return manualPremiumAllowed;
    }
}

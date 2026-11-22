package com.nsure.integration;

import com.nsure.common.exception.ConflictException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks the provider whether it really received the money a notification claims (NS-SH04-004): a notification alone
 * never collects premium. No live provider status API exists yet (B2/B3/B4), so with
 * {@code nsure.integration.provider-simulator=true} the simulated providers answer from their own records; with it
 * off (production) every provider answers "no status query", and the receipt waits in suspense until a person
 * matches it to the bank statement and someone else approves the allocation.
 */
@Component
public class ProviderStatusClient {

    /** What the provider says it holds under a reference; {@code received=false} when it has no record. */
    public record ProviderStatus(boolean received, BigDecimal amount, String currency) {
    }

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final boolean simulator;
    private final InterfaceRegistry interfaces;

    public ProviderStatusClient(EntityManager em, PlatformSecurityContext security,
            @Value("${nsure.integration.provider-simulator:true}") boolean simulator, InterfaceRegistry interfaces) {
        this.em = em;
        this.security = security;
        this.simulator = simulator;
        this.interfaces = interfaces;
    }

    /**
     * Empty when the provider offers no status query, so the receipt must be matched to a statement instead. Throws
     * {@link InterfaceUnavailableException} when the provider does not answer (a timeout): the outcome is then
     * unknown, and the caller must ask again later rather than assume either answer (NS-SH12-004).
     */
    public Optional<ProviderStatus> status(String provider, String providerRef) {
        if (!simulator) {
            return Optional.empty();
        }
        if (!interfaces.available("BANK_PAYMENTS")) {
            throw new InterfaceUnavailableException("The status query to " + provider + " timed out (simulated outage"
                    + " of bank and wallet payments): the outcome of " + providerRef + " is unknown");
        }
        return Optional.of(find(provider, providerRef)
                .map(r -> new ProviderStatus(true, r.getAmount(), r.getCurrency()))
                .orElse(new ProviderStatus(false, null, null)));
    }

    /** Simulator only: the provider records that it received money, as a real bank or wallet would on its side. */
    @Transactional
    public SimulatedProviderReceipt simulateReceipt(String provider, String providerRef, String amount,
            String currency) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_INTEGRATION");
        if (!simulator) {
            throw new IllegalArgumentException("The provider simulator is off in this environment.");
        }
        if (blank(provider) || blank(providerRef) || blank(amount)) {
            throw new IllegalArgumentException("provider, providerReference and amount are required.");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(amount.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("amount must be a number.");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive.");
        }
        String ccy = blank(currency) ? "BWP" : currency.trim().toUpperCase(Locale.ROOT);
        Optional<SimulatedProviderReceipt> earlier = find(provider.trim(), providerRef.trim());
        if (earlier.isPresent()) {
            SimulatedProviderReceipt r = earlier.get();
            if (r.getAmount().compareTo(value) != 0 || !r.getCurrency().equals(ccy)) {
                throw new ConflictException("The provider already holds " + providerRef + " for "
                        + r.getCurrency() + " " + r.getAmount().toPlainString() + ".");
            }
            return r;
        }
        SimulatedProviderReceipt receipt = new SimulatedProviderReceipt(provider.trim(), providerRef.trim(), value,
                ccy, user.getUsername());
        em.persist(receipt);
        return receipt;
    }

    private Optional<SimulatedProviderReceipt> find(String provider, String providerRef) {
        return em.createQuery("select r from SimulatedProviderReceipt r where r.provider = :provider"
                        + " and r.providerRef = :ref", SimulatedProviderReceipt.class)
                .setParameter("provider", provider)
                .setParameter("ref", providerRef)
                .getResultStream()
                .findFirst();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

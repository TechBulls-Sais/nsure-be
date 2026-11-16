package com.nsure.portfolio;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Race-free policy, claim, customer and receipt references (spec 01 BR-59). Each series is a row taken with
 * {@code SELECT … FOR UPDATE} in the caller's transaction, so two concurrent requests can never read the same
 * number; the previous "highest reference + 1" scan let both take it, and the unique key then failed one of them.
 * The known series are seeded at startup from the highest reference already stored; a series first used later is
 * seeded in the caller's transaction.
 */
@Component
public class ReferenceNumbers implements ApplicationRunner {

    /** Series → the entity whose references it numbers. */
    private static final Map<String, String> KNOWN = Map.of(
            "CUS-", "CustomerAccount",
            "APP-", "InsuranceApplication",
            "POL-", "InsurancePolicy",
            "CFG-", "ProductConfiguration",
            "CLM-", "InsuranceClaim",
            "INV-", "PremiumReceipt");

    private final EntityManager em;
    private final TransactionTemplate tx;

    public ReferenceNumbers(EntityManager em, PlatformTransactionManager transactions) {
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(status -> KNOWN.forEach((prefix, entity) -> {
            if (em.find(ReferenceSequence.class, prefix) == null) {
                em.persist(new ReferenceSequence(prefix, highest(entity, prefix) + 1));
            }
        }));
    }

    /** The next reference, e.g. {@code next("POL-", 5, …)} → {@code POL-00042}. Call inside a transaction. */
    public String next(String prefix, int width, LongSupplier highestExisting) {
        ReferenceSequence sequence = em.find(ReferenceSequence.class, prefix, LockModeType.PESSIMISTIC_WRITE);
        if (sequence == null) {
            em.persist(new ReferenceSequence(prefix, highestExisting.getAsLong() + 1));
            em.flush();
            sequence = em.find(ReferenceSequence.class, prefix, LockModeType.PESSIMISTIC_WRITE);
        }
        return prefix + String.format("%0" + width + "d", sequence.take());
    }

    /** Highest numeric suffix among stored references of an entity, for seeding a series. */
    public long highest(String entity, String prefix) {
        long max = 0;
        for (String ref : em.createQuery("select e.ref from " + entity + " e where e.ref like :prefix", String.class)
                .setParameter("prefix", prefix + "%")
                .getResultList()) {
            String digits = ref.substring(prefix.length()).replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                max = Math.max(max, Long.parseLong(digits));
            }
        }
        return max;
    }
}

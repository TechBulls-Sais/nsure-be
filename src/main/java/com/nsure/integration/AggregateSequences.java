package com.nsure.integration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;

/**
 * Hands out each aggregate's next event version inside the publishing transaction. Everything happens on the
 * caller's own connection: a separate transaction here would need a second connection while the caller holds its
 * business locks, which starves the pool when many requests queue on the same record.
 */
@Component
class AggregateSequences {

    private final EntityManager em;

    AggregateSequences(EntityManager em) {
        this.em = em;
    }

    /** The next version for {@code aggregateRef}; holds the aggregate's sequence lock until the caller commits. */
    long next(String aggregateRef) {
        AggregateSequence sequence = em.find(AggregateSequence.class, aggregateRef, LockModeType.PESSIMISTIC_WRITE);
        if (sequence == null) {
            // The aggregate's first versioned event. Events published before versions existed count first. Two
            // transactions creating the same aggregate's first event at once is not a real flow (an aggregate is
            // created by one transaction); if it ever happened, the unique key makes the later one fail and retry.
            Long earlier = em.createQuery("select count(e) from OutboxEvent e where e.aggregateRef = :ref", Long.class)
                    .setParameter("ref", aggregateRef).getSingleResult();
            sequence = new AggregateSequence(aggregateRef, earlier);
            em.persist(sequence);
            em.flush();
        }
        return sequence.next();
    }
}

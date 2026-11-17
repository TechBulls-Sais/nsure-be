package com.nsure.audit.evidence;

import com.nsure.common.CorrelationId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Append-only, tamper-evident audit evidence (NS-SH11-005, AT-SH11-03).
 *
 * <p>Evidence recorded during a transaction joins the chain as that transaction commits, so it exists exactly when
 * the change it describes does; a rolled-back change leaves none. Records are appended one transaction at a time under
 * the chain-head lock, taken last so it never deadlocks with business locks. Each record's hash covers its content
 * and the previous record's hash; {@link #verify()} recomputes the whole chain.
 */
@Service
public class EvidenceChain {

    /** Evidence waiting for its transaction to commit. */
    public record Pending(LocalDateTime occurredAt, String actor, String action, String entityType, String entityRef,
            String reason, String correlationId) {
    }

    /** The result of walking the chain. {@code brokenAt} and {@code problem} are null when it is intact. */
    public record Verification(boolean intact, long records, Long brokenAt, String problem, String headHash) {
    }

    static final String GENESIS = "0".repeat(64);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int PAGE = 1000;

    private final EntityManager em;
    private final TransactionTemplate separate;

    public EvidenceChain(EntityManager em, PlatformTransactionManager transactions) {
        this.em = em;
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records evidence of a decision, change, approval, sensitive access, export or administrative act. {@code actor}
     * may be null for the signed-in user (or "system" when nobody is).
     */
    public void record(String actor, String action, String entityType, String entityRef, String reason) {
        Pending pending = new Pending(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                clip(actor == null || actor.isBlank() ? currentUser() : actor, 100), clip(action, 120),
                clip(entityType, 60), clip(entityRef, 80), clip(reason, 1000), clip(CorrelationId.current(), 64));
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            batch().pending.add(pending);
        } else {
            separate.executeWithoutResult(status -> append(List.of(pending)));
        }
    }

    /** Records evidence at once in its own transaction, for an act whose own transaction will roll back. */
    public void recordNow(String actor, String action, String entityType, String entityRef, String reason) {
        Pending pending = new Pending(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                clip(actor == null || actor.isBlank() ? currentUser() : actor, 100), clip(action, 120),
                clip(entityType, 60), clip(entityRef, 80), clip(reason, 1000), clip(CorrelationId.current(), 64));
        separate.executeWithoutResult(status -> append(List.of(pending)));
    }

    /** Walks the whole chain from the first record, recomputing every hash and link, then checks the head. */
    public Verification verify() {
        return separate.execute(status -> {
            String previous = GENESIS;
            long expected = 1;
            while (true) {
                List<AuditEvidence> page = em.createQuery("select e from AuditEvidence e where e.sequenceNo >= :from"
                                + " order by e.sequenceNo asc", AuditEvidence.class)
                        .setParameter("from", expected)
                        .setMaxResults(PAGE)
                        .getResultList();
                for (AuditEvidence e : page) {
                    if (e.getSequenceNo() != expected) {
                        return broken(expected - 1, expected, "Record " + expected + " is missing from the chain.");
                    }
                    if (!e.getPrevHash().equals(previous)) {
                        return broken(expected - 1, expected, "Record " + expected
                                + " does not follow the record before it.");
                    }
                    if (!hash(e).equals(e.getHash())) {
                        return broken(expected - 1, expected, "Record " + expected
                                + " was changed after it was recorded.");
                    }
                    previous = e.getHash();
                    expected++;
                }
                em.clear();
                if (page.size() < PAGE) {
                    break;
                }
            }
            long records = expected - 1;
            AuditChainHead head = em.find(AuditChainHead.class, AuditChainHead.ID);
            if (head == null || head.getLastSequence() != records || !head.getLastHash().equals(previous)) {
                return broken(records, records + 1, "The chain ends at record " + records + " but the head says "
                        + (head == null ? "nothing" : head.getLastSequence()) + ": the newest records were removed.");
            }
            return new Verification(true, records, null, null, previous);
        });
    }

    /** Recent evidence, newest first, optionally for one record or one actor. */
    public List<AuditEvidence> search(String entityType, String entityRef, String actor, int limit) {
        return search(entityType, entityRef, actor, null, null, limit, 500);
    }

    /**
     * Evidence newest first, filtered by record type and reference, actor and a time window (from inclusive, to
     * exclusive); at most {@code limit}, capped at {@code max}.
     */
    public List<AuditEvidence> search(String entityType, String entityRef, String actor, LocalDateTime from,
            LocalDateTime to, int limit, int max) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        filter(where, params, "e.entityType = :type", "type", blankToNull(entityType));
        filter(where, params, "e.entityRef = :ref", "ref", blankToNull(entityRef));
        filter(where, params, "e.actor = :actor", "actor", blankToNull(actor));
        filter(where, params, "e.occurredAt >= :from", "from", from);
        filter(where, params, "e.occurredAt < :to", "to", to);
        String jpql = "select e from AuditEvidence e" + (where.isEmpty() ? "" : " where " + String.join(" and ", where))
                + " order by e.sequenceNo desc";
        return separate.execute(status -> {
            var query = em.createQuery(jpql, AuditEvidence.class);
            params.forEach(query::setParameter);
            return query.setMaxResults(Math.max(1, Math.min(limit, max))).getResultList();
        });
    }

    private static void filter(List<String> where, Map<String, Object> params, String clause, String name,
            Object value) {
        if (value != null) {
            where.add(clause);
            params.put(name, value);
        }
    }

    private void append(List<Pending> pending) {
        AuditChainHead head = em.find(AuditChainHead.class, AuditChainHead.ID, LockModeType.PESSIMISTIC_WRITE);
        if (head == null) {
            throw new IllegalStateException("The audit chain head is missing; run the database migrations.");
        }
        long sequence = head.getLastSequence();
        String previous = head.getLastHash();
        for (Pending p : pending) {
            sequence++;
            AuditEvidence evidence = new AuditEvidence(sequence, p, previous);
            em.persist(evidence);
            previous = evidence.getHash();
        }
        head.advance(sequence, previous);
        em.flush();
    }

    private Batch batch() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Batch existing) {
                return existing;
            }
        }
        Batch batch = new Batch();
        TransactionSynchronizationManager.registerSynchronization(batch);
        return batch;
    }

    /** One transaction's evidence: appended before it commits, or (for a read-only transaction) just after. */
    private final class Batch implements TransactionSynchronization {

        private final List<Pending> pending = new ArrayList<>();
        private boolean deferred;

        @Override
        public void beforeCommit(boolean readOnly) {
            if (pending.isEmpty()) {
                return;
            }
            if (readOnly) {
                deferred = true;
            } else {
                append(pending);
            }
        }

        @Override
        public void afterCommit() {
            if (deferred) {
                separate.executeWithoutResult(status -> append(pending));
            }
        }

        @Override
        public int getOrder() {
            // Last: every business lock is already held, so the chain-head lock cannot be part of a deadlock.
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    static String hash(AuditEvidence e) {
        String content = String.join("\u001f", String.valueOf(e.getSequenceNo()), TIME.format(e.getOccurredAt()),
                nz(e.getActor()), nz(e.getAction()), nz(e.getEntityType()), nz(e.getEntityRef()), nz(e.getReason()),
                nz(e.getCorrelationId()), e.getPrevHash());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Verification broken(long records, long at, String problem) {
        return new Verification(false, records, at, problem, null);
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}

package com.nsure.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.common.CorrelationId;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs long-running commands in the background with a status resource (spec §10: GET /operations/{id} returns
 * accepted, processing, succeeded or failed, the result reference, the last update and an actionable error). The
 * caller checks the permission to start the command before submitting it; the work then runs in its own transaction
 * as the requester, so it commits as a whole or not at all and a failure leaves no half-done financial effect.
 */
@Service
public class Operations {

    private static final Logger log = LoggerFactory.getLogger(Operations.class);

    /** What a finished operation produced: a reference to the result and a summary of it. */
    public record Outcome(String resultRef, Object summary) {
    }

    public record OperationView(String id, String type, String status, String resultRef, JsonNode result,
            String error, String requestedBy, LocalDateTime createdAt, LocalDateTime updatedAt,
            LocalDateTime finishedAt, String statusUrl) {
    }

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final PlatformSecurityContext security;
    private final ExecutorService workers;

    public Operations(EntityManager em, PlatformTransactionManager transactions, ObjectMapper mapper,
            PlatformSecurityContext security, @Value("${nsure.operations.workers:2}") int workers) {
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mapper = mapper;
        this.security = security;
        AtomicInteger n = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(Math.max(1, workers), r -> {
            Thread t = new Thread(r, "nsure-operation-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Records an ACCEPTED operation and runs {@code work} in the background once the caller's transaction (if any)
     * has committed. {@code readPermission} is who else may read its status besides {@code requester}.
     */
    public OperationView submit(String type, String readPermission, AppUser requester, Supplier<Outcome> work) {
        String correlation = CorrelationId.current();
        Operation operation = tx.execute(s -> {
            Operation created = new Operation(type, requester.getUsername(), readPermission, correlation);
            em.persist(created);
            return created;
        });
        String id = operation.getId();
        Runnable run = () -> workers.submit(() -> execute(id, correlation, work));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    run.run();
                }
            });
        } else {
            run.run();
        }
        return view(operation);
    }

    private void execute(String id, String correlation, Supplier<Outcome> work) {
        if (correlation != null) {
            MDC.put("correlationId", correlation);
        }
        try {
            tx.executeWithoutResult(s -> em.find(Operation.class, id).processing());
            Outcome outcome = tx.execute(s -> work.get());
            String summary = outcome == null || outcome.summary() == null ? null : json(outcome.summary());
            tx.executeWithoutResult(s -> em.find(Operation.class, id)
                    .succeeded(outcome == null ? null : outcome.resultRef(), summary));
        } catch (IllegalArgumentException | ConflictException | NotFoundException ex) {
            // A business refusal: the message says what to correct.
            fail(id, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Operation {} failed", id, ex);
            fail(id, "The operation stopped unexpectedly and nothing it did was kept. Try again; if it fails again,"
                    + " give support the reference " + (correlation == null ? id : correlation) + ".");
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void fail(String id, String message) {
        tx.executeWithoutResult(s -> em.find(Operation.class, id).failed(message));
    }

    /** The operation's status, for the person who asked for it or a holder of its read permission; 404 otherwise. */
    public OperationView status(String id) {
        AppUser user = security.authenticatedUser();
        Operation operation = tx.execute(s -> em.find(Operation.class, id));
        if (operation == null || !(operation.getRequestedBy().equalsIgnoreCase(user.getUsername())
                || user.hasPermissionTo(operation.getReadPermission()))) {
            // Never says whether someone else's operation exists.
            throw new NotFoundException("Operation " + id + " not found");
        }
        return view(operation);
    }

    private OperationView view(Operation o) {
        JsonNode result = null;
        if (o.getResult() != null) {
            try {
                result = mapper.readTree(o.getResult());
            } catch (JsonProcessingException ex) {
                result = null;
            }
        }
        return new OperationView(o.getId(), o.getType(), o.getStatus(), o.getResultRef(), result, o.getError(),
                o.getRequestedBy(), o.getCreatedAt(), o.getUpdatedAt(), o.getFinishedAt(), "/operations/" + o.getId());
    }

    private String json(Object summary) {
        try {
            return mapper.writeValueAsString(summary);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }
}

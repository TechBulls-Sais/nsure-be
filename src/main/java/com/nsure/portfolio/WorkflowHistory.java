package com.nsure.portfolio;

import com.nsure.audit.evidence.EvidenceChain;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes and reads the workflow log: every transition of a policy, claim or customer review. */
@Service
public class WorkflowHistory {

    public static final String POLICY = "Policy";
    public static final String CLAIM = "Claim";
    public static final String KYC_REVIEW = "KYC Review";
    public static final String EDD_REVIEW = "EDD Review";
    /** performedBy for transitions the scheduler or straight-through processing makes. */
    public static final String SYSTEM = "system";

    private final EntityManager em;
    private final EvidenceChain evidence;

    public WorkflowHistory(EntityManager em, EvidenceChain evidence) {
        this.em = em;
        this.evidence = evidence;
    }

    @Transactional
    public void record(String entityType, String ref, String label, String action,
            String previousState, String newState, String details, String actor) {
        record(entityType, ref, label, action, previousState, newState, details, actor, java.time.LocalDate.now());
    }

    /**
     * Records a transition that takes effect on {@code effective} (NS-DAT-002): the first day the new state applies,
     * which may differ from today, when it is recorded. Cover is judged on effective dates, never on recording time.
     */
    @Transactional
    public void record(String entityType, String ref, String label, String action,
            String previousState, String newState, String details, String actor, java.time.LocalDate effective) {
        em.persist(new WorkflowLog(entityType, ref, label, action, previousState, newState,
                details == null || details.isBlank() ? null : details, actor, effective));
        // Every decision and status change on a policy, claim or review is also audit evidence (NS-SH11-005).
        evidence.record(actor, entityType + ": " + action, entityType, ref, details);
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.HistoryView> of(String entityType, String ref) {
        return em.createQuery(
                        "select l from WorkflowLog l where l.entityType = :type and l.entityRef = :ref"
                                + " order by l.performedAt asc, l.id asc", WorkflowLog.class)
                .setParameter("type", entityType)
                .setParameter("ref", ref)
                .getResultList()
                .stream()
                .map(row -> new PortfolioViews.HistoryView(
                        row.getAction(), blank(row.getPreviousState()), blank(row.getNewState()),
                        blank(row.getDetails()), row.getPerformedBy(), row.getPerformedAt().toString(),
                        row.getEffectiveDate().toString()))
                .toList();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}

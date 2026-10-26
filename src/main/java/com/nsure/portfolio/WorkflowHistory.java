package com.nsure.portfolio;

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

    public WorkflowHistory(EntityManager em) {
        this.em = em;
    }

    @Transactional
    public void record(String entityType, String ref, String label, String action,
            String previousState, String newState, String details, String actor) {
        em.persist(new WorkflowLog(entityType, ref, label, action, previousState, newState,
                details == null || details.isBlank() ? null : details, actor));
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
                        blank(row.getDetails()), row.getPerformedBy(), row.getPerformedAt().toString()))
                .toList();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}

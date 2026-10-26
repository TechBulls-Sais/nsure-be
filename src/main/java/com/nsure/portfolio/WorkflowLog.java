package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** One workflow transition on a policy, claim or customer review (demo server Activation Log). Never updated. */
@Entity
@Table(name = "workflow_log")
public class WorkflowLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_ref", nullable = false, length = 40)
    private String entityRef;

    @Column(name = "entity_label", length = 200)
    private String entityLabel;

    @Column(name = "action_name", nullable = false, length = 40)
    private String action;

    @Column(name = "previous_state", length = 40)
    private String previousState;

    @Column(name = "new_state", length = 40)
    private String newState;

    @Column(length = 1000)
    private String details;

    @Column(name = "performed_by", nullable = false, length = 100)
    private String performedBy;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    protected WorkflowLog() {
    }

    public WorkflowLog(String entityType, String entityRef, String entityLabel, String action,
            String previousState, String newState, String details, String performedBy) {
        this.entityType = entityType;
        this.entityRef = entityRef;
        this.entityLabel = entityLabel;
        this.action = action;
        this.previousState = previousState;
        this.newState = newState;
        this.details = details == null || details.length() <= 1000 ? details : details.substring(0, 1000);
        this.performedBy = performedBy;
        this.performedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityRef() {
        return entityRef;
    }

    public String getEntityLabel() {
        return entityLabel;
    }

    public String getAction() {
        return action;
    }

    public String getPreviousState() {
        return previousState;
    }

    public String getNewState() {
        return newState;
    }

    public String getDetails() {
        return details;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public LocalDateTime getPerformedAt() {
        return performedAt;
    }
}

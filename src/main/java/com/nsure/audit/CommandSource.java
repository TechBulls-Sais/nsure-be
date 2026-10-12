package com.nsure.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "command_source")
public class CommandSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_name", nullable = false, length = 100)
    private String actionName;

    @Column(name = "entity_name", nullable = false, length = 100)
    private String entityName;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "office_id")
    private Long officeId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "command_as_json")
    private String commandAsJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "result_json")
    private String resultJson;

    @Column(name = "result_status")
    private Integer resultStatus;

    @Column(name = "maker_id", nullable = false)
    private Long makerId;

    @Column(name = "made_on", nullable = false)
    private OffsetDateTime madeOn;

    @Column(name = "checker_id")
    private Long checkerId;

    @Column(name = "checked_on")
    private OffsetDateTime checkedOn;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    public String getPermissionCode() {
        return actionName + "_" + entityName;
    }

    public void markProcessed(Long resourceId, String resultJson) {
        assertStatus(CommandStatus.UNDER_PROCESSING);
        this.resourceId = resourceId;
        this.resultJson = resultJson;
        this.resultStatus = 200;
        this.status = CommandStatus.PROCESSED;
    }

    public void markError(int httpStatus, String message) {
        if (CommandStatus.PROCESSED.equals(status)) {
            throw new IllegalStateException("Processed commands are immutable");
        }
        this.resultStatus = httpStatus;
        this.resultJson = message;
        this.status = CommandStatus.ERROR;
    }

    public void markAwaitingApproval() {
        assertStatus(CommandStatus.UNDER_PROCESSING);
        this.status = CommandStatus.AWAITING_APPROVAL;
    }

    public void markChecked(Long checkerId) {
        assertStatus(CommandStatus.AWAITING_APPROVAL);
        this.checkerId = checkerId;
        this.checkedOn = OffsetDateTime.now(ZoneOffset.UTC);
        this.resultStatus = 200;
        this.status = CommandStatus.PROCESSED;
    }

    public void markRejected(Long checkerId, String reason) {
        assertStatus(CommandStatus.AWAITING_APPROVAL);
        this.checkerId = checkerId;
        this.checkedOn = OffsetDateTime.now(ZoneOffset.UTC);
        this.resultJson = reason;
        this.status = CommandStatus.REJECTED;
    }

    public void resetForRetry() {
        assertStatus(CommandStatus.ERROR);
        this.status = CommandStatus.UNDER_PROCESSING;
        this.resultJson = null;
        this.resultStatus = null;
    }

    private void assertStatus(String expected) {
        if (!expected.equals(status)) {
            throw new IllegalStateException("Command is " + status + " and cannot move to the requested state");
        }
    }

    public Long getId() {
        return id;
    }

    public String getActionName() {
        return actionName;
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public Long getOfficeId() {
        return officeId;
    }

    public void setOfficeId(Long officeId) {
        this.officeId = officeId;
    }

    public String getCommandAsJson() {
        return commandAsJson;
    }

    public void setCommandAsJson(String commandAsJson) {
        this.commandAsJson = commandAsJson;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public Integer getResultStatus() {
        return resultStatus;
    }

    public Long getMakerId() {
        return makerId;
    }

    public void setMakerId(Long makerId) {
        this.makerId = makerId;
    }

    public OffsetDateTime getMadeOn() {
        return madeOn;
    }

    public void setMadeOn(OffsetDateTime madeOn) {
        this.madeOn = madeOn;
    }

    public Long getCheckerId() {
        return checkerId;
    }

    public OffsetDateTime getCheckedOn() {
        return checkedOn;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}

package com.nsure.security.domain;

import com.nsure.audit.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_permission")
public class Permission extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permission_group", nullable = false, length = 45)
    private String grouping;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(name = "entity_name", length = 100)
    private String entityName;

    @Column(name = "action_name", length = 100)
    private String actionName;

    @Column(name = "function_name", length = 150)
    private String functionName;

    @Column(name = "can_maker_checker", nullable = false)
    private boolean canMakerChecker;

    public boolean hasCode(String checkCode) {
        return code != null && code.equalsIgnoreCase(checkCode);
    }

    public Long getId() {
        return id;
    }

    public String getGrouping() {
        return grouping;
    }

    public String getCode() {
        return code;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getActionName() {
        return actionName;
    }

    public String getFunctionName() {
        return functionName;
    }

    public boolean isCanMakerChecker() {
        return canMakerChecker;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }

    public void setGrouping(String grouping) {
        this.grouping = grouping;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    public void setCanMakerChecker(boolean canMakerChecker) {
        this.canMakerChecker = canMakerChecker;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        Permission permission = (Permission) other;
        return id != null && id.equals(permission.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

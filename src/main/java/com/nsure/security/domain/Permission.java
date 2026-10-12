package com.nsure.security.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_permission")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 45)
    private String grouping;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(name = "entity_name", length = 100)
    private String entityName;

    @Column(name = "action_name", length = 100)
    private String actionName;

    @Column(name = "can_maker_checker", nullable = false)
    private boolean canMakerChecker;

    protected Permission() {
    }

    public Permission(String grouping, String code, String entityName, String actionName, boolean canMakerChecker) {
        this.grouping = grouping;
        this.code = code;
        this.entityName = entityName;
        this.actionName = actionName;
        this.canMakerChecker = canMakerChecker;
    }

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

    public boolean isCanMakerChecker() {
        return canMakerChecker;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Permission permission)) {
            return false;
        }
        return id != null && id.equals(permission.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

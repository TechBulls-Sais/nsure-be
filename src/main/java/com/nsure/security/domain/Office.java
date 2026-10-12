package com.nsure.security.domain;

import com.nsure.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_office")
public class Office extends AuditableEntity {

    public static final String HEAD_OFFICE_HIERARCHY = ".";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String hierarchy;

    protected Office() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getHierarchy() {
        return hierarchy;
    }
}

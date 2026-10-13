package com.nsure.mipas.capability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "mipas_capability")
public class MipasCapability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Column(name = "capability_group", nullable = false, length = 40)
    private String grouping;

    @Column(nullable = false, length = 300)
    private String meaning;

    protected MipasCapability() {
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getGrouping() {
        return grouping;
    }

    public String getMeaning() {
        return meaning;
    }
}

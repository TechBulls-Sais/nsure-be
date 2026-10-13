package com.nsure.mipas.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "relationship_type")
public class RelationshipType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "relationship_name", nullable = false, unique = true, length = 80)
    private String relationshipName;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "is_self_relationship", nullable = false)
    private boolean selfRelationship;

    protected RelationshipType() {
    }

    public Long getId() {
        return id;
    }

    public String getRelationshipName() {
        return relationshipName;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isSelfRelationship() {
        return selfRelationship;
    }
}

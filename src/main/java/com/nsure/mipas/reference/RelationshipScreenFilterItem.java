package com.nsure.mipas.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "relationship_screen_filter_item")
public class RelationshipScreenFilterItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "filter_id", nullable = false)
    private RelationshipScreenFilter filter;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "relationship_type_id", nullable = false)
    private RelationshipType relationshipType;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected RelationshipScreenFilterItem() {
    }

    public Long getId() {
        return id;
    }

    public RelationshipScreenFilter getFilter() {
        return filter;
    }

    public RelationshipType getRelationshipType() {
        return relationshipType;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}

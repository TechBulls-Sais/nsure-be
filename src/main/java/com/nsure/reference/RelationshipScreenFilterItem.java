package com.nsure.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "relationship_screen_filter_item")
public class RelationshipScreenFilterItem {

    @Id
    private Long id;

    @Column(name = "filter_id", nullable = false)
    private Long filterId;

    @Column(name = "relationship_type_id", nullable = false)
    private Long relationshipTypeId;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    public Long getFilterId() {
        return filterId;
    }

    public Long getRelationshipTypeId() {
        return relationshipTypeId;
    }
}

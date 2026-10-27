package com.nsure.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "relationship_screen_filter")
public class RelationshipScreenFilter {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String screen;

    public Long getId() {
        return id;
    }

    public String getScreen() {
        return screen;
    }
}

package com.nsure.mipas.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "relationship_screen_filter")
public class RelationshipScreenFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String screen;

    @Column(name = "screen_label", length = 120)
    private String screenLabel;

    protected RelationshipScreenFilter() {
    }

    public Long getId() {
        return id;
    }

    public String getScreen() {
        return screen;
    }

    public String getScreenLabel() {
        return screenLabel;
    }
}

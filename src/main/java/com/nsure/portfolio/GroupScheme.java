package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ins_scheme")
public class GroupScheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 80)
    private String sector;

    @Column(nullable = false, length = 8)
    private String initials;

    @Column(nullable = false)
    private int members;

    @Column(nullable = false)
    private int premium;

    @Column(nullable = false, length = 20)
    private String renewal;

    @Column(nullable = false, length = 20)
    private String color;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    public String getRef() {
        return ref;
    }

    public String getName() {
        return name;
    }

    public String getSector() {
        return sector;
    }

    public String getInitials() {
        return initials;
    }

    public int getMembers() {
        return members;
    }

    public int getPremium() {
        return premium;
    }

    public String getRenewal() {
        return renewal;
    }

    public String getColor() {
        return color;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}

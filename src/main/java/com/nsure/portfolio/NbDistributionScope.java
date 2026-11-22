package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One channel a product may be sold through (NS-IL01-001). A product with no rows is sold on every channel; the
 * seeded rows are demo defaults awaiting the insurer's distribution decision.
 */
@Entity(name = "NbDistributionScope")
@Table(name = "nb_distribution_scope")
public class NbDistributionScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String product;

    @Column(nullable = false, length = 20)
    private String channel;

    protected NbDistributionScope() {
    }

    public String getProduct() {
        return product;
    }

    public String getChannel() {
        return channel;
    }
}

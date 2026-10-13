package com.nsure.portfolio;

import java.util.List;

public final class PortfolioViews {

    private PortfolioViews() {
    }

    public record CustomerView(
            String id,
            String name,
            String email,
            String phone,
            String product,
            int policies,
            String status,
            String color
    ) {
    }

    public record CoverView(
            String id,
            String name,
            String product,
            int amount,
            int premium,
            String status,
            String date
    ) {
    }

    public record ClaimView(
            String id,
            String name,
            String product,
            int amount,
            int premium,
            String status,
            String date,
            String type,
            String description,
            String eventDate
    ) {
    }

    public record ProductView(
            String name,
            String icon,
            String color,
            String description,
            String cover,
            String premium,
            int count
    ) {
    }

    public record SchemeView(
            String id,
            String name,
            String sector,
            String initials,
            int members,
            int premium,
            String renewal,
            String color
    ) {
    }

    public record ConfigView(
            String id,
            String name,
            String code,
            String domain,
            String currency,
            String description,
            String effective,
            int minAge,
            int maxAge,
            int waiting,
            int cover,
            int premium,
            String frequency,
            int grace,
            String underwriting,
            List<String> benefits,
            List<String> channels,
            String wording,
            boolean approvals,
            String status,
            int version
    ) {
    }
}

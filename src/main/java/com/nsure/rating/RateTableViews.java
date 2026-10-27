package com.nsure.rating;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class RateTableViews {

    private RateTableViews() {
    }

    /** One row of the rate table list (spec 11 section 4.1). */
    public record Summary(
            String ref,
            String title,
            String product,
            String productType,
            String plan,
            int planCover,
            String coverUnit,
            boolean active,
            String version,
            String validFrom,
            int rows,
            List<String> lifeTypes,
            BigDecimal fromPremium
    ) {
    }

    public record Detail(
            String ref,
            String title,
            String product,
            String productType,
            String plan,
            int planCover,
            String coverUnit,
            int flatPremium,
            boolean active,
            List<LifeRule> rules,
            Grid version,
            List<VersionSummary> versions
    ) {
    }

    /** A life the plan may cover; these are the grid's columns (spec 11, E-16). */
    public record LifeRule(String lifeType, Integer minAge, Integer maxAge, int maxCount) {
    }

    public record VersionSummary(
            int versionNo,
            String label,
            String validFrom,
            String validTo,
            String source,
            String createdBy,
            String changeSummary,
            int rows,
            boolean current
    ) {
    }

    /** One version's rates, pivoted: age bands down, life types across. */
    public record Grid(
            int versionNo,
            String label,
            boolean current,
            List<String> lifeTypes,
            Map<String, BigDecimal> cover,
            List<Band> bands,
            List<String> issues
    ) {
    }

    /** An age band; a life type with no rate in this band is absent from premiums. */
    public record Band(int ageFrom, int ageTo, Map<String, BigDecimal> premiums) {
    }

    /** One rate row as the canonical flat format carries it (spec 11 section 4.3). */
    public record RowInput(
            String lifeType,
            Integer ageFrom,
            Integer ageTo,
            BigDecimal coverAmount,
            BigDecimal monthlyPremium
    ) {
    }

    /** A saved grid; baseVersion is the version the editor loaded, so a stale save is refused. */
    public record GridSave(Integer baseVersion, List<RowInput> rows) {
    }
}

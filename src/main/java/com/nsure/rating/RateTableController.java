package com.nsure.rating;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rate table administration (spec 11 §8). Reads need rate.read (READ_RATE_TABLE); building, importing and submitting a
 * draft needs rate.write (UPDATE_RATE_TABLE); approving, rejecting and cancelling needs rate.publish
 * (PUBLISH_RATE_TABLE). The services check the same codes.
 */
@RestController
@RequestMapping("/admin/rate-tables")
public class RateTableController {

    private final RateTableService rates;
    private final RateVersionService versions;

    public RateTableController(RateTableService rates, RateVersionService versions) {
        this.rates = rates;
        this.versions = versions;
    }

    @GetMapping
    public List<RateTableViews.Summary> list() {
        return rates.list();
    }

    /** The table, its version history and one version's grid (the live one unless {@code version} is given). */
    @GetMapping("/{ref}")
    public RateTableViews.Detail detail(@PathVariable String ref, @RequestParam(required = false) Integer version) {
        return rates.detail(ref, version);
    }

    @GetMapping("/{ref}/versions/{v}/grid")
    public RateTableViews.Detail grid(@PathVariable String ref, @PathVariable int v) {
        return rates.detail(ref, v);
    }

    /** Saves the editor's grid as the table's DRAFT (never live; E-13). */
    @PutMapping("/{ref}/grid")
    public RateTableViews.Detail saveGrid(@PathVariable String ref, @RequestBody RateTableViews.GridSave body) {
        return rates.saveGrid(ref, body);
    }

    @PutMapping("/{ref}/versions/{v}/grid")
    public RateTableViews.Detail saveVersionGrid(@PathVariable String ref, @PathVariable int v,
            @RequestBody RateTableViews.GridSave body) {
        return rates.saveGrid(ref, new RateTableViews.GridSave(v, body.rows()));
    }

    /** {baseVersionUpdatedAt, cells:[{lifeType, ageFrom, ageTo, coverAmount, monthlyPremium (null removes)}]}. */
    @PatchMapping("/{ref}/versions/{v}/cells")
    public RateTableViews.Detail patchCells(@PathVariable String ref, @PathVariable int v,
            @RequestBody CellPatch body) {
        return versions.patchCells(ref, v, body.baseVersionUpdatedAt(), body.cells());
    }

    @PostMapping("/{ref}/versions/{v}/submit")
    public RateTableViews.Detail submit(@PathVariable String ref, @PathVariable int v) {
        return versions.submit(ref, v);
    }

    /** {effectiveFrom?, noticeIssuedAt?, overrideReason?}. */
    @PostMapping("/{ref}/versions/{v}/approve")
    public RateTableViews.Detail approve(@PathVariable String ref, @PathVariable int v,
            @RequestBody(required = false) JsonNode body) {
        return versions.approve(ref, v, date(body, "effectiveFrom"), date(body, "noticeIssuedAt"), text(body, "overrideReason"));
    }

    @PostMapping("/{ref}/versions/{v}/reject")
    public RateTableViews.Detail reject(@PathVariable String ref, @PathVariable int v,
            @RequestBody(required = false) JsonNode body) {
        return versions.reject(ref, v, text(body, "reason"));
    }

    @PostMapping("/{ref}/versions/{v}/cancel")
    public RateTableViews.Detail cancel(@PathVariable String ref, @PathVariable int v,
            @RequestBody(required = false) JsonNode body) {
        return versions.cancel(ref, v, text(body, "reason"));
    }

    @GetMapping("/{ref}/versions/{a}/diff/{b}")
    public RateVersionService.Diff diff(@PathVariable String ref, @PathVariable int a, @PathVariable int b) {
        return versions.diff(ref, a, b);
    }

    @GetMapping("/{ref}/versions/{v}/impact")
    public RateVersionService.Impact impact(@PathVariable String ref, @PathVariable int v) {
        return versions.impact(ref, v);
    }

    @GetMapping("/{ref}/export")
    public ResponseEntity<String> export(@PathVariable String ref, @RequestParam(required = false) Integer version) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ref + ".csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(versions.export(ref, version));
    }

    /** {csv}: a dry run. Nothing is written; the response shows the diff, issues and the confirm token. */
    @PostMapping("/{ref}/import")
    public RateVersionService.ImportDryRun importDryRun(@PathVariable String ref, @RequestBody JsonNode body) {
        return versions.importDryRun(ref, text(body, "csv"));
    }

    /** {csv, dryRunToken}: applies a clean dry run as a DRAFT. */
    @PostMapping("/{ref}/import/confirm")
    public RateTableViews.Detail importConfirm(@PathVariable String ref, @RequestBody JsonNode body) {
        return versions.importConfirm(ref, text(body, "csv"), text(body, "dryRunToken"));
    }

    /** {factor, lifeType?}: an across-the-board uplift as a DRAFT. */
    @PostMapping("/{ref}/scale")
    public RateTableViews.Detail scale(@PathVariable String ref, @RequestBody JsonNode body) {
        BigDecimal factor = body.hasNonNull("factor") ? new BigDecimal(body.get("factor").asText()) : null;
        return versions.scale(ref, factor, text(body, "lifeType"));
    }

    private static String text(JsonNode body, String field) {
        return body == null || !body.hasNonNull(field) ? null : body.get(field).asText();
    }

    private static LocalDate date(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException(field + " must be a date like 2027-01-31.");
        }
    }

    public record CellPatch(String baseVersionUpdatedAt, List<RateTableViews.RowInput> cells) {
        public CellPatch {
            cells = cells == null ? new ArrayList<>() : cells;
        }
    }
}

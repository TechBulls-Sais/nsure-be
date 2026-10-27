package com.nsure.rating;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rate table administration (docs/spec/11): list, a version's pivoted grid, and saving a new version. */
@RestController
@RequestMapping("/admin/rate-tables")
public class RateTableController {

    private final RateTableService rates;

    public RateTableController(RateTableService rates) {
        this.rates = rates;
    }

    @GetMapping
    public List<RateTableViews.Summary> list() {
        return rates.list();
    }

    /** The table, its version history and one version's grid (the current one unless {@code version} is given). */
    @GetMapping("/{ref}")
    public RateTableViews.Detail detail(@PathVariable String ref, @RequestParam(required = false) Integer version) {
        return rates.detail(ref, version);
    }

    @PutMapping("/{ref}/grid")
    public RateTableViews.Detail saveGrid(@PathVariable String ref, @RequestBody RateTableViews.GridSave body) {
        return rates.saveGrid(ref, body);
    }
}

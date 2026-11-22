package com.nsure.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reports")
public class ReportingController {

    private final ReportingService reporting;
    private final ReportDrill drill;
    private final ReportExports exports;

    public ReportingController(ReportingService reporting, ReportDrill drill, ReportExports exports) {
        this.reporting = reporting;
        this.drill = drill;
        this.exports = exports;
    }

    /** Optional filters: from, to (yyyy-mm-dd), product, intermediary (a reference or DIRECT), scheme. */
    @GetMapping("/management")
    public ReportViews.ManagementReport management(@RequestParam Map<String, String> filters) {
        return reporting.management(filters);
    }

    /** The source rows behind one figure: ?key=policies.inforce plus the same filters. */
    @GetMapping("/drill")
    public ReportDrill.Result drill(@RequestParam Map<String, String> params) {
        return drill.drill(params.get("key"), ReportFilter.of(params));
    }

    /** {kind: DRILL | SECTION, key, filters...}: a logged export. */
    @PostMapping("/exports")
    public Map<String, Object> export(@RequestBody JsonNode body) {
        return exports.export(body);
    }

    @GetMapping("/exports")
    public List<Map<String, Object>> exportLog() {
        return exports.log();
    }

    /** A partner's own portfolio, whatever template it selects (AT-SH13-03). */
    @GetMapping("/partner-portfolio")
    public Map<String, Object> partnerPortfolio(@RequestParam(required = false) String template) {
        return exports.partnerPortfolio(template);
    }
}

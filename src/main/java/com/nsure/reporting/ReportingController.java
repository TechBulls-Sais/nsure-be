package com.nsure.reporting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reports")
public class ReportingController {

    private final ReportingService reporting;

    public ReportingController(ReportingService reporting) {
        this.reporting = reporting;
    }

    @GetMapping("/management")
    public ReportViews.ManagementReport management() {
        return reporting.management();
    }
}

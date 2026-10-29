package com.nsure.reporting;

import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The management dashboard (SH13): every section read from authoritative tables at request time, stamped with its
 * as-of time (NS-DAT-005). A section that fails reports its error instead of hiding the rest of the dashboard.
 */
@Service
public class ReportingService {

    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);

    private final PlatformSecurityContext security;
    private final ObjectProvider<ReportSection> sections;

    public ReportingService(PlatformSecurityContext security, ObjectProvider<ReportSection> sections) {
        this.security = security;
        this.sections = sections;
    }

    // Not one transaction: each section reads on its own, so one failing query cannot roll back the others.
    public ReportViews.ManagementReport management() {
        security.authenticatedUser().validateHasPermissionTo("READ_REPORT");
        List<ReportViews.Section> built = new ArrayList<>();
        for (ReportSection section : sections.orderedStream().toList()) {
            try {
                built.add(section.build());
            } catch (RuntimeException ex) {
                log.warn("Report section {} failed", section.getClass().getSimpleName(), ex);
                built.add(new ReportViews.Section(section.getClass().getSimpleName(), section.getClass().getSimpleName(),
                        "SHARED", 999, List.of(), List.of(), LocalDateTime.now(), "This section could not be loaded."));
            }
        }
        built.sort(Comparator.comparingInt(ReportViews.Section::order));
        return new ReportViews.ManagementReport(built, LocalDateTime.now(),
                "Live: read from the transaction tables at the time shown. Not an approved financial statement.");
    }
}

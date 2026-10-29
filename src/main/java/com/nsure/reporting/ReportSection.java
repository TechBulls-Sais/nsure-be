package com.nsure.reporting;

/**
 * One block of the management dashboard. Each module contributes its own section as a Spring bean, reading its own
 * tables, so the report never reaches into another module's write model.
 */
public interface ReportSection {

    ReportViews.Section build();
}

package com.nsure.reporting;

/**
 * One block of the management dashboard. Each module contributes its own section as a Spring bean, reading its own
 * tables, so the report never reaches into another module's write model. The filter carries the authorised dimensions
 * (NS-SH13-002); a section applies those it supports and says which in {@code Section.dimensions}.
 */
public interface ReportSection {

    ReportViews.Section build(ReportFilter filter);
}

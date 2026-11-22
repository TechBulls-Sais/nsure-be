package com.nsure.finance;

import java.math.BigDecimal;
import java.util.List;

/**
 * The insurer's corporate GL (NS-SH08-006), keyed by the subledger's batch id: sending the same batch again never
 * posts it twice, and its status can be asked by batch id after a timeout (AT-SH08-02). Only
 * {@link SimulatedCorporateGl} ships until the insurer's ERP interface is agreed (D12).
 */
public interface CorporateGl {

    String name();

    /**
     * Delivers a batch. {@code simulate} (ACCEPT, REJECT or TIMEOUT) steers only a simulated GL. A TIMEOUT answer is
     * {@code null}: the GL may or may not have posted it.
     */
    Ack send(ExportFile file, String simulate);

    /** What the GL holds for a batch id: POSTED, REJECTED or UNKNOWN (never received). */
    Ack status(String batchRef);

    record Ack(String status, String glReference, BigDecimal debitTotal, BigDecimal creditTotal, int deliveries,
            String reason) {
    }

    /** The agreed export format: batch and line ids, control totals and one line per journal line. */
    record ExportFile(String batchRef, int journalCount, int lineCount, BigDecimal debitTotal, BigDecimal creditTotal,
            String currency, List<ExportLine> lines) {
    }

    record ExportLine(String lineId, String journalRef, String eventType, String accountingDate, String periodCode,
            String accountCode, BigDecimal debit, BigDecimal credit, String currency, String costCentre,
            String policyRef, String product, String intermediaryRef, String treatyRef, String schemeRef,
            String description) {
    }
}

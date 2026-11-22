package com.nsure.finance;

import com.nsure.payment.PaymentGateways;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * SIMULATED corporate GL (NS-SH08-006, AT-SH08-02). It stands in for the insurer's ERP until its interface is agreed
 * (D12): a batch is posted once per batch id however often it is delivered, a delivery can be answered with an
 * acceptance, a rejection or no answer at all (a timeout after posting), and its status can be asked by batch id. A
 * batch whose debits and credits differ is always rejected. Off under the prod profile unless demo payments are on.
 */
@Component
public class SimulatedCorporateGl implements CorporateGl {

    private final EntityManager em;
    private final PaymentGateways gateways;

    public SimulatedCorporateGl(EntityManager em, PaymentGateways gateways) {
        this.em = em;
        this.gateways = gateways;
    }

    @Override
    public String name() {
        return "GL_SIMULATOR";
    }

    @Override
    public Ack send(ExportFile file, String simulate) {
        requireEnabled();
        GlSimulatorRecord held = record(file.batchRef());
        if (held != null) {
            // The same batch id again: nothing new is posted; the GL answers with what it already holds.
            held.delivered();
            return ack(held);
        }
        String mode = simulate == null || simulate.isBlank() ? "ACCEPT" : simulate.trim().toUpperCase(Locale.ROOT);
        boolean balanced = file.debitTotal().compareTo(file.creditTotal()) == 0;
        String status = !balanced || "REJECT".equals(mode) ? "REJECTED" : "POSTED";
        String reason = !balanced ? "Debits and credits differ" : "REJECT".equals(mode)
                ? "Rejected by the GL (simulated): cost centre not open in the GL" : null;
        GlSimulatorRecord record = new GlSimulatorRecord(file.batchRef(), status,
                "POSTED".equals(status) ? "SIMGL-" + file.batchRef() : null, file.debitTotal(), file.creditTotal(),
                file.lineCount(), reason);
        em.persist(record);
        return "TIMEOUT".equals(mode) && "POSTED".equals(status) ? null : ack(record);
    }

    @Override
    public Ack status(String batchRef) {
        requireEnabled();
        GlSimulatorRecord held = record(batchRef);
        return held == null ? new Ack("UNKNOWN", null, null, null, 0, "The GL has no batch " + batchRef) : ack(held);
    }

    private GlSimulatorRecord record(String batchRef) {
        return em.createQuery("select r from GlSimulatorRecord r where r.batchRef = :ref", GlSimulatorRecord.class)
                .setParameter("ref", batchRef).getResultStream().findFirst().orElse(null);
    }

    private static Ack ack(GlSimulatorRecord r) {
        return new Ack(r.getStatus(), r.getGlReference(), r.getDebitTotal(), r.getCreditTotal(), r.getDeliveries(),
                r.getReason());
    }

    private void requireEnabled() {
        if (!gateways.simulatorsEnabled()) {
            throw new IllegalArgumentException("No corporate GL interface is connected and the GL simulator is off"
                    + " here; record the GL's answer by hand.");
        }
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}

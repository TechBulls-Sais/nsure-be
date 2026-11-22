package com.nsure.collections;

import com.nsure.payment.PaymentGateways;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SIMULATED debit-order and mobile-money collection provider (NS-SH04-002). It stands in for a bank's debit-order
 * service until a provider is contracted (D12): it accepts a batch file and answers each instruction. Unless the
 * operator chooses an outcome, a demo rule decides it from the payer's account number: one ending in
 * {@code nsure.collections.simulator.fail-suffix} (99) is unpaid for insufficient funds, one ending in
 * {@code pending-suffix} (98) stays pending, every other one is paid. Like the payment simulators, it is off under the
 * prod profile unless demo payments are on.
 */
@Component
public class SimulatedCollectionRail implements CollectionRail {

    public static final String PROVIDER = "DEBIT_ORDER_SIM";

    private final PaymentGateways gateways;
    private final String failSuffix;
    private final String pendingSuffix;

    public SimulatedCollectionRail(PaymentGateways gateways,
            @Value("${nsure.collections.simulator.fail-suffix:99}") String failSuffix,
            @Value("${nsure.collections.simulator.pending-suffix:98}") String pendingSuffix) {
        this.gateways = gateways;
        this.failSuffix = failSuffix;
        this.pendingSuffix = pendingSuffix;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Submission submit(CollectionBatch batch, List<CollectionInstruction> instructions) {
        requireEnabled();
        Map<String, String> references = new LinkedHashMap<>();
        int n = 0;
        for (CollectionInstruction instruction : instructions) {
            references.put(instruction.getRef(), "SIM-" + batch.getRef() + "-" + String.format("%04d", ++n));
        }
        return new Submission("SIMFILE-" + batch.getRef(), references);
    }

    @Override
    public Answer answer(CollectionInstruction instruction, String requested, String reason) {
        requireEnabled();
        String outcome = requested == null ? null : requested.trim().toUpperCase(Locale.ROOT);
        if (outcome == null || outcome.isEmpty()) {
            String account = instruction.getMandate().getAccountNumber().replaceAll("\\s", "");
            outcome = account.endsWith(failSuffix) ? CollectionInstruction.FAILED
                    : account.endsWith(pendingSuffix) ? CollectionInstruction.PENDING : CollectionInstruction.CONFIRMED;
        }
        return switch (outcome) {
            case CollectionInstruction.CONFIRMED -> new Answer(CollectionInstruction.CONFIRMED, "00",
                    "Collected (simulated bank response)");
            case CollectionInstruction.FAILED -> new Answer(CollectionInstruction.FAILED, "R01",
                    (reason == null || reason.isBlank() ? "Insufficient funds" : reason.trim())
                            + " (simulated bank response)");
            case CollectionInstruction.PENDING -> new Answer(CollectionInstruction.PENDING, "P1",
                    "No answer from the payer's bank yet (simulated)");
            default -> throw new IllegalArgumentException("A simulated outcome is CONFIRMED, FAILED or PENDING.");
        };
    }

    private void requireEnabled() {
        if (!gateways.simulatorsEnabled()) {
            throw new IllegalArgumentException("No debit-order or mobile-money collection provider is connected, and"
                    + " the simulator is off here; nothing was sent.");
        }
    }
}

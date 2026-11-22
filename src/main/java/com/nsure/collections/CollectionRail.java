package com.nsure.collections;

import java.util.List;
import java.util.Map;

/**
 * A rail that collects premiums the insurer pulls from the payer (NS-SH04-002): a bank debit-order service or a
 * mobile-money collection API. Submitting a file is not settlement; the rail's response says what was collected.
 * Only {@link SimulatedCollectionRail} ships until a provider is chosen (decision D12).
 */
public interface CollectionRail {

    /** Stored on the batch and on each settled transaction, e.g. DEBIT_ORDER_SIM. */
    String provider();

    /** Accepts an approved batch: the provider's file id and one provider reference per instruction. */
    Submission submit(CollectionBatch batch, List<CollectionInstruction> instructions);

    /**
     * The provider's answer for one submitted instruction. {@code requested} is a simulation override (CONFIRMED,
     * FAILED or PENDING) that only a simulated rail honours; a real rail ignores it.
     */
    Answer answer(CollectionInstruction instruction, String requested, String reason);

    record Submission(String fileRef, Map<String, String> references) {
    }

    /** {@code status} is CONFIRMED, FAILED or PENDING. */
    record Answer(String status, String code, String text) {
    }
}

package com.nsure.payment;

/**
 * What a benefit does when the payout rail answers for one of its instructions (claims, Care Cash events). The rail
 * never changes a benefit's state itself: it tells the owning module, in the same transaction as the instruction's own
 * change, so the instruction and the benefit can never disagree.
 */
public interface PayoutOutcomeHandler {

    /** The {@link PayoutInstruction#getSourceType()} this handler owns, e.g. CLAIM. */
    String sourceType();

    /** The bank confirmed the money reached the payee. */
    void settled(PayoutInstruction instruction, String actor);

    /** The bank refused or failed the payment before it settled; nothing was paid. */
    void failed(PayoutInstruction instruction, String reason, String actor);

    /** A settled payment came back (returned or recalled): the benefit is unpaid again and needs a new instruction. */
    void returned(PayoutInstruction instruction, String reason, String actor);
}

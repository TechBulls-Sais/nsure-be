package com.nsure.payment;

import com.nsure.common.exception.NotFoundException;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SIMULATED payout rail (decision D12 is open: no bank or mobile-money provider is selected). It accepts a payout
 * instruction and keeps its own book of what it was asked to pay, so the whole outgoing design — submission,
 * asynchronous callback, lost callback, status enquiry, rejection and return — can be shown end to end. It makes no
 * outbound call; a real rail implements the same three operations against the provider's API.
 */
@Component
public class SimulatedBankRail {

    public static final String PROVIDER = "SIMULATED_BANK";

    private final EntityManager em;

    public SimulatedBankRail(EntityManager em) {
        this.em = em;
    }

    public String provider() {
        return PROVIDER;
    }

    /** The bank accepts the instruction for processing and answers with its own reference. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String accept(PayoutInstruction instruction) {
        String reference = "SB-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                .toUpperCase(java.util.Locale.ROOT);
        em.persist(new PayoutRecords.BankPayment(reference, instruction.getRef(), instruction.getAmount(),
                instruction.getAccountRef()));
        return reference;
    }

    /** Status enquiry: what the bank holds for this reference, if anything. */
    public Optional<PayoutRecords.BankPayment> enquire(String providerReference) {
        return providerReference == null ? Optional.empty()
                : Optional.ofNullable(em.find(PayoutRecords.BankPayment.class, providerReference));
    }

    /** The simulated bank's own decision about a payment (the demo console decides it). */
    @Transactional(propagation = Propagation.MANDATORY)
    PayoutRecords.BankPayment decide(String providerReference, String status, String code, String text,
            String eventId) {
        PayoutRecords.BankPayment payment = enquire(providerReference)
                .orElseThrow(() -> new NotFoundException("The simulated bank has no payment " + providerReference));
        payment.decide(status, code, text, eventId);
        return payment;
    }
}

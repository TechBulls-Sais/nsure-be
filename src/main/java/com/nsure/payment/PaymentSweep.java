package com.nsure.payment;

import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Trigger 3 — the safety net (spec 13 §6.3): every 15 minutes, not hourly (S-20), settle every transaction the
 * browser return and the callback left open. Older than two minutes so the live paths win; one failing row never
 * stops the batch (S-21). Settlement itself is idempotent and row-locked, so a second instance running the sweep at
 * the same time is safe (S-11).
 */
@Component
public class PaymentSweep {

    private static final Logger log = LoggerFactory.getLogger(PaymentSweep.class);
    private static final List<String> OPEN = List.of(
            PaymentTransaction.CREATED, PaymentTransaction.REDIRECTED, PaymentTransaction.PENDING_VERIFICATION);

    private final PaymentTransactionRepository transactions;
    private final PaymentSettlementService settlement;

    public PaymentSweep(PaymentTransactionRepository transactions, PaymentSettlementService settlement) {
        this.transactions = transactions;
        this.settlement = settlement;
    }

    @Scheduled(cron = "${nsure.payments.sweep-cron:0 */15 * * * *}")
    public void scheduled() {
        sweep(LocalDateTime.now().minusMinutes(2));
    }

    /** Returns how many transactions reached a terminal state. */
    public int sweep(LocalDateTime createdBefore) {
        int settled = 0;
        for (PaymentTransaction tx : transactions.findUnsettled(OPEN, createdBefore, PageRequest.of(0, 200))) {
            try {
                if (tx.getProviderToken() == null) {
                    continue;
                }
                if (settlement.settle(tx.getProviderToken(), PaymentSettlementService.SWEEP).isTerminal()) {
                    settled++;
                }
            } catch (RuntimeException ex) {
                log.error("Payment sweep failed for transaction {}", tx.getId(), ex);
            }
        }
        return settled;
    }
}

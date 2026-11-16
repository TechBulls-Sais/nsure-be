package com.nsure.credit;

/**
 * A credit claim that cannot be settled from the balances held (AT-IL05-03). The loan has been flagged for manual
 * resolution, so the transaction that raises this still commits that flag.
 */
public class CreditManualResolutionException extends IllegalArgumentException {

    public CreditManualResolutionException(String message) {
        super(message);
    }
}

package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Wrong declaration codes per customer, and the payment lock they trigger (spec 01 BR-6). */
@Entity
@Table(name = "declaration_lock")
public class DeclarationLock {

    @Id
    @Column(name = "customer_ref", length = 20)
    private String customerRef;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    protected DeclarationLock() {
    }

    DeclarationLock(String customerRef) {
        this.customerRef = customerRef;
    }

    boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    /** Counts a wrong code; at {@code max} wrong codes, locks for {@code lockMinutes} and starts counting again. */
    boolean fail(int max, int lockMinutes) {
        failedAttempts++;
        if (failedAttempts >= max) {
            failedAttempts = 0;
            lockedUntil = LocalDateTime.now().plusMinutes(lockMinutes);
            return true;
        }
        return false;
    }

    void clear() {
        failedAttempts = 0;
    }

    int getFailedAttempts() {
        return failedAttempts;
    }

    LocalDateTime getLockedUntil() {
        return lockedUntil;
    }
}

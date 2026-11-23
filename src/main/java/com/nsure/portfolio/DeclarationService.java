package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.controls.ControlKey;
import com.nsure.controls.ControlService;
import com.nsure.security.exception.NoAuthorizationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Declaration and consent (spec 01 BR-5, BR-6; 06 §3.2):
 *
 * <ul>
 *   <li>the customer ticks the four consents (accuracy, terms, data protection, false-information acknowledgement)
 *       and enters a 6-digit code sent to them (valid 600 s);</li>
 *   <li>a correct code writes an immutable consent row for that purchase — evidence, not a flag;</li>
 *   <li>wrong codes count towards {@code declaration.otp_max_attempts}; reaching it locks payment for
 *       {@code declaration.otp_lock_minutes}. The lock is checked on sending a code, on verifying and on paying.</li>
 * </ul>
 */
@Service
public class DeclarationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int OTP_SECONDS = 600;

    private final EntityManager em;
    private final ControlService controls;
    private final WorkflowHistory history;
    private final boolean exposeCode;
    private final boolean demoCode;

    public DeclarationService(EntityManager em, ControlService controls, WorkflowHistory history,
            @Value("${nsure.declaration.expose-otp:true}") boolean exposeCode,
            org.springframework.core.env.Environment environment) {
        this.em = em;
        this.controls = controls;
        this.history = history;
        // A demo installation uses the same fixed code as sign-in and sign-up; production never does.
        this.demoCode = com.nsure.security.otp.OneTimeCodes.demoCodeIn(environment);
        this.exposeCode = exposeCode || demoCode;
    }

    @Transactional(readOnly = true)
    public LockState lockState(CustomerAccount account) {
        DeclarationLock lock = em.find(DeclarationLock.class, account.getRef());
        int max = maxAttempts();
        // The purchases this customer has already declared, so the portal does not ask again on another sign-in.
        List<String> declared = em.createQuery("select distinct d.policyRef from DeclarationConsent d"
                        + " where d.customerRef = :customer", String.class)
                .setParameter("customer", account.getRef())
                .getResultList();
        if (lock == null) {
            return new LockState(false, null, max, declared);
        }
        return new LockState(lock.isLocked(), lock.isLocked() ? lock.getLockedUntil() : null,
                lock.isLocked() ? 0 : max - lock.getFailedAttempts(), declared);
    }

    /**
     * Sends a code for the declaration on {@code policy}. There is no SMS/e-mail provider yet, so outside production
     * ({@code nsure.declaration.expose-otp}) the code is also returned for the simulated delivery.
     */
    @Transactional
    public OtpSent sendOtp(CustomerAccount account, InsurancePolicy policy) {
        assertNotLocked(account);
        if (!PolicyWorkflow.PENDING_PAYMENT.equals(policy.getStatus())) {
            throw new IllegalArgumentException("Policy " + policy.getRef() + " is " + policy.getStatus()
                    + "; the declaration is made once, before the first premium.");
        }
        for (DeclarationOtp open : openCodes(account, policy)) {
            open.use();
        }
        String code = demoCode ? com.nsure.security.otp.OneTimeCodes.DEMO_CODE
                : String.format("%06d", RANDOM.nextInt(1_000_000));
        LocalDateTime expires = LocalDateTime.now().plusSeconds(OTP_SECONDS);
        em.persist(new DeclarationOtp(account.getRef(), policy.getRef(), hash(code), expires));
        return new OtpSent(mask(account.getEmail()), expires, exposeCode ? code : null);
    }

    /** {code, accuracy, terms, dataProtection, falseInformation}: all four consents must be given. */
    @Transactional(noRollbackFor = {IllegalArgumentException.class, NoAuthorizationException.class})
    public DeclarationConsent verify(CustomerAccount account, InsurancePolicy policy, JsonNode body) {
        assertNotLocked(account);
        List<String> missing = new ArrayList<>();
        if (!body.path("accuracy").asBoolean(false)) {
            missing.add("that the information is accurate");
        }
        if (!body.path("terms").asBoolean(false)) {
            missing.add("the terms and conditions");
        }
        if (!body.path("dataProtection").asBoolean(false)) {
            missing.add("the data protection notice");
        }
        if (!body.path("falseInformation").asBoolean(false)) {
            missing.add("that false information may void the policy");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Please confirm " + String.join(", ", missing) + ".");
        }
        String code = body.path("code").asText("").trim();
        DeclarationOtp otp = openCodes(account, policy).stream().findFirst().orElse(null);
        if (otp == null) {
            throw new IllegalArgumentException("Your code has expired or was not sent. Send a new code.");
        }
        DeclarationLock lock = em.find(DeclarationLock.class, account.getRef(), LockModeType.PESSIMISTIC_WRITE);
        if (lock == null) {
            lock = new DeclarationLock(account.getRef());
            em.persist(lock);
        }
        if (!hash(code).equals(otp.getCodeHash())) {
            int max = maxAttempts();
            if (lock.fail(max, lockMinutes())) {
                otp.use();
                history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Declaration Locked",
                        policy.getStatus(), policy.getStatus(), max + " wrong declaration codes: payment locked until "
                                + lock.getLockedUntil().withNano(0), account.getLoginUsername());
                throw new NoAuthorizationException("Too many wrong codes. For your security, payment is locked for "
                        + lockMinutes() + " minutes.");
            }
            throw new IllegalArgumentException("That code is not right. You have " + (max - lock.getFailedAttempts())
                    + " attempt(s) left.");
        }
        otp.use();
        lock.clear();
        DeclarationConsent consent = new DeclarationConsent(account.getRef(), policy.getRef(), account.getLoginUsername());
        em.persist(consent);
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Declaration Signed",
                policy.getStatus(), policy.getStatus(), "OTP-verified declaration with all four consents",
                account.getLoginUsername());
        return consent;
    }

    /**
     * Before a customer pays: not locked (BR-6), and — for the first premium — an OTP-verified declaration exists for
     * this purchase (BR-5).
     */
    @Transactional(readOnly = true, noRollbackFor = {IllegalArgumentException.class, NoAuthorizationException.class})
    public void assertDeclaredForPayment(CustomerAccount account, InsurancePolicy policy) {
        assertNotLocked(account);
        if (!PolicyWorkflow.PENDING_PAYMENT.equals(policy.getStatus())) {
            return;
        }
        Long consents = em.createQuery("select count(d) from DeclarationConsent d where d.policyRef = :policy"
                        + " and d.customerRef = :customer", Long.class)
                .setParameter("policy", policy.getRef())
                .setParameter("customer", account.getRef())
                .getSingleResult();
        if (consents == 0) {
            throw new IllegalArgumentException("Complete the declaration and consent step before paying for "
                    + policy.getRef() + ".");
        }
    }

    @Transactional(readOnly = true)
    public List<DeclarationConsent> forPolicy(String policyRef) {
        return em.createQuery("select d from DeclarationConsent d where d.policyRef = :policy order by d.id desc",
                        DeclarationConsent.class)
                .setParameter("policy", policyRef)
                .getResultList();
    }

    private void assertNotLocked(CustomerAccount account) {
        DeclarationLock lock = em.find(DeclarationLock.class, account.getRef());
        if (lock != null && lock.isLocked()) {
            throw new NoAuthorizationException("Payment is locked after too many wrong declaration codes. Try again after "
                    + lock.getLockedUntil().withNano(0) + ".");
        }
    }

    private List<DeclarationOtp> openCodes(CustomerAccount account, InsurancePolicy policy) {
        return em.createQuery("select o from DeclarationOtp o where o.customerRef = :customer and o.policyRef = :policy"
                        + " and o.usedAt is null order by o.id desc", DeclarationOtp.class)
                .setParameter("customer", account.getRef())
                .setParameter("policy", policy.getRef())
                .getResultStream()
                .filter(DeclarationOtp::usable)
                .toList();
    }

    private int maxAttempts() {
        return controls.valueOf(ControlKey.DECLARATION_OTP_MAX_ATTEMPTS.key()).intValue();
    }

    private int lockMinutes() {
        return controls.valueOf(ControlKey.DECLARATION_OTP_LOCK_MINUTES.key()).intValue();
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String mask(String email) {
        if (email == null || !email.contains("@")) {
            return "your registered contact";
        }
        String[] parts = email.split("@", 2);
        return parts[0].charAt(0) + "***@" + parts[1];
    }

    public record LockState(boolean locked, LocalDateTime lockedUntil, int attemptsLeft, List<String> declaredPolicies) {
    }

    /** {@code devCode} is set only outside production, standing in for the SMS/e-mail delivery. */
    public record OtpSent(String sentTo, LocalDateTime expiresAt, String devCode) {
    }
}

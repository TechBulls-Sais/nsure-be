package com.nsure.security.otp;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time codes for customer sign-up and for the second factor of staff and partner sign-in. Each request gets its
 * own challenge, valid for {@code nsure.one-time-codes.ttl-minutes} and {@code nsure.one-time-codes.max-attempts}
 * answers; a right answer is accepted once.
 *
 * <p>Where the code comes from:
 * <ul>
 *   <li><b>dev or demo profile</b> (and not {@code prod}): the published demo code {@value #DEMO_CODE}, returned with
 *       the challenge so the screen can show it. No messaging provider is involved.</li>
 *   <li><b>everywhere else</b>: a random code, sent through the configured {@link OneTimeCodeSender}. It is never the
 *       demo code. With no provider, or nobody to send to, the step is refused: the demo code is never accepted.</li>
 * </ul>
 */
@Service
public class OneTimeCodes {

    public enum Purpose {
        STAFF_SIGN_IN,
        CUSTOMER_SIGNUP,
        /** NS-SH01-005: proves a new email or phone belongs to the customer before the change is approved. */
        CONTACT_CHANGE,
        /** NS-SH11-001: account recovery, sent to the customer's verified mobile. */
        ACCOUNT_RECOVERY
    }

    /** The demonstration code. Accepted only while a dev or demo profile is active and prod is not. */
    public static final String DEMO_CODE = "123456";

    static final Profiles DEMO_CODE_PROFILES = Profiles.of("(dev | demo) & !prod");

    /** A challenge to answer. {@code demoCode} is set only in a dev or demo installation. */
    public record Issued(String challenge, LocalDateTime expiresAt, int attemptsAllowed, String demoCode) {
    }

    private static final Logger log = LoggerFactory.getLogger(OneTimeCodes.class);
    private static final String GONE = "This code has expired or was already used. Request a new code.";

    private final EntityManager em;
    private final ObjectProvider<OneTimeCodeSender> senders;
    private final boolean demoCode;
    private final Duration ttl;
    private final int maxAttempts;
    private final SecureRandom random = new SecureRandom();

    public OneTimeCodes(EntityManager em, ObjectProvider<OneTimeCodeSender> senders, Environment environment,
            @Value("${nsure.one-time-codes.ttl-minutes:5}") long ttlMinutes,
            @Value("${nsure.one-time-codes.max-attempts:5}") int maxAttempts) {
        this.em = em;
        this.senders = senders;
        this.demoCode = environment.acceptsProfiles(DEMO_CODE_PROFILES);
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.maxAttempts = maxAttempts;
        if (demoCode) {
            log.warn("One-time codes: the demo code {} is accepted (dev/demo profile). Not for real customers.",
                    DEMO_CODE);
        } else if (senders.getIfUnique() == null) {
            log.warn("One-time codes: no messaging provider (OneTimeCodeSender) is configured, so customer sign-up"
                    + " and staff/partner sign-in are refused.");
        }
    }

    /** True in a dev or demo installation, where the fixed demo code stands in for a delivered one. */
    public boolean demoCodeAccepted() {
        return demoCode;
    }

    /**
     * Opens a challenge for {@code subject} and delivers its code to {@code destination} (ignored in a demo
     * installation). Throws {@link CodeDeliveryUnavailableException} when the code cannot be delivered.
     */
    @Transactional
    public Issued issue(Purpose purpose, String subject, String destination) {
        String code;
        if (demoCode) {
            code = DEMO_CODE;
        } else {
            OneTimeCodeSender sender = senders.getIfUnique();
            if (sender == null) {
                throw new CodeDeliveryUnavailableException(purpose == Purpose.CUSTOMER_SIGNUP
                        ? "Online sign-up is not available yet: no SMS or e-mail provider is connected. Please contact"
                                + " us to open an account."
                        : purpose == Purpose.CONTACT_CHANGE || purpose == Purpose.ACCOUNT_RECOVERY
                        ? "No SMS or e-mail provider is connected to send the verification code."
                        : "Sign-in needs a one-time code, and no SMS or e-mail provider is connected to send it. Ask"
                                + " your administrator.");
            }
            if (destination == null || destination.isBlank()) {
                throw new CodeDeliveryUnavailableException(purpose == Purpose.CUSTOMER_SIGNUP
                        ? "Enter a phone number to receive your code."
                        : "Your account has no phone number or e-mail address for sign-in codes. Ask your"
                                + " administrator.");
            }
            code = randomCode();
            sender.send(purpose, destination.trim(), code);
        }
        LocalDateTime now = LocalDateTime.now();
        // Spent codes are kept a day for investigation, then dropped.
        em.createQuery("delete from OneTimeCode o where o.expiresAt < :cutoff")
                .setParameter("cutoff", now.minusDays(1))
                .executeUpdate();
        String challenge = token();
        LocalDateTime expires = now.plus(ttl);
        em.persist(new OneTimeCode(purpose.name(), sha256(challenge), subject, codeHash(challenge, code), now, expires,
                maxAttempts));
        return new Issued(challenge, expires, maxAttempts, demoCode ? DEMO_CODE : null);
    }

    /**
     * Checks {@code code} against the challenge and returns the subject it was issued for. When
     * {@code expectedSubject} is given, a challenge issued for anyone else is refused without using an attempt.
     *
     * <p>Runs in its own transaction, so a wrong answer is counted even though the caller's request fails; the last
     * allowed wrong answer cancels the code.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = InvalidCodeException.class)
    public String verify(Purpose purpose, String challenge, String code, String expectedSubject) {
        if (challenge == null || challenge.isBlank() || challenge.length() > 200) {
            throw new InvalidCodeException(GONE);
        }
        OneTimeCode row = em.createQuery("select o from OneTimeCode o where o.challengeHash = :hash", OneTimeCode.class)
                .setParameter("hash", sha256(challenge.trim()))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (row == null || !purpose.name().equals(row.getPurpose()) || !row.usable(now)) {
            throw new InvalidCodeException(GONE);
        }
        if (expectedSubject != null && !expectedSubject.equals(row.getSubject())) {
            throw new InvalidCodeException("This code was sent for different details. Request a new code.");
        }
        String answer = code == null ? "" : code.replaceAll("\\s", "");
        if (!MessageDigest.isEqual(codeHash(challenge.trim(), answer).getBytes(StandardCharsets.UTF_8),
                row.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            row.failedAttempt(now);
            int left = row.getMaxAttempts() - row.getAttempts();
            throw new InvalidCodeException(left > 0
                    ? "That code is not right. You have " + left + " attempt(s) left."
                    : "That code is not right, and it has been cancelled after too many attempts. Request a new code.");
        }
        row.use(now);
        return row.getSubject();
    }

    private String randomCode() {
        String code;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
        } while (DEMO_CODE.equals(code));
        return code;
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Salted with the challenge token, which is stored only as a hash. */
    private static String codeHash(String challenge, String code) {
        return sha256(challenge + ":" + code);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

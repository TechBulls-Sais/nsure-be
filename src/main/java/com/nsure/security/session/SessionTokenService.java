package com.nsure.security.session;

import com.nsure.security.domain.AppUser;
import com.nsure.security.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Browser sessions (NS-SH11-001): the password is sent once to open a session, and the browser then holds only an
 * opaque random token. A session ends after {@code nsure.auth.session-idle-minutes} without use, at
 * {@code nsure.auth.session-max-hours} after sign-in, on sign-out, when the user is disabled, or when their password
 * changes.
 */
@Service
public class SessionTokenService {

    /** What the browser receives when it signs in. */
    public record Issued(String token, LocalDateTime expiresAt, long idleMinutes) {
    }

    // Writing last-seen on every call would turn each read into a write; a minute's precision is enough for idle time.
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    private final EntityManager em;
    private final AppUserRepository users;
    private final Duration idle;
    private final Duration max;
    private final SecureRandom random = new SecureRandom();

    public SessionTokenService(EntityManager em, AppUserRepository users,
            @Value("${nsure.auth.session-idle-minutes:30}") long idleMinutes,
            @Value("${nsure.auth.session-max-hours:12}") long maxHours) {
        this.em = em;
        this.users = users;
        this.idle = Duration.ofMinutes(idleMinutes);
        this.max = Duration.ofHours(maxHours);
    }

    @Transactional
    public Issued issue(AppUser user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plus(max);
        em.persist(new AuthSession(sha256(token), user.getId(), stamp(user), now, expires));
        return new Issued(token, expires, idle.toMinutes());
    }

    /** The signed-in user for a live token, or empty when the session has ended for any reason. */
    @Transactional
    public Optional<AppUser> authenticate(String token) {
        AuthSession session = find(token);
        LocalDateTime now = LocalDateTime.now();
        if (session == null || session.getRevokedAt() != null || !now.isBefore(session.getExpiresAt())
                || !now.isBefore(session.getLastSeenAt().plus(idle))) {
            return Optional.empty();
        }
        AppUser user = users.findById(session.getUserId()).orElse(null);
        if (user == null || !user.isEnabled() || !stamp(user).equals(session.getCredentialStamp())) {
            session.revoke(now);
            return Optional.empty();
        }
        // Resolve roles and grants now: Spring Security reads the authorities after this transaction ends.
        user.getAuthorities();
        if (Duration.between(session.getLastSeenAt(), now).compareTo(TOUCH_EVERY) >= 0) {
            session.touch(now);
        }
        return Optional.of(user);
    }

    /** Ends the session the token belongs to; an unknown or ended token is ignored. */
    @Transactional
    public void revoke(String token) {
        AuthSession session = find(token);
        if (session != null) {
            session.revoke(LocalDateTime.now());
        }
    }

    /** Removes every session of a user who is being deleted, ended or not (sign-ins stay in the evidence chain). */
    @Transactional
    public void forget(AppUser user) {
        em.createQuery("delete from AuthSession s where s.userId = :user")
                .setParameter("user", user.getId())
                .executeUpdate();
    }

    private AuthSession find(String token) {
        if (token == null || token.isBlank() || token.length() > 200) {
            return null;
        }
        return em.createQuery("select s from AuthSession s where s.tokenHash = :hash", AuthSession.class)
                .setParameter("hash", sha256(token.trim()))
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private static String stamp(AppUser user) {
        return sha256("credential:" + (user.getPassword() == null ? "" : user.getPassword()));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

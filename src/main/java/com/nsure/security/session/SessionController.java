package com.nsure.security.session;

import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.UnAuthenticatedUserException;
import com.nsure.security.otp.OneTimeCodes;
import com.nsure.security.repository.AppUserRepository;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opens a browser session and ends it on sign-out (NS-SH11-001).
 *
 * <p>Customers open a session with their username and password. Staff and partners (anyone holding a role with desk
 * access) prove a second factor first: the password only answers with a challenge, and the session token is issued
 * by {@code POST /auth/session/verify} once its one-time code is right. Their password works nowhere else
 * ({@link SecondFactorRequiredFilter}), so no API client can skip the code.
 */
@RestController
@RequestMapping("/auth/session")
public class SessionController {

    public record SessionView(String token, String username, LocalDateTime expiresAt, long idleMinutes) {
    }

    /** The password was right; send the one-time code with this challenge to {@code /auth/session/verify}. */
    public record ChallengeView(boolean secondFactorRequired, String username, String challenge,
            LocalDateTime expiresAt, int attemptsAllowed, String demoCode) {
    }

    public record VerifyRequest(String challenge, String code) {
    }

    private final SessionTokenService sessions;
    private final PlatformSecurityContext security;
    private final com.nsure.audit.evidence.EvidenceChain evidence;
    private final OneTimeCodes codes;
    private final AppUserRepository users;

    public SessionController(SessionTokenService sessions, PlatformSecurityContext security,
            com.nsure.audit.evidence.EvidenceChain evidence, OneTimeCodes codes, AppUserRepository users) {
        this.evidence = evidence;
        this.sessions = sessions;
        this.security = security;
        this.codes = codes;
        this.users = users;
    }

    /**
     * Sign in with HTTP Basic. A customer gets the session token at once; staff and partners get a challenge for
     * their one-time code and no token.
     */
    @PostMapping
    public Object open(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        // A session is opened by the password only: a token cannot mint new tokens and outlive its own expiry.
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw new IllegalArgumentException("Sign in with your username and password to open a session.");
        }
        AppUser user = security.authenticatedUser();
        if (requiresSecondFactor(user)) {
            // Staff accounts carry no phone or e-mail yet, so outside a demo installation this is refused.
            OneTimeCodes.Issued issued = codes.issue(OneTimeCodes.Purpose.STAFF_SIGN_IN, user.getUsername(), null);
            evidence.recordNow(user.getUsername(), "Sign-in code requested", "User", user.getUsername(),
                    "Password accepted; one-time code valid until " + issued.expiresAt().withNano(0));
            return new ChallengeView(true, user.getUsername(), issued.challenge(), issued.expiresAt(),
                    issued.attemptsAllowed(), issued.demoCode());
        }
        return issue(user);
    }

    /** The second sign-in step for staff and partners: the right code for a live challenge opens the session. */
    @PostMapping("/verify")
    public SessionView verify(@RequestBody VerifyRequest body) {
        String username = codes.verify(OneTimeCodes.Purpose.STAFF_SIGN_IN, body == null ? null : body.challenge(),
                body == null ? null : body.code(), null);
        // The account may have been disabled since the password was checked.
        AppUser user = users.findByUsername(username).filter(AppUser::isEnabled)
                .orElseThrow(UnAuthenticatedUserException::new);
        return issue(user);
    }

    /** Sign out: the token stops working at once. */
    @DeleteMapping
    public ResponseEntity<Void> close(HttpServletRequest request) {
        Object token = request.getAttribute(BearerTokenFilter.TOKEN_ATTRIBUTE);
        if (token instanceof String value) {
            sessions.revoke(value);
            evidence.recordNow(null, "Signed out", "User", security.authenticatedUser().getUsername(), null);
        }
        return ResponseEntity.noContent().build();
    }

    /** Staff and partner logins (a role with desk access) need the second factor; portal customers do not. */
    static boolean requiresSecondFactor(AppUser user) {
        return user.isStaffUser();
    }

    private SessionView issue(AppUser user) {
        SessionTokenService.Issued issued = sessions.issue(user);
        evidence.recordNow(user.getUsername(), "Signed in", "User", user.getUsername(),
                "Session until " + issued.expiresAt().withNano(0));
        return new SessionView(issued.token(), user.getUsername(), issued.expiresAt(), issued.idleMinutes());
    }
}

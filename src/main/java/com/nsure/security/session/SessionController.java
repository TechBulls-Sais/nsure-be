package com.nsure.security.session;

import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Opens a browser session from a username and password, and ends it on sign-out (NS-SH11-001). */
@RestController
@RequestMapping("/auth/session")
public class SessionController {

    public record SessionView(String token, String username, LocalDateTime expiresAt, long idleMinutes) {
    }

    private final SessionTokenService sessions;
    private final PlatformSecurityContext security;
    private final com.nsure.audit.evidence.EvidenceChain evidence;

    public SessionController(SessionTokenService sessions, PlatformSecurityContext security,
            com.nsure.audit.evidence.EvidenceChain evidence) {
        this.evidence = evidence;
        this.sessions = sessions;
        this.security = security;
    }

    /** Sign in with HTTP Basic; the answer carries the token the browser uses from now on. */
    @PostMapping
    public SessionView open(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        // A session is opened by the password only: a token cannot mint new tokens and outlive its own expiry.
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw new IllegalArgumentException("Sign in with your username and password to open a session.");
        }
        AppUser user = security.authenticatedUser();
        SessionTokenService.Issued issued = sessions.issue(user);
        evidence.recordNow(user.getUsername(), "Signed in", "User", user.getUsername(),
                "Session until " + issued.expiresAt().withNano(0));
        return new SessionView(issued.token(), user.getUsername(), issued.expiresAt(), issued.idleMinutes());
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
}

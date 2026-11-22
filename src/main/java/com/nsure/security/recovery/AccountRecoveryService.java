package com.nsure.security.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.security.domain.AppUser;
import com.nsure.security.otp.InvalidCodeException;
import com.nsure.security.otp.OneTimeCodes;
import com.nsure.security.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer account recovery (NS-SH11-001): a forgotten password is reset with a one-time code sent to the mobile
 * number the customer verified at onboarding. The answer never says whether an account exists. Staff and partner
 * logins are not recovered here: an administrator resets them through the four-eyes user change. A reset ends every
 * open session of the account (the credential stamp changes).
 */
@Service
public class AccountRecoveryService {

    private final EntityManager em;
    private final AppUserRepository users;
    private final OneTimeCodes codes;
    private final PasswordEncoder passwords;
    private final EvidenceChain audit;

    public AccountRecoveryService(EntityManager em, AppUserRepository users, OneTimeCodes codes,
            PasswordEncoder passwords, EvidenceChain audit) {
        this.em = em;
        this.users = users;
        this.codes = codes;
        this.passwords = passwords;
        this.audit = audit;
    }

    /** {username}: sends a recovery code to the customer's verified mobile. The same answer for an unknown login. */
    @Transactional
    public Map<String, Object> requestCode(JsonNode body) {
        String username = text(body, "username");
        if (username == null) {
            throw new IllegalArgumentException("Enter your username.");
        }
        String login = username.trim().toLowerCase(Locale.ROOT);
        AppUser user = users.findByUsername(login).orElse(null);
        String phone = null;
        if (user != null && user.isEnabled() && !user.isStaffUser()) {
            List<String> phones = em.createQuery("select c.phone from CustomerAccount c where c.loginUsername = :login",
                    String.class).setParameter("login", login).getResultList();
            phone = phones.isEmpty() ? null : phones.get(0);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sentTo", "the mobile number registered on the account, if there is one");
        if (phone == null) {
            // An unknown, disabled or staff login gets a challenge that can never reset anything; nothing is sent.
            out.put("challenge", java.util.UUID.randomUUID().toString().replace("-", ""));
            out.put("expiresAt", String.valueOf(java.time.LocalDateTime.now().plusMinutes(5)));
            if (codes.demoCodeAccepted()) {
                out.put("demoCode", OneTimeCodes.DEMO_CODE);
            }
            return out;
        }
        OneTimeCodes.Issued issued = codes.issue(OneTimeCodes.Purpose.ACCOUNT_RECOVERY, login, phone);
        out.put("challenge", issued.challenge());
        out.put("expiresAt", String.valueOf(issued.expiresAt()));
        if (issued.demoCode() != null) {
            out.put("demoCode", issued.demoCode());
        }
        return out;
    }

    /** {challenge, code, password}: sets the new password; every open session of the account ends. */
    @Transactional
    public Map<String, Object> reset(JsonNode body) {
        String password = text(body, "password");
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("The new password must be at least 8 characters.");
        }
        String subject = codes.verify(OneTimeCodes.Purpose.ACCOUNT_RECOVERY, text(body, "challenge"), text(body, "code"),
                null);
        AppUser user = users.findByUsername(subject).orElse(null);
        if (user == null || !user.isEnabled() || user.isStaffUser()) {
            throw new InvalidCodeException("This code cannot reset a password. Request a new code.");
        }
        user.setPassword(passwords.encode(password));
        users.save(user);
        audit.record(user.getUsername(), "Password reset with a recovery code", "User", user.getUsername(),
                "Sent to the registered mobile; open sessions ended");
        return Map.of("username", user.getUsername(), "status", "PASSWORD_RESET");
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        String value = body.get(field).asText().trim();
        return value.isEmpty() ? null : value;
    }
}

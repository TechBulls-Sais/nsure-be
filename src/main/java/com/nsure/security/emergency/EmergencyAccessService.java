package com.nsure.security.emergency;

import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.StaffAlertService;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.EmergencyAccessGrant;
import com.nsure.security.domain.PermissionCatalog;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.AppUserRepository;
import com.nsure.security.repository.EmergencyAccessGrantRepository;
import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emergency access (Nsure Life NS-SH11-003): "time-limited, justified, alerted and reviewed".
 *
 * <ul>
 *   <li>Only a break-glass code ({@link PermissionCatalog#BREAK_GLASS}) can be granted this way; no role holds them.</li>
 *   <li>Time-limited: 1 to {@value #MAX_HOURS} hours, then it lapses on its own. It can be revoked earlier.</li>
 *   <li>Justified: a written reason is required.</li>
 *   <li>Alerted: every holder of READ_AUDIT sees an alert when it is granted or revoked.</li>
 *   <li>Reviewed: after it ends, someone other than the granter and the grantee records a review.</li>
 *   <li>Nobody grants emergency access to themselves, and only staff can receive it.</li>
 * </ul>
 */
@Service
public class EmergencyAccessService {

    public static final int MAX_HOURS = 8;
    public static final String GRANT_CODE = "GRANT_EMERGENCY_ACCESS";
    public static final String REVIEW_CODE = "REVIEW_EMERGENCY_ACCESS";
    static final String ALERT_PERMISSION = "READ_AUDIT";

    private final EmergencyAccessGrantRepository grants;
    private final AppUserRepository users;
    private final PlatformSecurityContext security;
    private final StaffAlertService alerts;

    public EmergencyAccessService(EmergencyAccessGrantRepository grants, AppUserRepository users,
            PlatformSecurityContext security, StaffAlertService alerts) {
        this.grants = grants;
        this.users = users;
        this.security = security;
        this.alerts = alerts;
    }

    @Transactional(readOnly = true)
    public List<GrantView> list() {
        security.authenticatedUser().validateHasAnyPermission(GRANT_CODE, REVIEW_CODE, ALERT_PERMISSION);
        return grants.findTop200ByOrderByGrantedAtDescIdDesc().stream().map(GrantView::of).toList();
    }

    @Transactional
    public GrantView grant(String username, String permission, String reason, Integer hours) {
        AppUser granter = security.authenticatedUser();
        granter.validateHasPermissionTo(GRANT_CODE);
        String code = breakGlassCode(permission);
        String why = required(reason, "A reason", 10, 1000);
        if (hours == null || hours < 1 || hours > MAX_HOURS) {
            throw new IllegalArgumentException("Emergency access lasts 1 to " + MAX_HOURS + " hours.");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Choose the user who needs emergency access.");
        }
        AppUser grantee = users.findByUsername(username.trim())
                .orElseThrow(() -> new NotFoundException("User " + username.trim() + " not found"));
        if (grantee.getId().equals(granter.getId())) {
            throw new NoAuthorizationException("You cannot grant emergency access to yourself; another administrator"
                    + " must grant it.");
        }
        if (!grantee.isEnabled() || !grantee.isStaffUser()) {
            throw new IllegalArgumentException(grantee.getUsername()
                    + " is not an enabled staff user, so it cannot receive emergency access.");
        }
        boolean alreadyActive = grants.findByUserIdAndPermissionCodeAndRevokedAtIsNull(grantee.getId(), code)
                .stream().anyMatch(EmergencyAccessGrant::isActive);
        if (alreadyActive) {
            throw new ConflictException(grantee.getUsername() + " already has active emergency access to " + code + ".");
        }
        EmergencyAccessGrant grant = grants.save(new EmergencyAccessGrant(grantee, code, why,
                granter.getUsername(), LocalDateTime.now().plusHours(hours)));
        alerts.raise("EMERGENCY_ACCESS_GRANTED", ALERT_PERMISSION,
                "Emergency access granted: " + code,
                granter.getUsername() + " gave " + grantee.getUsername() + " " + code + " for " + hours
                        + " hour(s). Reason: " + why,
                "EMERGENCY_ACCESS_GRANTED:" + grant.getId(), granter.getUsername());
        return GrantView.of(grant);
    }

    @Transactional
    public GrantView revoke(Long id) {
        AppUser caller = security.authenticatedUser();
        caller.validateHasPermissionTo(GRANT_CODE);
        EmergencyAccessGrant grant = find(id);
        if (!grant.isActive()) {
            throw new ConflictException("Emergency access " + id + " is already " + grant.status().toLowerCase(Locale.ROOT)
                    + ".");
        }
        grant.revoke(caller.getUsername());
        alerts.raise("EMERGENCY_ACCESS_REVOKED", ALERT_PERMISSION,
                "Emergency access revoked: " + grant.getPermissionCode(),
                caller.getUsername() + " revoked " + grant.getUser().getUsername() + "'s "
                        + grant.getPermissionCode() + " before it expired.",
                "EMERGENCY_ACCESS_REVOKED:" + grant.getId(), caller.getUsername());
        return GrantView.of(grant);
    }

    @Transactional
    public GrantView review(Long id, String note) {
        AppUser reviewer = security.authenticatedUser();
        reviewer.validateHasPermissionTo(REVIEW_CODE);
        EmergencyAccessGrant grant = find(id);
        if (grant.isActive()) {
            throw new ConflictException("Emergency access " + id + " is still active. Review it once it has expired"
                    + " or been revoked.");
        }
        if (grant.getReviewedAt() != null) {
            throw new ConflictException("Emergency access " + id + " was already reviewed by " + grant.getReviewedBy()
                    + ".");
        }
        if (reviewer.getUsername().equalsIgnoreCase(grant.getGrantedBy())
                || reviewer.getId().equals(grant.getUser().getId())) {
            throw new NoAuthorizationException("The review must be done by someone other than the person who granted"
                    + " or used the emergency access.");
        }
        grant.review(reviewer.getUsername(), required(note, "A review note", 5, 1000));
        return GrantView.of(grant);
    }

    private EmergencyAccessGrant find(Long id) {
        return grants.findById(id).orElseThrow(() -> new NotFoundException("Emergency access " + id + " not found"));
    }

    private static String breakGlassCode(String permission) {
        String code = permission == null ? "" : permission.trim().toUpperCase(Locale.ROOT);
        if (!PermissionCatalog.BREAK_GLASS.contains(code)) {
            throw new IllegalArgumentException("Emergency access covers only " + String.join(", ",
                    PermissionCatalog.BREAK_GLASS.stream().sorted().toList()) + ".");
        }
        return code;
    }

    private static String required(String value, String label, int min, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() < min) {
            throw new IllegalArgumentException(label + " of at least " + min + " characters is required.");
        }
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(label + " must be at most " + max + " characters.");
        }
        return trimmed;
    }

    public record GrantView(
            Long id,
            String username,
            String permission,
            String reason,
            String grantedBy,
            LocalDateTime grantedAt,
            LocalDateTime expiresAt,
            String revokedBy,
            LocalDateTime revokedAt,
            String reviewedBy,
            LocalDateTime reviewedAt,
            String reviewNote,
            String status) {

        static GrantView of(EmergencyAccessGrant grant) {
            return new GrantView(grant.getId(), grant.getUser().getUsername(), grant.getPermissionCode(),
                    grant.getReason(), grant.getGrantedBy(), grant.getGrantedAt(), grant.getExpiresAt(),
                    grant.getRevokedBy(), grant.getRevokedAt(), grant.getReviewedBy(), grant.getReviewedAt(),
                    grant.getReviewNote(), grant.status());
        }
    }
}

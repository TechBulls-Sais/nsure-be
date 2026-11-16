package com.nsure.security.approval;

import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Role;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.RoleRepository;
import com.nsure.security.service.PlatformSecurityContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim approval authority limits (spec 04 §5.3):
 *
 * <ul>
 *   <li>a role with no row is unlimited;</li>
 *   <li>a user holding several approving roles gets the highest limit among them;</li>
 *   <li>no role is exempt by name: the administrator role is unlimited only because it has no row (Nsure Life
 *       NS-SH11-002, deny by default);</li>
 *   <li>a protected role cannot be given a limit (A-1), and a limit must be above zero.</li>
 * </ul>
 *
 * The limit governs which person may approve; the STP ceiling governs whether a person is needed at all
 * (spec 12 C-14). The two are kept separate.
 */
@Service
public class ApprovalAuthorityService {

    private final ClaimApprovalAuthorityRepository authorities;
    private final RoleRepository roles;
    private final PlatformSecurityContext security;

    public ApprovalAuthorityService(
            ClaimApprovalAuthorityRepository authorities, RoleRepository roles, PlatformSecurityContext security) {
        this.authorities = authorities;
        this.roles = roles;
        this.security = security;
    }

    /** The user's approval limit, or empty when unbounded. */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> limitFor(AppUser user) {
        Map<Long, BigDecimal> limits = authorities.findAll().stream()
                .collect(Collectors.toMap(a -> a.getRole().getId(), ClaimApprovalAuthority::getMaxPayoutAmount));
        BigDecimal highest = null;
        for (Role role : user.getRoles()) {
            if (role.isDisabled() || !approves(role)) {
                continue;
            }
            BigDecimal limit = limits.get(role.getId());
            if (limit == null) {
                return Optional.empty();
            }
            highest = highest == null || limit.compareTo(highest) > 0 ? limit : highest;
        }
        return Optional.ofNullable(highest);
    }

    /** Refuses a payout above the approver's limit, with the spec's wording. */
    @Transactional(readOnly = true)
    public void assertWithinLimit(AppUser approver, BigDecimal payout) {
        limitFor(approver).ifPresent(limit -> {
            if (payout.compareTo(limit) > 0) {
                throw new NoAuthorizationException("This payout of P " + money(payout)
                        + " exceeds your approval limit of P " + money(limit) + ". It needs a more senior approver.");
            }
        });
    }

    @Transactional(readOnly = true)
    public List<AuthorityView> list() {
        security.authenticatedUser().validateHasPermissionTo("READ_ROLE");
        Map<Long, ClaimApprovalAuthority> byRole = authorities.findAll().stream()
                .collect(Collectors.toMap(a -> a.getRole().getId(), Function.identity()));
        return roles.findAll().stream()
                .filter(role -> !role.isProtectedRole())
                .sorted(Comparator.comparing(Role::getName))
                .map(role -> {
                    ClaimApprovalAuthority row = byRole.get(role.getId());
                    return new AuthorityView(role.getId(), role.getName(), approves(role),
                            row == null ? null : row.getMaxPayoutAmount());
                })
                .toList();
    }

    @Transactional
    public AuthorityView set(Long roleId, BigDecimal maxPayoutAmount) {
        security.authenticatedUser().validateHasPermissionTo("UPDATE_ROLE");
        Role role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("Role not found"));
        if (role.isProtectedRole()) {
            throw new IllegalArgumentException(
                    role.getName() + " is a protected role and cannot have an approval limit.");
        }
        if (maxPayoutAmount == null || maxPayoutAmount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "An approval limit must be greater than zero. Delete the row to remove the limit.");
        }
        BigDecimal amount = maxPayoutAmount.setScale(2, RoundingMode.HALF_UP);
        ClaimApprovalAuthority row = authorities.findByRoleId(roleId).orElse(null);
        if (row == null) {
            row = new ClaimApprovalAuthority(role, amount);
        } else {
            row.setMaxPayoutAmount(amount);
        }
        authorities.save(row);
        return new AuthorityView(role.getId(), role.getName(), approves(role), amount);
    }

    @Transactional
    public void remove(Long roleId) {
        security.authenticatedUser().validateHasPermissionTo("UPDATE_ROLE");
        authorities.findByRoleId(roleId).ifPresent(authorities::delete);
    }

    private static boolean approves(Role role) {
        return role.hasPermissionTo("APPROVE_CLAIM");
    }

    private static String money(BigDecimal amount) {
        return String.format("%,.2f", amount);
    }

    /** {@code maxPayoutAmount} null means unlimited. */
    public record AuthorityView(Long roleId, String roleName, boolean approvesClaims, BigDecimal maxPayoutAmount) {
    }
}

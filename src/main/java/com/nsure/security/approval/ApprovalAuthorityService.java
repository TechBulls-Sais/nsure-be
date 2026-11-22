package com.nsure.security.approval;

import com.nsure.common.Money;
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

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    private com.nsure.security.repository.AppUserRepository users;

    @org.springframework.beans.factory.annotation.Autowired
    private com.nsure.audit.evidence.EvidenceChain audit;

    @org.springframework.beans.factory.annotation.Value("${nsure.approval.max-delegation-days:30}")
    private int maxDelegationDays;

    /**
     * The user's approval limit, or empty when unbounded: the highest of their own roles' limits and any delegation
     * lent to them that is in force now (NS-SH10-003).
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> limitFor(AppUser user) {
        Optional<BigDecimal> own = ownLimit(user);
        if (own.isEmpty() || jdbc == null) {
            return own;
        }
        BigDecimal delegated = jdbc.queryForObject("select max(max_amount) from auth_delegation where delegate = ? and"
                + " revoked_at is null and valid_from <= ? and valid_to >= ?", BigDecimal.class, user.getUsername(),
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        return delegated != null && delegated.compareTo(own.get()) > 0 ? Optional.of(delegated) : own;
    }

    /**
     * {delegate, maxAmount, validTo, reason}: the signed-in approver lends a higher claim approval limit to a colleague
     * who also approves claims, until a date at most {@code nsure.approval.max-delegation-days} away. It can never be
     * above the delegator's own limit (only a role's limit counts, not a delegation they received).
     */
    @Transactional
    public Map<String, Object> delegate(com.fasterxml.jackson.databind.JsonNode body) {
        AppUser delegator = security.authenticatedUser();
        delegator.validateHasPermissionTo("APPROVE_CLAIM");
        String to = body == null || !body.hasNonNull("delegate") ? null : body.get("delegate").asText().trim();
        if (to == null || to.isEmpty() || to.equalsIgnoreCase(delegator.getUsername())) {
            throw new IllegalArgumentException("Choose the colleague the authority is delegated to (not yourself).");
        }
        AppUser delegate = users.findByUsername(to)
                .orElseThrow(() -> new com.nsure.common.exception.NotFoundException("User " + to + " not found"));
        if (!delegate.isEnabled() || !delegate.hasPermissionTo("APPROVE_CLAIM")) {
            throw new IllegalArgumentException(to + " does not approve claims, so no limit can be delegated to them.");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(body.path("maxAmount").asText(""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Give the limit delegated, e.g. 100000.");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("The delegated limit must be above zero.");
        }
        Optional<BigDecimal> own = ownLimit(delegator);
        if (own.isPresent() && amount.compareTo(own.get()) > 0) {
            throw new NoAuthorizationException("You can delegate at most your own limit of " + Money.display(own.get())
                    + ".");
        }
        java.time.LocalDateTime until;
        try {
            until = java.time.LocalDate.parse(body.path("validTo").asText("")).atTime(23, 59, 59);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Give the last day of the delegation, e.g. 2026-12-31.");
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (until.isBefore(now) || until.isAfter(now.plusDays(maxDelegationDays).withHour(23).withMinute(59))) {
            throw new IllegalArgumentException("A delegation lasts at most " + maxDelegationDays + " days.");
        }
        String reason = body.path("reason").asText("").trim();
        if (reason.length() < 5) {
            throw new IllegalArgumentException("Say why the authority is delegated (at least 5 characters).");
        }
        jdbc.update("insert into auth_delegation (delegator, delegate, max_amount, valid_from, valid_to, reason) values"
                + " (?, ?, ?, ?, ?, ?)", delegator.getUsername(), delegate.getUsername(), amount, now, until, reason);
        audit.record(delegator.getUsername(), "Approval authority delegated", "User", delegate.getUsername(),
                Money.display(amount) + " until " + until.toLocalDate() + ": " + reason);
        return delegations();
    }

    /** Ends a delegation now: by its delegator, or by someone who manages roles (UPDATE_ROLE). */
    @Transactional
    public Map<String, Object> revokeDelegation(Long id) {
        AppUser user = security.authenticatedUser();
        Map<String, Object> d = jdbc.queryForList("select delegator, delegate from auth_delegation where id = ?", id)
                .stream().findFirst().orElseThrow(() -> new com.nsure.common.exception.NotFoundException(
                        "Delegation " + id + " not found"));
        String delegator = String.valueOf(d.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("delegator"))
                .findFirst().orElseThrow().getValue());
        if (!delegator.equalsIgnoreCase(user.getUsername()) && !user.hasPermissionTo("UPDATE_ROLE")) {
            throw new NoAuthorizationException("Only the delegator or a role administrator ends a delegation.");
        }
        jdbc.update("update auth_delegation set revoked_by = ?, revoked_at = ? where id = ? and revoked_at is null",
                user.getUsername(), java.time.LocalDateTime.now(), id);
        audit.record(user.getUsername(), "Approval delegation revoked", "Delegation", String.valueOf(id), null);
        return delegations();
    }

    /** Delegations (newest first) and the signed-in user's own effective limit. */
    @Transactional(readOnly = true)
    public Map<String, Object> delegations() {
        AppUser user = security.authenticatedUser();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        out.put("delegations", jdbc.queryForList("select * from auth_delegation order by id desc").stream().map(r -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k.toLowerCase(java.util.Locale.ROOT), v instanceof java.sql.Timestamp t
                    ? t.toLocalDateTime().toString() : v));
            m.put("active", m.get("revoked_at") == null
                    && !java.time.LocalDateTime.parse(String.valueOf(m.get("valid_to"))).isBefore(now));
            return m;
        }).toList());
        Optional<BigDecimal> mine = limitFor(user);
        out.put("myLimit", mine.map(BigDecimal::toPlainString).orElse("UNLIMITED"));
        out.put("maxDays", maxDelegationDays);
        return out;
    }

    /** The highest limit among the user's own approving roles; empty when one of them is unbounded. */
    private Optional<BigDecimal> ownLimit(AppUser user) {
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
                throw new NoAuthorizationException("This payout of " + Money.display(payout)
                        + " exceeds your approval limit of " + Money.display(limit) + ". It needs a more senior approver.");
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

    /** {@code maxPayoutAmount} null means unlimited. */
    public record AuthorityView(Long roleId, String roleName, boolean approvesClaims, BigDecimal maxPayoutAmount) {
    }
}

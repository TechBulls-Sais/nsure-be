package com.nsure.controls;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /admin/controls} (spec 12 §3.3): state, value, who changed it, when and why. Read with READ_CONTROL
 * (controls.read); change with UPDATE_CONTROL (controls.write, granted to no role by default — C-10).
 */
@RestController
@RequestMapping("/admin/controls")
public class ControlController {

    private final ControlService controls;
    private final StpMonitor stp;
    private final ProductStpPolicyRepository stpPolicies;
    private final PlatformSecurityContext security;
    private final EntityManager em;

    public ControlController(ControlService controls, StpMonitor stp, ProductStpPolicyRepository stpPolicies,
            PlatformSecurityContext security, EntityManager em) {
        this.controls = controls;
        this.stp = stp;
        this.stpPolicies = stpPolicies;
        this.security = security;
        this.em = em;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ControlView> list() {
        security.authenticatedUser().validateHasPermissionTo("READ_CONTROL");
        Map<Long, String> names = usernames();
        return controls.all().stream().map(row -> view(row, names)).toList();
    }

    @GetMapping("/stp-dashboard")
    public StpMonitor.Dashboard stpDashboard() {
        security.authenticatedUser().validateHasPermissionTo("READ_CONTROL");
        return stp.dashboard();
    }

    @GetMapping("/stp-policies")
    @Transactional(readOnly = true)
    public List<StpPolicyView> stpPolicies() {
        security.authenticatedUser().validateHasPermissionTo("READ_CONTROL");
        Map<Long, ProductStpPolicy> byProduct = stpPolicies.findAll().stream()
                .collect(Collectors.toMap(ProductStpPolicy::getProductId, p -> p));
        return em.createQuery("select p.id, p.name from InsuranceProduct p order by p.name", Object[].class)
                .getResultStream()
                .map(row -> {
                    ProductStpPolicy policy = byProduct.get((Long) row[0]);
                    return policy == null
                            ? new StpPolicyView((Long) row[0], (String) row[1], false, true, null, true, null)
                            : new StpPolicyView((Long) row[0], (String) row[1], true, policy.isStpEnabled(),
                                    policy.getStpCeiling(), policy.isRequiresVerifiedPayout(),
                                    policy.getMaxClaimsPerPolicyPerYear());
                })
                .toList();
    }

    /** Product-level STP override (C-15): {stpEnabled, stpCeiling (null inherits), requiresVerifiedPayout, maxClaimsPerPolicyPerYear}. */
    @PutMapping("/stp-policies/{productId}")
    @Transactional
    public StpPolicyView setStpPolicy(@PathVariable Long productId, @RequestBody JsonNode body) {
        security.authenticatedUser().validateHasPermissionTo("UPDATE_CONTROL");
        Object[] product = em.createQuery("select p.id, p.name from InsuranceProduct p where p.id = :id", Object[].class)
                .setParameter("id", productId)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new com.nsure.common.exception.NotFoundException("Product not found"));
        ProductStpPolicy policy = stpPolicies.findByProductId(productId).orElseGet(() -> new ProductStpPolicy(productId));
        if (body.has("stpEnabled")) {
            policy.setStpEnabled(body.get("stpEnabled").asBoolean());
        }
        if (body.has("stpCeiling")) {
            BigDecimal ceiling = body.get("stpCeiling").isNull() ? null : body.get("stpCeiling").decimalValue();
            if (ceiling != null && ceiling.signum() < 0) {
                throw new IllegalArgumentException("The ceiling cannot be negative.");
            }
            policy.setStpCeiling(ceiling);
        }
        if (body.has("requiresVerifiedPayout")) {
            policy.setRequiresVerifiedPayout(body.get("requiresVerifiedPayout").asBoolean());
        }
        if (body.has("maxClaimsPerPolicyPerYear")) {
            Integer max = body.get("maxClaimsPerPolicyPerYear").isNull() ? null : body.get("maxClaimsPerPolicyPerYear").asInt();
            if (max != null && max < 1) {
                throw new IllegalArgumentException("The yearly claim limit must be at least 1, or empty for no limit.");
            }
            policy.setMaxClaimsPerPolicyPerYear(max);
        }
        stpPolicies.save(policy);
        return new StpPolicyView(productId, (String) product[1], true, policy.isStpEnabled(), policy.getStpCeiling(),
                policy.isRequiresVerifiedPayout(), policy.getMaxClaimsPerPolicyPerYear());
    }

    @GetMapping("/{key}/history")
    @Transactional(readOnly = true)
    public List<HistoryView> history(@PathVariable String key) {
        security.authenticatedUser().validateHasPermissionTo("READ_CONTROL");
        Map<Long, String> names = usernames();
        return controls.history(key).stream()
                .map(h -> new HistoryView(h.getChangedAt(), names.getOrDefault(h.getChangedBy(), "#" + h.getChangedBy()),
                        h.getPreviousEnabled(), h.getNewEnabled(), h.getPreviousValue(), h.getNewValue(), h.getReason()))
                .toList();
    }

    /** {enabled?, numericValue?, reason}. */
    @PutMapping("/{key}")
    @Transactional
    public ControlView change(@PathVariable String key, @RequestBody JsonNode body) {
        Boolean enabled = body.hasNonNull("enabled") ? body.get("enabled").asBoolean() : null;
        BigDecimal value = null;
        if (body.hasNonNull("numericValue")) {
            try {
                value = new BigDecimal(body.get("numericValue").asText().trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("numericValue must be a number");
            }
        }
        String reason = body.hasNonNull("reason") ? body.get("reason").asText() : null;
        OperationalControl changed = controls.change(key, enabled, value, reason, security.authenticatedUser());
        return view(changed, usernames());
    }

    private Map<Long, String> usernames() {
        return em.createQuery("select u.id, u.username from AppUser u", Object[].class)
                .getResultStream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (String) r[1]));
    }

    private static ControlView view(OperationalControl row, Map<Long, String> names) {
        return new ControlView(row.getControlKey(), row.getName(), row.getDescription(), row.getControlType(),
                row.isEnabled(), row.getNumericValue(), row.getFailMode(), row.getMinValue(), row.getMaxValue(),
                row.isRequiresReason(),
                row.getLastChangedBy() == null ? null : names.getOrDefault(row.getLastChangedBy(), "#" + row.getLastChangedBy()),
                row.getLastChangedAt(), row.getLastChangeReason());
    }

    public record ControlView(
            String key,
            String name,
            String description,
            String type,
            boolean enabled,
            BigDecimal numericValue,
            String failMode,
            BigDecimal minValue,
            BigDecimal maxValue,
            boolean requiresReason,
            String lastChangedBy,
            LocalDateTime lastChangedAt,
            String lastChangeReason) {
    }

    public record HistoryView(LocalDateTime changedAt, String changedBy, Boolean previousEnabled, Boolean newEnabled,
            BigDecimal previousValue, BigDecimal newValue, String reason) {
    }

    public record StpPolicyView(Long productId, String product, boolean overridden, boolean stpEnabled,
            BigDecimal stpCeiling, boolean requiresVerifiedPayout, Integer maxClaimsPerPolicyPerYear) {
    }
}

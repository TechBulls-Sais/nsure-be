package com.nsure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nsure.security.domain.Permission;
import com.nsure.security.domain.PermissionCatalog;
import com.nsure.security.domain.Role;
import com.nsure.security.repository.PermissionRepository;
import com.nsure.security.repository.RoleRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seeded roles hold exactly the spec grant matrix (spec 04 §4, 11 §2.2, 12 §3.3), and the permission table
 * holds exactly the closed vocabulary in source (A-4). Both directions: drift in either is a failing build.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-rbac-matrix;DB_CLOSE_DELAY=-1")
@Transactional
class RbacMatrixTest {

    /** Spec 04 §4 (+ 11 §2.2 rate grants, + 12 §3.3 controls.read). */
    static final Map<String, Set<String>> MATRIX = new LinkedHashMap<>();

    static {
        MATRIX.put("Policyholder / End User", Set.of());
        MATRIX.put("Product Configurator", Set.of(
                "product.read", "product.write", "product.activate", "product.delete", "tier.write",
                "rate.read", "rate.write"));
        MATRIX.put("Actuary", Set.of(
                "dashboard.read", "claim.read", "policy.read", "payment.read", "product.read", "product.write",
                "rate.read", "rate.write", "rate.publish", "controls.read"));
        MATRIX.put("Onboarding Specialist", Set.of("customer.read", "customer.write"));
        MATRIX.put("Claims Assessor", Set.of(
                "dashboard.read", "claim.read", "claim.create", "claim.assess", "claim.approve", "claim.reject",
                "policy.read", "payment.read", "controls.read"));
        MATRIX.put("Customer Support Representative", Set.of(
                "dashboard.read", "claim.read", "claim.create", "policy.read", "policy.create", "policy.write",
                "payment.read", "customer.read"));
        MATRIX.put("Compliance & Auditor", Set.of(
                "dashboard.read", "claim.read", "policy.read", "payment.read", "customer.read", "audit.read",
                "finance.export", "rate.read", "controls.read"));
    }

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PermissionRepository permissions;

    @Test
    void theSevenSpecRolesHoldExactlyTheGrantMatrix() {
        MATRIX.forEach((roleName, expected) -> {
            Role role = roles.findByName(roleName).orElseThrow(() -> new AssertionError("Missing role " + roleName));
            Set<String> codes = role.getPermissions().stream().map(Permission::getCode).collect(Collectors.toSet());
            Set<String> held = new TreeSet<>();
            Set<String> partial = new TreeSet<>();
            for (String capability : PermissionCatalog.capabilities()) {
                Set<String> realisedBy = PermissionCatalog.codesFor(capability);
                long count = realisedBy.stream().filter(codes::contains).count();
                if (count == realisedBy.size()) {
                    held.add(capability);
                } else if (count > 0) {
                    partial.add(capability);
                }
            }
            assertThat(held).as("capabilities of %s", roleName).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(partial).as("half-granted capabilities of %s", roleName).isEmpty();
        });
    }

    @Test
    void theCustomerRoleIsProtectedAndHasNoDeskAccess() {
        Role portal = roles.findByName("Policyholder / End User").orElseThrow();
        assertThat(portal.isProtectedRole()).isTrue();
        assertThat(portal.isDeskAccess()).isFalse();
        assertThat(portal.getPermissions()).isEmpty();
        assertThat(roles.findByName("Policyholder")).as("legacy name renamed, not recreated").isEmpty();
    }

    @Test
    void breakGlassAuthoritiesAreGrantedToNoRole() {
        for (Role role : roles.findAll()) {
            Set<String> codes = role.getPermissions().stream().map(Permission::getCode).collect(Collectors.toSet());
            assertThat(codes).as("break-glass codes on %s", role.getName())
                    .doesNotContainAnyElementsOf(PermissionCatalog.BREAK_GLASS);
        }
    }

    @Test
    void thePermissionTableHoldsExactlyTheVocabularyInSource() {
        Map<String, String> seeded = permissions.findAll().stream()
                .collect(Collectors.toMap(Permission::getCode, p -> p.getCapability() == null ? "" : p.getCapability()));
        assertThat(seeded.keySet()).containsExactlyInAnyOrderElementsOf(PermissionCatalog.codes());
        for (PermissionCatalog code : PermissionCatalog.values()) {
            assertThat(seeded.get(code.name())).as("capability of %s", code.name())
                    .isEqualTo(code.capability() == null ? "" : code.capability());
        }
    }

    @Test
    void theSpecVocabularyIsComplete() {
        // Spec 04 §3 (19) + §3.1 (5) + the two break-glass authorities.
        assertThat(PermissionCatalog.capabilities()).containsExactlyInAnyOrder(
                "dashboard.read", "claim.read", "claim.create", "claim.assess", "claim.approve", "claim.reject",
                "policy.read", "policy.create", "policy.write", "payment.read", "customer.read", "customer.write",
                "product.read", "product.write", "product.activate", "product.delete", "tier.write", "audit.read",
                "finance.export", "rate.read", "rate.write", "rate.publish", "controls.read", "controls.write",
                "claim.approve.override", "rate.publish.self");
    }
}

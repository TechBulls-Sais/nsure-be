package com.nsure.mipas.web;

import com.nsure.mipas.capability.Capability;
import com.nsure.mipas.capability.CapabilityService;
import com.nsure.mipas.capability.MipasCapability;
import com.nsure.mipas.capability.MipasCapabilityRepository;
import com.nsure.mipas.control.ControlService;
import com.nsure.mipas.control.OperationalControl;
import com.nsure.mipas.control.OperationalControlHistory;
import com.nsure.mipas.reference.Courtesy;
import com.nsure.mipas.reference.Gender;
import com.nsure.mipas.reference.PayoutMethod;
import com.nsure.mipas.reference.PremiumFrequency;
import com.nsure.mipas.reference.ProofOfIdentity;
import com.nsure.mipas.reference.ReferenceCatalog;
import com.nsure.mipas.reference.RelationshipType;
import com.nsure.security.domain.Role;
import com.nsure.security.repo.RoleRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MipasFoundationController {

    private static final List<String> MIPAS_ROLES = List.of(
            "Policyholder / End User",
            "Product Configurator",
            "Actuary",
            "Onboarding Specialist",
            "Claims Assessor",
            "Customer Support Representative",
            "Compliance & Auditor");

    private final ReferenceCatalog catalog;
    private final ControlService controls;
    private final CapabilityService capabilities;
    private final MipasCapabilityRepository capabilityRows;
    private final RoleRepository roles;

    public MipasFoundationController(ReferenceCatalog catalog,
                                     ControlService controls,
                                     CapabilityService capabilities,
                                     MipasCapabilityRepository capabilityRows,
                                     RoleRepository roles) {
        this.catalog = catalog;
        this.controls = controls;
        this.capabilities = capabilities;
        this.capabilityRows = capabilityRows;
        this.roles = roles;
    }

    @GetMapping("/reference/premium-frequencies")
    public List<FrequencyView> frequencies() {
        return catalog.frequencies().stream().map(FrequencyView::from).toList();
    }

    @GetMapping("/reference/relationship-types")
    public List<RelationshipView> relationships() {
        return catalog.relationships().stream().map(RelationshipView::from).toList();
    }

    @GetMapping("/reference/courtesies")
    public List<NameView> courtesies() {
        return catalog.courtesies().stream().map(NameView::from).toList();
    }

    @GetMapping("/reference/identity-proofs")
    public List<NameView> identityProofs() {
        return catalog.identityProofs().stream().map(NameView::from).toList();
    }

    @GetMapping("/reference/payout-methods")
    public List<NameView> payoutMethods() {
        return catalog.payoutMethods().stream().map(NameView::from).toList();
    }

    @GetMapping("/reference/genders")
    public List<NameView> genders() {
        return catalog.genders().stream().map(NameView::from).toList();
    }

    @GetMapping("/reference/screen-filters")
    public List<ReferenceCatalog.ScreenFilterView> screenFilters() {
        return catalog.screenFilters();
    }

    @GetMapping("/controls")
    public List<ControlView> controls() {
        capabilities.require(Capability.CONTROLS_READ);
        return controls.all().stream().map(ControlView::from).toList();
    }

    @GetMapping("/controls/{key}/history")
    public List<ControlHistoryView> history(@PathVariable String key) {
        capabilities.require(Capability.CONTROLS_READ);
        return controls.history(key).stream().map(ControlHistoryView::from).toList();
    }

    @PutMapping("/controls/{key}")
    public ControlView update(@PathVariable String key, @RequestBody ControlChange body) {
        capabilities.require(Capability.CONTROLS_WRITE);
        return ControlView.from(controls.update(key, body.enabled(), body.numericValue(), body.reason()));
    }

    @GetMapping("/capabilities")
    public CapabilityCatalogView capabilityCatalog() {
        capabilities.require(Capability.CONTROLS_READ);
        List<RoleGrantView> grants = roles.findAll().stream()
                .filter(role -> MIPAS_ROLES.contains(role.getName()))
                .sorted(Comparator.comparing(Role::getName))
                .map(role -> new RoleGrantView(role.getName(), capabilities.codesForRole(role.getName())))
                .toList();
        List<CapabilityView> vocabulary = capabilityRows.findAllByOrderByGroupingAscCodeAsc().stream()
                .map(CapabilityView::from)
                .toList();
        return new CapabilityCatalogView(vocabulary, grants);
    }

    public record FrequencyView(Long id, String name, int months, String shortLabel, String periodWord, int displayOrder) {
        static FrequencyView from(PremiumFrequency row) {
            return new FrequencyView(row.getId(), row.getFrequencyName(), row.getMonths(),
                    row.getShortLabel(), row.getPeriodWord(), row.getDisplayOrder());
        }
    }

    public record RelationshipView(Long id, String name, int displayOrder, boolean self) {
        static RelationshipView from(RelationshipType row) {
            return new RelationshipView(row.getId(), row.getRelationshipName(), row.getDisplayOrder(), row.isSelfRelationship());
        }
    }

    public record NameView(Long id, String name) {
        static NameView from(Courtesy row) {
            return new NameView(row.getId(), row.getName());
        }

        static NameView from(ProofOfIdentity row) {
            return new NameView(row.getId(), row.getName());
        }

        static NameView from(PayoutMethod row) {
            return new NameView(row.getId(), row.getName());
        }

        static NameView from(Gender row) {
            return new NameView(row.getId(), row.getName());
        }
    }

    public record ControlView(String key, String name, String description, String type, boolean enabled,
                              BigDecimal value, String failMode, BigDecimal minValue, BigDecimal maxValue,
                              boolean requiresReason, String lastChangeReason) {
        static ControlView from(OperationalControl row) {
            return new ControlView(row.getControlKey(), row.getName(), row.getDescription(), row.getControlType(),
                    row.isEnabled(), row.getNumericValue(), row.getFailMode(), row.getMinValue(), row.getMaxValue(),
                    row.isRequiresReason(), row.getLastChangeReason());
        }
    }

    public record ControlHistoryView(String key, Boolean previousEnabled, Boolean newEnabled,
                                     BigDecimal previousValue, BigDecimal newValue, Long changedBy, String reason) {
        static ControlHistoryView from(OperationalControlHistory row) {
            return new ControlHistoryView(row.getControlKey(), row.getPreviousEnabled(), row.getNewEnabled(),
                    row.getPreviousValue(), row.getNewValue(), row.getChangedBy(), row.getReason());
        }
    }

    public record ControlChange(boolean enabled, BigDecimal numericValue, String reason) {
    }

    public record CapabilityView(String code, String grouping, String meaning) {
        static CapabilityView from(MipasCapability row) {
            return new CapabilityView(row.getCode(), row.getGrouping(), row.getMeaning());
        }
    }

    public record RoleGrantView(String role, List<String> capabilities) {
    }

    public record CapabilityCatalogView(List<CapabilityView> vocabulary, List<RoleGrantView> roles) {
    }
}

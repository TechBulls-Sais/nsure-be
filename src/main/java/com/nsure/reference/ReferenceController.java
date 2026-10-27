package com.nsure.reference;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reference")
public class ReferenceController {

    private final ReferenceQueryService reference;

    public ReferenceController(ReferenceQueryService reference) {
        this.reference = reference;
    }

    @GetMapping("/premium-frequencies")
    public List<PremiumFrequencyView> frequencies() {
        return reference.frequencies().stream()
                .map(row -> new PremiumFrequencyView(
                        row.getId(), row.getFrequencyName(), row.getMonths(),
                        row.getShortLabel(), row.getPeriodWord(), row.getDisplayOrder()))
                .toList();
    }

    @GetMapping("/relationships")
    public List<RelationshipView> relationships() {
        return views(reference.relationships(null));
    }

    @GetMapping("/relationships/{screen}")
    public List<RelationshipView> relationshipsForScreen(@PathVariable String screen) {
        return views(reference.relationships(screen));
    }

    @GetMapping("/courtesies")
    public List<NamedValue> courtesies() {
        return reference.courtesies().stream().map(row -> new NamedValue(row.getId(), row.getName())).toList();
    }

    @GetMapping("/identity-types")
    public List<NamedValue> identityTypes() {
        return reference.identityTypes().stream().map(row -> new NamedValue(row.getId(), row.getName())).toList();
    }

    @GetMapping("/payout-methods")
    public List<NamedValue> payoutMethods() {
        return reference.payoutMethods().stream().map(row -> new NamedValue(row.getId(), row.getName())).toList();
    }

    @GetMapping("/genders")
    public List<NamedValue> genders() {
        return reference.genders().stream().map(row -> new NamedValue(row.getId(), row.getName())).toList();
    }

    private static List<RelationshipView> views(List<RelationshipType> rows) {
        return rows.stream()
                .map(row -> new RelationshipView(
                        row.getId(), row.getRelationshipName(), row.getDisplayOrder(), row.isSelfRelationship()))
                .toList();
    }

    public record PremiumFrequencyView(
            Long id, String frequencyName, int months, String shortLabel, String periodWord, int displayOrder) {
    }

    public record RelationshipView(Long id, String relationshipName, int displayOrder, boolean self) {
    }
}

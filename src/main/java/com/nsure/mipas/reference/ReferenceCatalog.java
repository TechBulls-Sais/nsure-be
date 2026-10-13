package com.nsure.mipas.reference;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReferenceCatalog {

    private final PremiumFrequencyRepository frequencies;
    private final RelationshipTypeRepository relationships;
    private final CourtesyRepository courtesies;
    private final ProofOfIdentityRepository identityProofs;
    private final PayoutMethodRepository payoutMethods;
    private final GenderRepository genders;
    private final RelationshipScreenFilterRepository filters;
    private final RelationshipScreenFilterItemRepository filterItems;

    public ReferenceCatalog(PremiumFrequencyRepository frequencies,
                            RelationshipTypeRepository relationships,
                            CourtesyRepository courtesies,
                            ProofOfIdentityRepository identityProofs,
                            PayoutMethodRepository payoutMethods,
                            GenderRepository genders,
                            RelationshipScreenFilterRepository filters,
                            RelationshipScreenFilterItemRepository filterItems) {
        this.frequencies = frequencies;
        this.relationships = relationships;
        this.courtesies = courtesies;
        this.identityProofs = identityProofs;
        this.payoutMethods = payoutMethods;
        this.genders = genders;
        this.filters = filters;
        this.filterItems = filterItems;
    }

    public List<PremiumFrequency> frequencies() {
        return frequencies.findAllByOrderByDisplayOrderAsc();
    }

    public List<RelationshipType> relationships() {
        return relationships.findAllByOrderByDisplayOrderAsc();
    }

    public List<Courtesy> courtesies() {
        return courtesies.findAllByOrderByNameAsc();
    }

    public List<ProofOfIdentity> identityProofs() {
        return identityProofs.findAllByOrderByNameAsc();
    }

    public List<PayoutMethod> payoutMethods() {
        return payoutMethods.findAllByOrderByNameAsc();
    }

    public List<Gender> genders() {
        return genders.findAllByOrderByNameAsc();
    }

    public List<ScreenFilterView> screenFilters() {
        return filters.findAllByOrderByScreenAsc().stream()
                .map(filter -> new ScreenFilterView(
                        filter.getScreen(),
                        filter.getScreenLabel(),
                        filterItems.findByFilter_ScreenOrderByDisplayOrderAsc(filter.getScreen()).stream()
                                .map(item -> item.getRelationshipType().getRelationshipName())
                                .toList()))
                .toList();
    }

    public record ScreenFilterView(String screen, String label, List<String> relationships) {
    }
}

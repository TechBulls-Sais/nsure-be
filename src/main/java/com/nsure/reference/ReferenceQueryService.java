package com.nsure.reference;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReferenceQueryService {

    public static final Set<String> SCREENS = Set.of("life-assured", "family-details", "beneficiary");

    private final ReferenceRepository data;

    public ReferenceQueryService(ReferenceRepository data) {
        this.data = data;
    }

    @Transactional(readOnly = true)
    public List<PremiumFrequency> frequencies() {
        return data.frequencies();
    }

    @Transactional(readOnly = true)
    public List<RelationshipType> relationships(String screen) {
        if (screen == null || screen.isBlank()) {
            return data.relationships();
        }
        if (!SCREENS.contains(screen)) {
            throw new IllegalArgumentException("Unknown screen " + screen);
        }
        List<RelationshipType> filtered = data.relationshipsForScreen(screen);
        return filtered.isEmpty() ? data.relationships() : filtered;
    }

    @Transactional(readOnly = true)
    public List<Courtesy> courtesies() {
        return data.courtesies();
    }

    @Transactional(readOnly = true)
    public List<ProofOfIdentity> identityTypes() {
        return data.identityTypes();
    }

    @Transactional(readOnly = true)
    public List<PayoutMethod> payoutMethods() {
        return data.payoutMethods();
    }

    @Transactional(readOnly = true)
    public List<Gender> genders() {
        return data.genders();
    }
}

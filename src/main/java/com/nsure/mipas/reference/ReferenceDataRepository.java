package com.nsure.mipas.reference;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PremiumFrequencyRepository extends JpaRepository<PremiumFrequency, Long> {
    List<PremiumFrequency> findAllByOrderByDisplayOrderAsc();
}

interface RelationshipTypeRepository extends JpaRepository<RelationshipType, Long> {
    List<RelationshipType> findAllByOrderByDisplayOrderAsc();
}

interface CourtesyRepository extends JpaRepository<Courtesy, Long> {
    List<Courtesy> findAllByOrderByNameAsc();
}

interface ProofOfIdentityRepository extends JpaRepository<ProofOfIdentity, Long> {
    List<ProofOfIdentity> findAllByOrderByNameAsc();
}

interface PayoutMethodRepository extends JpaRepository<PayoutMethod, Long> {
    List<PayoutMethod> findAllByOrderByNameAsc();
}

interface GenderRepository extends JpaRepository<Gender, Long> {
    List<Gender> findAllByOrderByNameAsc();
}

interface RelationshipScreenFilterRepository extends JpaRepository<RelationshipScreenFilter, Long> {
    List<RelationshipScreenFilter> findAllByOrderByScreenAsc();
}

interface RelationshipScreenFilterItemRepository extends JpaRepository<RelationshipScreenFilterItem, Long> {
    List<RelationshipScreenFilterItem> findByFilter_ScreenOrderByDisplayOrderAsc(String screen);
}

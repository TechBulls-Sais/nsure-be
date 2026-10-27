package com.nsure.reference;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ReferenceRepository {

    private final EntityManager em;

    public ReferenceRepository(EntityManager em) {
        this.em = em;
    }

    public List<PremiumFrequency> frequencies() {
        return em.createQuery(
                "select f from PremiumFrequency f order by f.displayOrder", PremiumFrequency.class)
                .getResultList();
    }

    public List<RelationshipType> relationships() {
        return em.createQuery(
                "select r from RelationshipType r order by r.displayOrder", RelationshipType.class)
                .getResultList();
    }

    public List<RelationshipType> relationshipsForScreen(String screen) {
        return em.createQuery(
                """
                select r from RelationshipType r
                where r.id in (
                  select i.relationshipTypeId from RelationshipScreenFilterItem i, RelationshipScreenFilter f
                  where i.filterId = f.id and f.screen = :screen)
                order by r.displayOrder
                """, RelationshipType.class)
                .setParameter("screen", screen)
                .getResultList();
    }

    public List<Courtesy> courtesies() {
        return em.createQuery("select c from Courtesy c order by c.name", Courtesy.class).getResultList();
    }

    public List<ProofOfIdentity> identityTypes() {
        return em.createQuery("select p from ProofOfIdentity p order by p.name", ProofOfIdentity.class).getResultList();
    }

    public List<PayoutMethod> payoutMethods() {
        return em.createQuery("select p from PayoutMethod p order by p.name", PayoutMethod.class).getResultList();
    }

    public List<Gender> genders() {
        return em.createQuery("select g from Gender g order by g.name", Gender.class).getResultList();
    }
}

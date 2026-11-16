package com.nsure.controls;

import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.PermissionCatalog;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Raises and lists staff alerts; each user sees only the alerts whose permission they hold. */
@Service
public class StaffAlertService {

    public static final String CONTROL_DISABLED = "CONTROL_DISABLED";
    public static final String STP_RATE_ZERO = "STP_RATE_ZERO";

    private final EntityManager em;

    public StaffAlertService(EntityManager em) {
        this.em = em;
    }

    /** Raises an alert once per {@code dedupKey}; returns false when it already exists. */
    @Transactional
    public boolean raise(String type, String requiredPermission, String title, String body, String dedupKey,
            String createdBy) {
        PermissionCatalog.require(requiredPermission);
        Long existing = em.createQuery("select count(a) from StaffAlert a where a.dedupKey = :key", Long.class)
                .setParameter("key", dedupKey)
                .getSingleResult();
        if (existing > 0) {
            return false;
        }
        em.persist(new StaffAlert(type, requiredPermission, title, body, dedupKey, createdBy));
        return true;
    }

    @Transactional(readOnly = true)
    public List<StaffAlert> visibleTo(AppUser user) {
        if (user.isPortalCustomer()) {
            return List.of();
        }
        return em.createQuery("select a from StaffAlert a where a.createdAt >= :since order by a.createdAt desc, a.id desc",
                        StaffAlert.class)
                .setParameter("since", LocalDateTime.now().minusDays(30))
                .setMaxResults(200)
                .getResultStream()
                .filter(alert -> user.hasPermissionTo(alert.getRequiredPermission()))
                .limit(50)
                .toList();
    }
}

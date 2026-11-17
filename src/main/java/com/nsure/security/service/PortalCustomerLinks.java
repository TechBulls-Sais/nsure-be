package com.nsure.security.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * Which logins are linked to a customer record. Spec 04 A-19 keys the "no portal holders" guard on the customer
 * link, not on a user-type flag: a staff-typed account attached to a customer record is still a portal customer.
 */
@Component
public class PortalCustomerLinks {

    private final EntityManager em;

    public PortalCustomerLinks(EntityManager em) {
        this.em = em;
    }

    public boolean isLinked(String username) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM ins_customer WHERE login_username = ?1")
                .setParameter(1, username)
                .getSingleResult();
        return count.longValue() > 0;
    }

    /** Whether this login is tied to an agent, employer or lender through the partner portal (portal_account). */
    public boolean isPartnerLinked(String username) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM portal_account WHERE username = ?1")
                .setParameter(1, username)
                .getSingleResult();
        return count.longValue() > 0;
    }

    /** How many users holding this role are linked to a customer record. */
    public long linkedHoldersOf(Long roleId) {
        Number count = (Number) em.createNativeQuery("""
                        SELECT COUNT(*) FROM app_user_role ur
                        JOIN app_user u ON u.id = ur.user_id
                        JOIN ins_customer c ON c.login_username = u.username
                        WHERE ur.role_id = ?1
                        """)
                .setParameter(1, roleId)
                .getSingleResult();
        return count.longValue();
    }
}

package com.nsure.security.api;

import java.util.List;

/**
 * The signed-in user as the UI needs it. {@code permissions} is every code the user may perform now (explicit role
 * grants plus active emergency grants, empty for a portal customer): the UI shows a page or an action only when the
 * code its API needs is in this list, so the route guard and the endpoint cite the same code (spec 04 A-9).
 */
public record MeResponse(
        Long id,
        String username,
        boolean superAdmin,
        List<String> roles,
        // A portal customer: authorised by ownership, holds no permission (spec 04 A-2, A-11).
        boolean customerPortal,
        // Staff: holds an enabled role with desk access.
        boolean staff,
        boolean canReviewKyc,
        boolean canReviewEdd,
        // Policy workflow: record premiums, cancel unpaid policies, manage beneficiaries.
        boolean canManagePolicies,
        // Claim workflow: lodge, update the payee, assess, approve, reject, pay out.
        boolean canLodgeClaims,
        boolean canUpdateClaims,
        boolean canAssessClaims,
        boolean canApproveClaims,
        boolean canRejectClaims,
        boolean canSettleClaims,
        List<String> permissions,
        // Emergency access in force now (Nsure Life NS-SH11-003): the UI shows the user what they hold and until when.
        List<EmergencyAccess> emergencyAccess) {

    public record EmergencyAccess(String permission, java.time.LocalDateTime expiresAt, String grantedBy) {
    }
}

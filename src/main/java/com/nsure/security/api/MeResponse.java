package com.nsure.security.api;

import java.util.List;

public record MeResponse(
        Long id,
        String username,
        boolean superAdmin,
        List<String> roles,
        boolean customerPortal,
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
        // The Nsure Life module codes this user holds (MeController.MODULE_CODES): the UI shows only these workspaces.
        List<String> permissions) {
}

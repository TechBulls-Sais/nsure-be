package com.nsure.portfolio;

import static com.nsure.portfolio.PolicyWorkflowApiTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The demo server's claim workflow: lodge, five checks, straight-through approval or manual review, then
 * assess → approve (four-eyes) → pay, or reject with a reason.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-claim-workflow;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ClaimWorkflowApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ClaimWorkflow claims;

    /** Claims in these tests have no documents on file, so approving them needs this reason (NS-SH05-003). */
    private static final String EVIDENCE = "\"evidenceOverrideReason\":\"Certificate seen at the branch; copy to follow\"";

    @Test
    void aClaimThatPassesEveryCheckUnderTheCeilingIsApprovedStraightThrough() throws Exception {
        customer("Boitumelo Kgosi", "boitumelo");
        String policy = activePolicy("Boitumelo Kgosi", 4000, 60);
        beneficiary(policy, "Lesego Kgosi");

        String body = mockMvc.perform(json(post("/portfolio/shop/claims"), "boitumelo", lodgement(policy, "Lesego Kgosi", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims[0].status").value("Approved"))
                .andExpect(jsonPath("$.claims[0].processingType").value("STP"))
                .andExpect(jsonPath("$.claims[0].payoutAmount").value(4000))
                .andExpect(jsonPath("$.claims[0].adjudicatedBy").value(""))
                .andExpect(jsonPath("$.claims[0].checks[?(@.result == 'Fail')]").isEmpty())
                .andExpect(jsonPath("$.claims[0].payee.name").value("Lesego Kgosi"))
                .andExpect(jsonPath("$.claims[0].payee.verified").value(true))
                .andExpect(jsonPath("$.claims[0].actions[0]").value("pay"))
                .andReturn().getResponse().getContentAsString();
        String claim = JsonPath.read(body, "$.claims[0].id");

        mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("onboarding", "password")))
                .andExpect(status().isForbidden());
        // NS-SH05-007: sending the payout leaves it in flight; nothing is paid until the provider confirms it.
        String sent = mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Payout Sent"))
                .andExpect(jsonPath("$.payoutReference").value(org.hamcrest.Matchers.startsWith("PAYOUT-")))
                .andExpect(jsonPath("$.paidAt").value(""))
                .andExpect(jsonPath("$.actions[0]").value("confirm-payout"))
                .andExpect(jsonPath("$.actions[1]").value("payout-failed"))
                .andReturn().getResponse().getContentAsString();
        String payout = JsonPath.read(sent, "$.payoutReference");
        assertThat(claimPaidEvents(claim)).isZero();
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/confirm-payout"), "assessor",
                        "{\"providerReference\":\"PAYOUT-OTHER\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(payout)));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/confirm-payout"), "assessor",
                        "{\"providerReference\":\"" + payout + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Paid"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty())
                // §09: settled, it can only be closed.
                .andExpect(jsonPath("$.actions[0]").value("close"))
                .andExpect(jsonPath("$.actions.length()").value(1));
        assertThat(claimPaidEvents(claim)).isEqualTo(1L);

        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].action").value("Lodged"))
                .andExpect(jsonPath("$[1].action").value("Auto Approved"))
                .andExpect(jsonPath("$[1].performedBy").value("system"))
                .andExpect(jsonPath("$[2].action").value("Payout Sent"))
                .andExpect(jsonPath("$[3].action").value("Paid"));
    }

    private Long claimPaidEvents(String claim) {
        return tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType = 'ClaimPaid'"
                + " and e.aggregateRef = :ref", Long.class).setParameter("ref", claim).getSingleResult());
    }

    @Test
    void aClaimOverTheCeilingIsAssessedThenApprovedBySomeoneElse() throws Exception {
        customer("Kagiso Molefe", "kagiso");
        String policy = activePolicy("Kagiso Molefe", 50000, 60);
        beneficiary(policy, "Naledi Molefe");

        String claim = staffLodge(policy, "Naledi Molefe");
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].status").value("Under Review"))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].stpIneligibleReason").value(
                        "Payout of BWP 50,000 is over the straight-through ceiling of BWP 5,000"));

        mockMvc.perform(post("/portfolio/claims/" + claim + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Cannot approve a claim that is Under Review. Allowed from here: assess, reject"));

        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Recommended"))
                .andExpect(jsonPath("$.recommendedBy").value("assessor"))
                .andExpect(jsonPath("$.payee.verified").value(true));

        // Four-eyes: the assessor cannot approve their own recommendation.
        mockMvc.perform(post("/portfolio/claims/" + claim + "/approve").with(signedIn("assessor", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{\"payoutAmount\":60000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Payout of BWP 60,000 exceeds the policy cover of BWP 50,000"));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{\"payoutAmount\":45000," + EVIDENCE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Approved"))
                .andExpect(jsonPath("$.payoutAmount").value(45000))
                .andExpect(jsonPath("$.adjudicatedBy").value("admin"))
                .andExpect(jsonPath("$.fourEyesApplied").value(true));

        // Approved claims cannot be rejected or have their payee changed.
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "admin", "{\"reason\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(put("/portfolio/claims/" + claim + "/payee"), "assessor",
                        "{\"name\":\"Someone Else\",\"payoutMethod\":\"Bank Account\",\"accountRef\":\"1\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Payout Sent"));

        // AT-SH05-03: the bank returns the payout; it is re-sent only after the details are corrected and verified.
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/payout-failed"), "admin", "{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/payout-failed"), "admin",
                        "{\"reason\":\"Returned by the bank: account closed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Payout Failed"))
                .andExpect(jsonPath("$.payee.verified").value(false))
                .andExpect(jsonPath("$.actions[0]").value("pay"));
        assertThat(claimPaidEvents(claim)).isZero();
        mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("verify")));
        mockMvc.perform(json(put("/portfolio/claims/" + claim + "/payee"), "assessor",
                        "{\"name\":\"Naledi Molefe\",\"relationship\":\"Spouse\",\"payoutMethod\":\"Bank Account\","
                                + "\"accountRef\":\"62009988\",\"verified\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payee.verified").value(true));
        String resent = mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Payout Sent"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/confirm-payout"), "admin",
                        "{\"providerReference\":\"" + JsonPath.read(resent, "$.payoutReference") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Paid"));
        assertThat(claimPaidEvents(claim)).isEqualTo(1L);
    }

    @Test
    void aFailingClaimNeedsAPayeeBeforeAssessmentAndAReasonToReject() throws Exception {
        customer("Mpho Dikgang", "mphod");
        // Cover started yesterday: inside the waiting period, and the event is before cover started.
        String policy = activePolicy("Mpho Dikgang", 3000, 1);

        String body = mockMvc.perform(json(post("/portfolio/shop/claims"), "mphod", lodgement(policy, "Unknown Person", 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims[0].status").value("Under Review"))
                .andExpect(jsonPath("$.claims[0].processingType").value("Manual Review"))
                .andExpect(jsonPath("$.claims[0].checks[?(@.name == 'Policy Active')].result").value("Fail"))
                .andExpect(jsonPath("$.claims[0].checks[?(@.name == 'Waiting Period')].result").value("Fail"))
                .andExpect(jsonPath("$.claims[0].checks[?(@.name == 'Beneficiary Match')].result").value("Fail"))
                .andExpect(jsonPath("$.claims[0].checks[?(@.name == 'No Duplicate')].result").value("Pass"))
                .andExpect(jsonPath("$.claims[0].payee.name").value(""))
                .andReturn().getResponse().getContentAsString();
        String claim = JsonPath.read(body, "$.claims[0].id");

        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(put("/portfolio/claims/" + claim + "/payee"), "assessor",
                        "{\"name\":\"Unknown Person\",\"payoutMethod\":\"Mobile Wallet\",\"accountRef\":\"71234567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payee.source").value("Entered by Assessor"))
                .andExpect(jsonPath("$.payee.verified").value(false));
        mockMvc.perform(post("/portfolio/claims/" + claim + "/checks").with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Under Review"));

        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor", "{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Please enter a reason for rejecting this claim. The customer is told why."));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "onboarding", "{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor",
                        "{\"reason\":\"The event happened before cover started\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Rejected"));

        mockMvc.perform(get("/portfolio/shop").with(signedIn("mphod", "password1")))
                .andExpect(jsonPath("$.claims[0].status").value("Rejected"))
                .andExpect(jsonPath("$.claims[0].rejectionReason").value("The event happened before cover started"));
    }

    @Test
    void aCustomerClaimsOnlyOnTheirOwnPolicyForAnEventThatHappened() throws Exception {
        customer("Dikeledi Sebina", "dikeledi");
        String policy = activePolicy("Dikeledi Sebina", 3000, 60);

        mockMvc.perform(json(post("/portfolio/shop/claims"), "customer", lodgement(policy, "Dikeledi Sebina", 1)))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(post("/portfolio/shop/claims"), "dikeledi", lodgement(policy, "Dikeledi Sebina", -2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The date of the event cannot be in the future"));
        mockMvc.perform(json(post("/portfolio/claims"), "onboarding", lodgement(policy, "Dikeledi Sebina", 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvingYourOwnAssessmentNeedsTheBreakGlassAuthorityAndAWrittenReason() throws Exception {
        customer("Thato Seretse", "thato");
        String policy = activePolicy("Thato Seretse", 50000, 60);
        beneficiary(policy, "Mpho Seretse");
        String claim = staffLodge(policy, "Mpho Seretse");

        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedBy").value("admin"));
        // Spec 12 C-9 and Nsure Life NS-SH11-003: no role holds the override, the administrator included. It comes only
        // through time-limited emergency access granted by someone else, and still needs a written reason.
        mockMvc.perform(post("/portfolio/claims/" + claim + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You assessed this claim, so you cannot also authorise its "
                        + "payout. A second approver must action it."));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                        "{\"fourEyesOverrideReason\":\"Only approver on duty during the outage\"}"))
                .andExpect(status().isForbidden());
        String grant = mockMvc.perform(json(post("/admin/emergency-access"), "secadmin",
                        "{\"username\":\"admin\",\"permission\":\"OVERRIDE_CLAIM_FOUR_EYES\",\"hours\":1,"
                                + "\"reason\":\"Outage: only one approver on duty\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                        "{\"fourEyesOverrideReason\":\"Only approver on duty during the outage\"," + EVIDENCE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Approved"))
                .andExpect(jsonPath("$.adjudicatedBy").value("admin"))
                .andExpect(jsonPath("$.fourEyesApplied").value(false))
                .andExpect(jsonPath("$.fourEyesOverrideReason").value("Only approver on duty during the outage"));
        mockMvc.perform(post("/admin/emergency-access/" + JsonPath.read(grant, "$.id") + "/revoke")
                        .with(signedIn("secadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Approved')].details").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("FOUR-EYES OVERRIDDEN"))));
        // C-17: the override shows on the control-bypass register.
        mockMvc.perform(get("/admin/controls/stp-dashboard").with(signedIn("compliance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fourEyesNotApplied[?(@.claimRef == '" + claim + "')].detail").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("Override:"))));
    }

    @Test
    void aSecondAssessorApprovesOnlyWithinTheirApprovalLimit() throws Exception {
        customer("Neo Ramotswe", "neo");
        String policy = activePolicy("Neo Ramotswe", 50000, 60);
        beneficiary(policy, "Kabo Ramotswe");
        String claim = staffLodge(policy, "Kabo Ramotswe");
        // Over the STP ceiling: routed to a person with the reason recorded as a code (spec 12 C-12).
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].stpEligible").value(false))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].stpIneligibleCode").value("OVER_CEILING"));
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());

        Long roleId = ((Number) em.createQuery("select r.id from Role r where r.name = 'Claims Assessor'")
                .getSingleResult()).longValue();
        try {
            mockMvc.perform(json(put("/admin/approval-authorities/" + roleId), "admin", "{\"maxPayoutAmount\":10000}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.maxPayoutAmount").value(10000.00));
            // Spec 04 §5.3: over the limit needs a more senior approver.
            mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "assessor2", "{\"payoutAmount\":45000}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("This payout of P 45,000.00 exceeds your approval limit of "
                            + "P 10,000.00. It needs a more senior approver."));
            mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "assessor2", "{\"payoutAmount\":\"ten\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value("Payout amount must be a number"));
            mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "assessor2", "{\"payoutAmount\":9000," + EVIDENCE + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("Approved"))
                    .andExpect(jsonPath("$.adjudicatedBy").value("assessor2"))
                    .andExpect(jsonPath("$.fourEyesApplied").value(true));
        } finally {
            mockMvc.perform(json(put("/admin/approval-authorities/" + roleId), "admin", "{\"maxPayoutAmount\":50000}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void aClaimReportedAfterCancellationIsJudgedOnCoverAtTheEventDate() throws Exception {
        // NS-SH05-002 / AT-SH05-01: the event happened while cover was in force; the policy was cancelled later.
        customer("Tebogo Late", "tebogolate");
        String policy = activePolicy("Tebogo Late", 50000, 200);
        beneficiary(policy, "Mpho Late");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/cancel"), "admin",
                        "{\"reason\":\"Customer asked to cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Cancelled"));
        mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement(policy, "Mpho Late", 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[?(@.name == 'Policy Active')].result").value(org.hamcrest.Matchers.hasItem("Pass")))
                .andExpect(jsonPath("$.checks[?(@.name == 'Premiums Paid')].result").value(org.hamcrest.Matchers.hasItem("Pass")));
        // An event after the cancellation took effect has no cover. NS-DAT-002: the cancellation is judged on the
        // date it applied from, not on when it was recorded, so only the effective date is moved back here.
        tx.executeWithoutResult(s -> em.createQuery("update WorkflowLog h set h.effectiveDate = :on where h.entityRef = :ref"
                        + " and h.action = 'Cancelled'")
                .setParameter("on", LocalDate.now().minusDays(2)).setParameter("ref", policy).executeUpdate());
        mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement(policy, "Mpho Late", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[?(@.name == 'Policy Active')].result").value(org.hamcrest.Matchers.hasItem("Fail")));
    }

    @Test
    void anEventBeforeCoverStartedFailsTheHardChecksAndCanOnlyBeRejected() throws Exception {
        customer("Kitso Molapo", "kitso");
        String policy = activePolicy("Kitso Molapo", 50000, 0);
        beneficiary(policy, "Dineo Molapo");
        // Cover started today; the event was 3 days ago.
        String body = mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement(policy, "Dineo Molapo", 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Under Review"))
                .andExpect(jsonPath("$.checks[?(@.name == 'Policy Active')].result").value(org.hamcrest.Matchers.hasItem("Fail")))
                // Premiums paid before cover started bought no cover for an earlier event.
                .andExpect(jsonPath("$.checks[?(@.name == 'Premiums Paid')].result").value(org.hamcrest.Matchers.hasItem("Fail")))
                .andReturn().getResponse().getContentAsString();
        String claim = JsonPath.read(body, "$.id");

        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "Policy Active, Waiting Period, Premiums Paid failed, so there was no valid cover")));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor", "{\"reason\":\"Event before cover\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditFlag").value(""));
    }

    @Test
    void aFailedSoftCheckIsApprovedOnlyWithAReasonThatIsKept() throws Exception {
        customer("Gaone Tau", "gaone");
        String policy = activePolicy("Gaone Tau", 50000, 60);
        beneficiary(policy, "Masego Tau");
        // The claimant is not a named beneficiary: Beneficiary Match fails, the payee is the beneficiary.
        String claim = staffLodge(policy, "Uncle Tau");
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/portfolio/claims/" + claim + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "Beneficiary Match failed")));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                        "{\"overrideReason\":\"Paid to the named beneficiary, verified against the ID\"," + EVIDENCE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Approved"))
                .andExpect(jsonPath("$.checkOverrideReason").value("Paid to the named beneficiary, verified against the ID"))
                // Following the rule is not an audit case.
                .andExpect(jsonPath("$.auditFlag").value(""));
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Approved')].details").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("approved despite Beneficiary Match"))));
    }

    @Test
    void aDuplicateIsAnEarlierClaimForTheSameEventNotAnyClaimOnThePolicy() throws Exception {
        customer("Refentse Bole", "refentse");
        String policy = activePolicy("Refentse Bole", 50000, 60);
        beneficiary(policy, "Kagiso Bole");
        String first = staffLodge(policy, "Kagiso Bole");
        // A different event on the same policy is not a duplicate...
        String other = JsonPath.read(mockMvc.perform(json(post("/portfolio/claims"), "assessor",
                        lodgement(policy, "Kagiso Bole", 5))).andReturn().getResponse().getContentAsString(), "$.id");
        // ...the same event again is.
        String again = staffLodge(policy, "Kagiso Bole");
        for (String[] expected : new String[][] {{first, "Pass"}, {other, "Pass"}, {again, "Fail"}}) {
            mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                    .andExpect(jsonPath("$[?(@.id == '" + expected[0] + "')].checks[?(@.name == 'No Duplicate')].result")
                            .value(org.hamcrest.Matchers.hasItem(expected[1])));
        }
    }

    @Test
    void aClaimPaidDespiteAFailedHardCheckIsFlaggedForAudit() throws Exception {
        customer("Lerato Phiri", "leratop");
        String policy = activePolicy("Lerato Phiri", 50000, 60);
        beneficiary(policy, "Tebogo Phiri");
        String claim = staffLodge(policy, "Tebogo Phiri");
        // As claims were paid before the rule: recorded as Paid with Policy Active failed.
        tx.executeWithoutResult(s -> em.createQuery("update InsuranceClaim c set c.status = 'Paid',"
                        + " c.checkPolicyActive = 'Fail' where c.ref = :ref")
                .setParameter("ref", claim)
                .executeUpdate());
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].auditFlag").value(org.hamcrest.Matchers.hasItem(
                        "Paid despite failed checks: Policy Active")));
    }

    @Test
    void resultsFromTheOldRulesAreReRunOnceWithoutMovingTheClaim() throws Exception {
        customer("Ditiro Kebonye", "ditiro");
        String policy = activePolicy("Ditiro Kebonye", 50000, 0);
        beneficiary(policy, "Neo Kebonye");
        String claim = JsonPath.read(mockMvc.perform(json(post("/portfolio/claims"), "assessor",
                lodgement(policy, "Neo Kebonye", 3))).andReturn().getResponse().getContentAsString(), "$.id");
        // As the old rules left it: paid, with Premiums Paid passed for an event before cover started.
        tx.executeWithoutResult(s -> em.createQuery("update InsuranceClaim c set c.status = 'Paid',"
                        + " c.checkPremiumsPaid = 'Pass', c.checkRulesVersion = 1 where c.ref = :ref")
                .setParameter("ref", claim)
                .executeUpdate());
        Long id = tx.execute(s -> em.createQuery("select c.id from InsuranceClaim c where c.ref = :ref", Long.class)
                .setParameter("ref", claim).getSingleResult());
        org.assertj.core.api.Assertions.assertThat(claims.claimsWithOldCheckResults()).contains(id);

        org.assertj.core.api.Assertions.assertThat(claims.refreshChecks(id)).containsExactly("Premiums Paid: Pass → Fail");
        org.assertj.core.api.Assertions.assertThat(claims.refreshChecks(id)).isEmpty();

        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].status").value(org.hamcrest.Matchers.hasItem("Paid")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].auditFlag").value(org.hamcrest.Matchers.hasItem(
                        "Paid despite failed checks: Policy Active, Waiting Period, Premiums Paid")));
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Checks Re-run')].details").value(org.hamcrest.Matchers.hasItem(
                        "Check rules updated · Premiums Paid: Pass → Fail")));
    }

    @Test
    void aDecidedClaimWithNoChecksShowsNotRun() throws Exception {
        customer("Oratile Sello", "oratile");
        String policy = activePolicy("Oratile Sello", 50000, 60);
        beneficiary(policy, "Kabo Sello");
        String claim = staffLodge(policy, "Kabo Sello");
        tx.executeWithoutResult(s -> em.createQuery("update InsuranceClaim c set c.status = 'Paid', c.checkPolicyActive = null,"
                        + " c.checkWaitingPeriod = null, c.checkPremiumsPaid = null, c.checkBeneficiaryMatch = null,"
                        + " c.checkNoDuplicate = null where c.ref = :ref")
                .setParameter("ref", claim)
                .executeUpdate());
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].checks[*].result").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("Not run"))))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].auditFlag").value(org.hamcrest.Matchers.hasItem(
                        "Paid without checks")));
    }

    @Test
    void aClaimCarriesItsCoveredLifeReserveEvidenceAndBenefitLimit() throws Exception {
        customer("Dineo Reserve", "dineo");
        String policy = activePolicy("Dineo Reserve", 50000, 60);
        beneficiary(policy, "Kabo Reserve");
        mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement(policy, "Kabo Reserve", 1)
                        .replace("}", ",\"coveredLife\":\"Nobody Here\"}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Nobody called Nobody Here")));

        String claim = staffLodge(policy, "Kabo Reserve");
        String claims = mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        String self = "$[?(@.id == '" + claim + "')]";
        assertThat((List<String>) JsonPath.read(claims, self + ".coveredLife")).containsExactly("Dineo Reserve");
        assertThat((List<Object>) JsonPath.read(claims, self + ".reserve")).hasSize(1);
        assertThat(new BigDecimal(((List<?>) JsonPath.read(claims, self + ".reserve")).get(0).toString()))
                .isEqualByComparingTo("50000");
        assertThat((List<Object>) JsonPath.read(claims, self + ".evidence[?(@.documentType == 'Death certificate'"
                + " && @.mandatory == true && @.received == false)]")).hasSize(1);

        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{\"payoutAmount\":45000," + EVIDENCE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserve").value(45000.0));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lifeLimit").value(50000.0))
                .andExpect(jsonPath("$.lifeUsed").value(45000.0))
                .andExpect(jsonPath("$.policyUsed").value(45000.0))
                .andExpect(jsonPath("$.movements.length()").value(2))
                // SH05 event-date cover view: the cover reconstructed as at the event.
                .andExpect(jsonPath("$.eventCover.eventDate").value(LocalDate.now().minusDays(1).toString()))
                .andExpect(jsonPath("$.eventCover.policyStatusOnEventDate").value("Active"))
                .andExpect(jsonPath("$.eventCover.coverInForce").value(true))
                .andExpect(jsonPath("$.eventCover.coverStartDate").value(LocalDate.now().minusDays(60).toString()))
                .andExpect(jsonPath("$.eventCover.coverAmount").value(50000.0))
                .andExpect(jsonPath("$.eventCover.beneficiariesOnEventDate[0]").value("Kabo Reserve"))
                .andExpect(jsonPath("$.recoveries").isEmpty());
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Approved')].details").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("evidence outstanding: Death certificate"))));

        String sent = mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/confirm-payout"), "admin",
                        "{\"providerReference\":\"" + JsonPath.read(sent, "$.payoutReference") + "\"}"))
                .andExpect(jsonPath("$.status").value("Paid"))
                .andExpect(jsonPath("$.reserve").value(0.0));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lifeUsed").value(45000.0))
                .andExpect(jsonPath("$.movements[2].reason").value("Released: payout settled"));
        Long consumed = tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType ="
                + " 'BenefitLimitConsumed' and e.aggregateRef = :ref", Long.class).setParameter("ref", policy)
                .getSingleResult());
        assertThat(consumed).isEqualTo(1L);
    }

    /**
     * NS-SH05-003 (test B-10): mandatory evidence still outstanding does not block the claim, but approving it needs a
     * written reason, kept on the claim, in its decision record and in the history. Once the evidence is on file, no
     * reason is asked for.
     */
    @Test
    void approvingWithMandatoryEvidenceOutstandingNeedsARecordedReason() throws Exception {
        customer("Kefilwe Evidence", "kefilweevidence");
        String policy = activePolicy("Kefilwe Evidence", 50000, 60);
        beneficiary(policy, "Onalenna Evidence");
        String claim = staffLodge(policy, "Onalenna Evidence");
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Assessed')].details").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("evidence outstanding: Death certificate, Identity document"))));

        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{\"payoutAmount\":40000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Required evidence is not yet on file for " + claim
                        + ": Death certificate, Identity document. Enter a reason for approving without it; the reason"
                        + " is kept with the claim."));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                        "{\"payoutAmount\":40000,\"evidenceOverrideReason\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                        "{\"payoutAmount\":40000,\"evidenceOverrideReason\":\"Originals seen at the branch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Approved"))
                .andExpect(jsonPath("$.evidenceOverrideReason").value("Originals seen at the branch"));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.decisions[0].decision").value("PARTIALLY_APPROVED"))
                .andExpect(jsonPath("$.decisions[0].reason").value(
                        "Approved without Death certificate, Identity document: Originals seen at the branch"));
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Approved')].details").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("approved without it: Originals seen at the branch"))));

        // With every mandatory document on file, approval asks for no such reason.
        String complete = staffLodge(policy, "Onalenna Evidence");
        for (String type : List.of("Death certificate", "Identity document")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .multipart("/portfolio/claims/" + complete + "/documents")
                            .file(new org.springframework.mock.web.MockMultipartFile("file", "evidence.pdf",
                                    "application/octet-stream", "%PDF-1.4\n% evidence\n%%EOF".getBytes()))
                            .param("type", type).with(signedIn("assessor", "password")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/portfolio/claims/" + complete + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims/" + complete + "/approve"), "admin",
                        "{\"payoutAmount\":5000,\"overrideReason\":\"Second event confirmed separately\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Approved"))
                .andExpect(jsonPath("$.evidenceOverrideReason").value(""));
    }

    /** SH05: the claim contract's events under the specification's names, beside the older names handlers use. */
    @Test
    void theClaimLifecyclePublishesTheSpecificationEvents() throws Exception {
        customer("Ditiro Events", "ditiroevents");
        String policy = activePolicy("Ditiro Events", 50000, 60);
        beneficiary(policy, "Mpho Events");
        String paid = staffLodge(policy, "Mpho Events");
        assertThat(eventTypes(paid)).containsExactly("ClaimReceived", "ClaimRegistered");
        mockMvc.perform(post("/portfolio/claims/" + paid + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        assertThat(eventTypes(paid)).endsWith("ClaimAssessed");
        mockMvc.perform(json(post("/portfolio/claims/" + paid + "/approve"), "admin", "{" + EVIDENCE + "}"))
                .andExpect(status().isOk());
        String sent = mockMvc.perform(post("/portfolio/claims/" + paid + "/pay").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat(eventTypes(paid)).doesNotContain("ClaimPaymentSettled");
        mockMvc.perform(json(post("/portfolio/claims/" + paid + "/confirm-payout"), "admin",
                        "{\"providerReference\":\"" + JsonPath.read(sent, "$.payoutReference") + "\"}"))
                .andExpect(status().isOk());
        assertThat(eventTypes(paid)).containsSubsequence("ClaimApproved", "ClaimPaid", "ClaimPaymentSettled");

        String declined = staffLodge(policy, "Mpho Events");
        mockMvc.perform(json(post("/portfolio/claims/" + declined + "/reject"), "assessor",
                        "{\"reason\":\"Same event as an earlier claim.\"}"))
                .andExpect(status().isOk());
        assertThat(eventTypes(declined)).endsWith("ClaimRejected", "ClaimDeclined");
        String payload = tx.execute(s -> em.createQuery("select e.payload from OutboxEvent e where e.aggregateRef = :ref"
                + " and e.eventType = 'ClaimDeclined'", String.class).setParameter("ref", declined).getSingleResult());
        assertThat(payload).contains("Same event as an earlier claim.").contains(policy);
    }

    private List<String> eventTypes(String claim) {
        return tx.execute(s -> em.createQuery("select e.eventType from OutboxEvent e where e.aggregateRef = :ref"
                + " order by e.aggregateVersion", String.class).setParameter("ref", claim).getResultList());
    }

    /**
     * §14 / AT-SH05-03: the bank's answer to a payout is unknown. The claim stays in flight (not paid, nothing posted,
     * the limit still only reserved), it cannot be sent a second time, and when the confirmations arrive several at
     * once it settles exactly once.
     */
    @Test
    void anUnknownBankOutcomeLeavesThePayoutInFlightAndSettlesExactlyOnce() throws Exception {
        customer("Kitso Unknown", "kitsounknown");
        String policy = activePolicy("Kitso Unknown", 50000, 60);
        beneficiary(policy, "Pono Unknown");
        String claim = staffLodge(policy, "Pono Unknown");
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                        "{\"payoutAmount\":20000," + EVIDENCE + "}"))
                .andExpect(status().isOk());
        String sent = mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String payout = JsonPath.read(sent, "$.payoutReference");

        // No answer from the bank: still in flight, and a second instruction is refused.
        mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Cannot pay a claim that is Payout Sent")));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.reserve").value(20000.0))
                .andExpect(jsonPath("$.lifeUsed").value(20000.0));
        assertThat(claimPaidEvents(claim)).isZero();
        assertThat(eventTypes(claim)).doesNotContain("ClaimPaymentSettled");

        // The confirmation arrives several times at once (callback, statement, operator): one settlement.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return mockMvc.perform(json(post("/portfolio/claims/" + claim + "/confirm-payout"), "admin",
                                "{\"providerReference\":\"" + payout + "\"}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        List<Integer> statuses = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<Integer> result : results) {
            statuses.add(result.get());
        }
        assertThat(statuses).containsOnlyOnce(200);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);
        assertThat(claimPaidEvents(claim)).isEqualTo(1L);
        assertThat(eventTypes(claim).stream().filter("ClaimPaymentSettled"::equals)).hasSize(1);
        Long consumed = tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType ="
                + " 'BenefitLimitConsumed' and e.payload like :claim", Long.class)
                .setParameter("claim", "%\"claimRef\":\"" + claim + "\"%").getSingleResult());
        assertThat(consumed).isEqualTo(1L);
        // A late failure notice cannot undo a confirmed settlement.
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/payout-failed"), "admin", "{\"reason\":\"Late bounce\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * §09: a partial payout is recorded as a partial approval; an approval not yet paid can be reopened, giving back
     * its limit reservation and keeping the set-aside decision; a settled claim is closed only once nothing is held.
     */
    @Test
    void aPartialApprovalCanBeReopenedBeforePaymentAndASettledClaimIsClosed() throws Exception {
        customer("Lame Lifecycle", "lamelifecycle");
        String policy = activePolicy("Lame Lifecycle", 50000, 60);
        beneficiary(policy, "Bonolo Lifecycle");
        String claim = staffLodge(policy, "Bonolo Lifecycle");
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{\"payoutAmount\":30000," + EVIDENCE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions").value(org.hamcrest.Matchers.hasItems("pay", "reopen")));
        String approved = tx.execute(s -> em.createQuery("select e.payload from OutboxEvent e where e.aggregateRef = :ref"
                + " and e.eventType = 'ClaimApproved'", String.class).setParameter("ref", claim).getSingleResult());
        assertThat(approved).contains("\"outcome\":\"PARTIALLY_APPROVED\"").contains("\"claimed\":\"50000");
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.decisions[0].decision").value("PARTIALLY_APPROVED"))
                .andExpect(jsonPath("$.lifeUsed").value(30000.0));

        // New information before payment: the approval is set aside, its reservation released.
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reopen"), "onboarding", "{\"reason\":\"Second certificate disagrees\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reopen"), "admin", "{\"reason\":\"Second certificate disagrees\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Under Review"))
                .andExpect(jsonPath("$.payoutAmount").doesNotExist())
                .andExpect(jsonPath("$.reserve").value(50000.0));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lifeUsed").value(0.0))
                .andExpect(jsonPath("$.decisions[0].supersededBy").value(2))
                .andExpect(jsonPath("$.decisions[1].decision").value("REOPENED"));

        // Decided again in full, paid and settled; then closed once nothing is held.
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{" + EVIDENCE + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/close"), "admin", "{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Cannot close a claim that is Approved. Allowed from here: pay, reopen"));
        String sent = mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reopen"), "admin", "{\"reason\":\"Too late to reopen now\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/confirm-payout"), "admin",
                        "{\"providerReference\":\"" + JsonPath.read(sent, "$.payoutReference") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0]").value("close"));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/close"), "assessor2", "{}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/close"), "onboarding", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].status").value("Paid"))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].closedBy").value("assessor2"))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].actions[0]").doesNotExist());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/close"), "admin", "{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Closed')].performedBy").value("assessor2"));
    }

    @Test
    void concurrentApprovalsCannotTogetherExceedTheSharedLimit() throws Exception {
        customer("Lorato Limit", "loratolimit");
        String policy = activePolicy("Lorato Limit", 50000, 60);
        beneficiary(policy, "Boipelo Limit");
        String first = staffLodge(policy, "Boipelo Limit");
        String second = staffLodge(policy, "Boipelo Limit");
        for (String claim : List.of(first, second)) {
            mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                    .andExpect(status().isOk());
        }
        // AT-SH05-02: each fits the limit alone; together they would not.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (String claim : List.of(first, second)) {
            results.add(pool.submit(() -> {
                go.await();
                return mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                                "{\"payoutAmount\":30000,\"overrideReason\":\"Separate benefit events confirmed\","
                                        + EVIDENCE + "}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        List<Integer> statuses = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<Integer> result : results) {
            statuses.add(result.get());
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        mockMvc.perform(get("/portfolio/claims/" + first + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policyUsed").value(30000.0));
    }

    @Test
    void aRejectedClaimIsReopenedOnlyWithAReasonBySomeoneWhoMayApprove() throws Exception {
        customer("Tumi Reopen", "tumireopen");
        String policy = activePolicy("Tumi Reopen", 50000, 60);
        beneficiary(policy, "Masego Reopen");
        String claim = staffLodge(policy, "Masego Reopen");
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor",
                        "{\"reason\":\"No death certificate was provided.\"}"))
                .andExpect(jsonPath("$.status").value("Rejected"))
                .andExpect(jsonPath("$.reserve").value(0.0))
                .andExpect(jsonPath("$.actions[0]").value("reopen"));

        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reopen"), "onboarding",
                        "{\"reason\":\"The certificate has now arrived.\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reopen"), "admin", "{\"reason\":\"late\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reopen"), "admin",
                        "{\"reason\":\"The certificate has now arrived.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Under Review"))
                .andExpect(jsonPath("$.rejectionReason").value(""))
                .andExpect(jsonPath("$.reserve").value(50000.0));
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Reopened')].details").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString(
                                "Reason: The certificate has now arrived."))));
        // §09, NS-SH05-004: the rejection it set aside is kept in full, with who made it and why.
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions[0].decision").value("REJECTED"))
                .andExpect(jsonPath("$.decisions[0].reason").value("No death certificate was provided."))
                .andExpect(jsonPath("$.decisions[0].decidedBy").value("assessor"))
                .andExpect(jsonPath("$.decisions[0].supersededBy").value(2))
                .andExpect(jsonPath("$.decisions[1].decision").value("REOPENED"))
                .andExpect(jsonPath("$.decisions[1].decidedBy").value("admin"));
    }

    /**
     * NS-SH05-002: a claim is matched against the beneficiaries registered on the event date. Someone removed after
     * the event still matches; someone added after it does not.
     */
    @Test
    void beneficiaryMatchUsesTheBeneficiariesRegisteredOnTheEventDate() throws Exception {
        customer("Lorato Bene", "loratobene");
        String policy = activePolicy("Lorato Bene", 50000, 60);
        beneficiary(policy, "Pako Bene");
        String beneficiaries = mockMvc.perform(get("/portfolio/policies").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        Integer id = ((List<Integer>) JsonPath.read(beneficiaries,
                "$[?(@.id == '" + policy + "')].beneficiaries[?(@.fullName == 'Pako Bene')].id")).get(0);
        // Removed today: from tomorrow they are no longer a beneficiary, but they were one when the event happened.
        mockMvc.perform(delete("/portfolio/policies/" + policy + "/beneficiaries/" + id)
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        // Nominated today, so not a beneficiary when the event happened five days ago.
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/beneficiaries"), "admin",
                        "{\"fullName\":\"Neo Bene\",\"relationship\":\"Spouse\",\"mobile\":\"+267 72 000 001\","
                                + "\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62004568\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement(policy, "Pako Bene", 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[?(@.name == 'Beneficiary Match')].result")
                        .value(org.hamcrest.Matchers.hasItem("Pass")))
                .andExpect(jsonPath("$.payee.name").value("Pako Bene"));
        // Neo was added today, so they were not a beneficiary when the event happened five days ago.
        mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement(policy, "Neo Bene", 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[?(@.name == 'Beneficiary Match')].result")
                        .value(org.hamcrest.Matchers.hasItem("Fail")));
    }

    private void customer(String name, String username) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 001\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(body, "$.id") + "/kyc")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk());
    }

    /** A staff-issued, flat-priced policy whose cover started `daysAgo` days ago and is paid up. */
    private String activePolicy(String name, int cover, int daysAgo) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"name\":\"" + name + "\",\"product\":\"Term Life\",\"amount\":" + cover + ",\"premium\":50}"))
                .andExpect(status().isOk())
                // NS-IL01-006: a staff issue is an offer; the first premium issues cover.
                .andExpect(jsonPath("$.status").value("Pending Payment"))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + ref + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
        tx.executeWithoutResult(status -> em.createQuery(
                        "update InsurancePolicy p set p.coverStartDate = :start where p.ref = :ref")
                .setParameter("start", LocalDate.now().minusDays(daysAgo))
                .setParameter("ref", ref)
                .executeUpdate());
        return ref;
    }

    /**
     * Nominates a beneficiary as at the policy's cover start, like a nomination taken with the application. (The
     * helper backdates the designation to match {@link #activePolicy}, which backdates the cover start by SQL;
     * in the application a nomination takes effect the day it is made.)
     */
    private void beneficiary(String policy, String name) throws Exception {
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/beneficiaries"), "admin",
                        "{\"fullName\":\"" + name + "\",\"relationship\":\"Spouse\",\"mobile\":\"+267 72 000 000\","
                                + "\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62004567\"}"))
                .andExpect(status().isOk());
        tx.executeWithoutResult(s -> em.createQuery("update PolicyBeneficiary b set b.effectiveFrom ="
                        + " (select p.coverStartDate from InsurancePolicy p where p.ref = :ref)"
                        + " where b.fullName = :name and b.policy.ref = :ref")
                .setParameter("ref", policy).setParameter("name", name).executeUpdate());
    }

    private String staffLodge(String policy, String claimant) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement(policy, claimant, 1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static String lodgement(String policy, String claimant, int daysAgo) {
        return "{\"policy\":\"" + policy + "\",\"claimantName\":\"" + claimant + "\",\"claimantMobile\":\"+267 72 000 000\","
                + "\"claimantRelationship\":\"Spouse\",\"eventDate\":\"" + LocalDate.now().minusDays(daysAgo)
                + "\",\"description\":\"Passed away at home.\"}";
    }
}

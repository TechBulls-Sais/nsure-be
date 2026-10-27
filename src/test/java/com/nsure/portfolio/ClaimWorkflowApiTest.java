package com.nsure.portfolio;

import static com.nsure.portfolio.PolicyWorkflowApiTest.json;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
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
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-claim-workflow;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ClaimWorkflowApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

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

        mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(httpBasic("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(httpBasic("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Paid"))
                .andExpect(jsonPath("$.payoutReference").value(org.hamcrest.Matchers.startsWith("PAYOUT-")))
                .andExpect(jsonPath("$.actions").isEmpty());

        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[0].action").value("Lodged"))
                .andExpect(jsonPath("$[1].action").value("Auto Approved"))
                .andExpect(jsonPath("$[1].performedBy").value("system"))
                .andExpect(jsonPath("$[2].action").value("Paid"));
    }

    @Test
    void aClaimOverTheCeilingIsAssessedThenApprovedBySomeoneElse() throws Exception {
        customer("Kagiso Molefe", "kagiso");
        String policy = activePolicy("Kagiso Molefe", 50000, 60);
        beneficiary(policy, "Naledi Molefe");

        String claim = staffLodge(policy, "Naledi Molefe");
        mockMvc.perform(get("/portfolio/claims").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].status").value("Under Review"))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].stpIneligibleReason").value(
                        "Payout of BWP 50000 is over the straight-through ceiling of BWP 5000"));

        mockMvc.perform(post("/portfolio/claims/" + claim + "/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Cannot approve a claim that is Under Review. Allowed from here: assess, reject"));

        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(httpBasic("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(httpBasic("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Recommended"))
                .andExpect(jsonPath("$.recommendedBy").value("assessor"))
                .andExpect(jsonPath("$.payee.verified").value(true));

        // Four-eyes: the assessor cannot approve their own recommendation.
        mockMvc.perform(post("/portfolio/claims/" + claim + "/approve").with(httpBasic("assessor", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{\"payoutAmount\":60000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Payout of BWP 60000 exceeds the policy cover of BWP 50000"));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{\"payoutAmount\":45000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Approved"))
                .andExpect(jsonPath("$.payoutAmount").value(45000))
                .andExpect(jsonPath("$.adjudicatedBy").value("admin"))
                .andExpect(jsonPath("$.fourEyesApplied").value(true));

        // Approved claims cannot be rejected or have their payee changed.
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "admin", "{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/portfolio/claims/" + claim + "/payee"), "assessor",
                        "{\"name\":\"Someone Else\",\"payoutMethod\":\"Bank Account\",\"accountRef\":\"1\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Paid"));
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

        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(httpBasic("assessor", "password")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/portfolio/claims/" + claim + "/payee"), "assessor",
                        "{\"name\":\"Unknown Person\",\"payoutMethod\":\"Mobile Wallet\",\"accountRef\":\"71234567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payee.source").value("Entered by Assessor"))
                .andExpect(jsonPath("$.payee.verified").value(false));
        mockMvc.perform(post("/portfolio/claims/" + claim + "/checks").with(httpBasic("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Under Review"));

        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Please enter a reason for rejecting this claim. The customer is told why."));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "onboarding", "{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor",
                        "{\"reason\":\"The event happened before cover started\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Rejected"));

        mockMvc.perform(get("/portfolio/shop").with(httpBasic("mphod", "password1")))
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The date of the event cannot be in the future"));
        mockMvc.perform(json(post("/portfolio/claims"), "onboarding", lodgement(policy, "Dikeledi Sebina", 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSuperAdminMayApproveTheirOwnAssessmentAsARecordedOverride() throws Exception {
        customer("Thato Seretse", "thato");
        String policy = activePolicy("Thato Seretse", 50000, 60);
        beneficiary(policy, "Mpho Seretse");
        String claim = staffLodge(policy, "Mpho Seretse");

        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedBy").value("admin"));
        mockMvc.perform(post("/portfolio/claims/" + claim + "/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Approved"))
                .andExpect(jsonPath("$.adjudicatedBy").value("admin"))
                .andExpect(jsonPath("$.fourEyesApplied").value(false));

        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Approved')].details").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("four-eyes overridden"))));
    }

    private void customer(String name, String username) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 001\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(body, "$.id") + "/kyc")
                        .with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
    }

    /** A staff-issued, flat-priced policy whose cover started `daysAgo` days ago and is paid up. */
    private String activePolicy(String name, int cover, int daysAgo) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"name\":\"" + name + "\",\"product\":\"Term Life\",\"amount\":" + cover + ",\"premium\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.id");
        tx.executeWithoutResult(status -> em.createQuery(
                        "update InsurancePolicy p set p.coverStartDate = :start where p.ref = :ref")
                .setParameter("start", LocalDate.now().minusDays(daysAgo))
                .setParameter("ref", ref)
                .executeUpdate());
        return ref;
    }

    private void beneficiary(String policy, String name) throws Exception {
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/beneficiaries"), "admin",
                        "{\"fullName\":\"" + name + "\",\"relationship\":\"Spouse\",\"mobile\":\"+267 72 000 000\","
                                + "\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62004567\"}"))
                .andExpect(status().isOk());
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

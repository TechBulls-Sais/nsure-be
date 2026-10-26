package com.nsure.portfolio;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The demo server's workflows on Nsure: KYC / EDD review, the policy lifecycle (Pending Payment → Active →
 * Lapsed, or Expired / Cancelled) and the claim lifecycle (checks, STP or review, assess → approve with
 * four-eyes → pay, or reject with a reason). Every seeded login's password is "password".
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class WorkflowApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void kycAndEddReviewsRecordAReasonForEveryRejection() throws Exception {
        // Naledi (CUS-1003) is pending KYC.
        mockMvc.perform(json(post("/portfolio/customers/CUS-1003/kyc"), "support", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/customers/CUS-1003/kyc"), "onboarding", "{\"decision\":\"FAILED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please enter a reason for rejecting this record"));
        mockMvc.perform(json(post("/portfolio/customers/CUS-1003/kyc"), "onboarding",
                        "{\"decision\":\"FAILED\",\"reason\":\"Omang photo is unreadable\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("KYC failed"));
        mockMvc.perform(get("/portal/me").with(httpBasic("naledi", "password")))
                .andExpect(jsonPath("$.kycVerified").value(false))
                .andExpect(jsonPath("$.kycRejectionReason").value("Omang photo is unreadable"));
        mockMvc.perform(json(put("/portfolio/customers/CUS-1003"), "admin", "{\"status\":\"Verified\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(json(post("/portfolio/customers/CUS-1003/edd"), "onboarding", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("EDD review does not apply to this customer"));

        String pep = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"Mpho Kgari\",\"email\":\"mpho@example.com\",\"phone\":\"+267 71 000 001\",\"pep\":true}"))
                .andExpect(jsonPath("$.eddStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/kyc"), "onboarding", "{}"))
                .andExpect(jsonPath("$.status").value("Verified"));
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding",
                        "{\"decision\":\"VERIFIED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eddStatus").value("VERIFIED"));
        mockMvc.perform(get("/portfolio/history/kyc/" + pep).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[0].action").value("Approved"));
    }

    @Test
    void aPortalPurchaseIsPendingPaymentUntilPaidAndCanBeCancelledExpiredOrLapsed() throws Exception {
        String policy = buy("amara", "Family Funeral");
        mockMvc.perform(json(post("/portal/purchase"), "amara", "{\"product\":\"Family Funeral\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "You already have Family Funeral awaiting payment (" + policy + "). Pay it from Your policies."));

        mockMvc.perform(json(post("/portal/policies/" + policy + "/pay"), "thabo", "{\"method\":\"Card\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(post("/portal/policies/" + policy + "/pay"), "amara", "{\"method\":\"Orange Money\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"))
                .andExpect(jsonPath("$.coverStartDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.nextPremiumDue").value(LocalDate.now().plusMonths(1).toString()));

        // Staff cancel only an unpaid policy, with a reason.
        String cancelled = buy("amara", "Care Cash");
        mockMvc.perform(json(post("/portfolio/policies/" + cancelled + "/cancel"), "compliance", "{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/policies/" + cancelled + "/cancel"), "support",
                        "{\"reason\":\"Customer asked to cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Cancelled"))
                .andExpect(jsonPath("$.statusNote").value("Customer asked to cancel"));

        // Expire: the payment window passes and a late payment is refused.
        String expired = buy("amara", "Term Life");
        update("update InsurancePolicy p set p.paymentDueBy = :value where p.ref = :ref",
                LocalDateTime.now().minusMinutes(1), expired);
        mockMvc.perform(json(post("/portal/policies/" + expired + "/pay"), "amara", "{\"method\":\"Card\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/portal/policies").with(httpBasic("amara", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + expired + "')].status").value("Expired"));

        // Lapse: Active, grace period over, nothing paid.
        update("update InsurancePolicy p set p.gracePeriodEnd = :value where p.ref = :ref",
                LocalDate.now().minusDays(1), policy);
        mockMvc.perform(post("/portfolio/policies/sweep").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lapsed[?(@ == '" + policy + "')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[0].newState").value("Pending Payment"))
                .andExpect(jsonPath("$[1].newState").value("Active"))
                .andExpect(jsonPath("$[2].newState").value("Lapsed"));
    }

    @Test
    void aClaimIsCheckedThenAssessedAndApprovedByTwoPeopleAndPaid() throws Exception {
        String policy = buy("thabo", "Whole of Life");
        mockMvc.perform(json(post("/portal/policies/" + policy + "/pay"), "thabo", "{\"method\":\"Card\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portal/policies/" + policy + "/beneficiaries"), "thabo",
                        "{\"fullName\":\"Naledi Molefe\",\"relationship\":\"Spouse\",\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62001234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beneficiaries[0].primary").value(true));
        update("update InsurancePolicy p set p.coverStartDate = :value where p.ref = :ref",
                LocalDate.now().minusDays(60), policy);

        String body = mockMvc.perform(json(post("/portal/claims"), "thabo",
                        "{\"policy\":\"" + policy + "\",\"claimantName\":\"Naledi Molefe\",\"claimantMobile\":\"+267 72 000 000\","
                                + "\"claimantRelationship\":\"Spouse\",\"eventDate\":\"" + LocalDate.now().minusDays(1)
                                + "\",\"description\":\"Passed away at home.\"}"))
                .andExpect(status().isOk())
                // Every check passes, but BWP 750,000 is over the straight-through ceiling.
                .andExpect(jsonPath("$.status").value("Under Review"))
                .andExpect(jsonPath("$.checks[?(@.result == 'Fail')]").isEmpty())
                .andExpect(jsonPath("$.stpIneligibleReason").value(
                        org.hamcrest.Matchers.startsWith("Payout of BWP 750000 is over the straight-through ceiling")))
                .andExpect(jsonPath("$.payee.name").value("Naledi Molefe"))
                .andReturn().getResponse().getContentAsString();
        String claim = JsonPath.read(body, "$.id");

        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(httpBasic("support", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(httpBasic("claims", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Recommended"));
        mockMvc.perform(post("/portfolio/claims/" + claim + "/approve").with(httpBasic("claims", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{\"payoutAmount\":700000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Approved"))
                .andExpect(jsonPath("$.payoutAmount").value(700000))
                .andExpect(jsonPath("$.fourEyesApplied").value(true));
        mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(httpBasic("claims", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Paid"));

        mockMvc.perform(get("/portal/claims").with(httpBasic("thabo", "password")))
                .andExpect(jsonPath("$[0].status").value("Paid"));
        mockMvc.perform(get("/me").with(httpBasic("claims", "password")))
                .andExpect(jsonPath("$.canAssessClaims").value(true))
                .andExpect(jsonPath("$.canManagePolicies").value(false));
    }

    @Test
    void aRejectedClaimTellsTheCustomerWhy() throws Exception {
        String claim = JsonPath.read(mockMvc.perform(json(post("/portfolio/claims"), "support",
                        "{\"policy\":\"POL-10841\",\"claimantName\":\"Someone\",\"claimantMobile\":\"+267 72 000 000\","
                                + "\"claimantRelationship\":\"Sibling\",\"eventDate\":\"" + LocalDate.now().minusDays(2)
                                + "\",\"description\":\"Hospital stay.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Under Review"))
                .andExpect(jsonPath("$.checks[?(@.name == 'Beneficiary Match')].result").value("Fail"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "claims", "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "claims",
                        "{\"reason\":\"The claimant is not a beneficiary\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Rejected"));
        mockMvc.perform(get("/portal/claims").with(httpBasic("amara", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].rejectionReason").value("The claimant is not a beneficiary"));
    }

    private String buy(String user, String product) throws Exception {
        String body = mockMvc.perform(json(post("/portal/purchase"), user, "{\"product\":\"" + product + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Pending Payment"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.policyId");
    }

    private void update(String jpql, Object value, String ref) {
        tx.executeWithoutResult(status -> em.createQuery(jpql)
                .setParameter("value", value)
                .setParameter("ref", ref)
                .executeUpdate());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(httpBasic(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

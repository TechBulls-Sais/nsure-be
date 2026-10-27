package com.nsure.portfolio;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Customer reviews (KYC, EDD) and the policy lifecycle as on the demo server: a purchase waits for its first
 * premium, goes Active when paid, expires if not paid in time, can be cancelled unpaid, and lapses after grace.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-policy-workflow;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PolicyWorkflowApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void eddIsReviewedOnlyForAFlaggedCustomerAndARejectionNeedsAReason() throws Exception {
        String plain = customer("Onkemetse Pilane", "onkemetse", false);
        mockMvc.perform(post("/portfolio/customers/" + plain + "/kyc").with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/customers/" + plain + "/edd"), "onboarding", "{\"decision\":\"VERIFIED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("EDD review does not apply to this customer"));

        String pep = customer("Mpho Kgari", "mpho", true);
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mark KYC successful before recording the EDD review."));
        mockMvc.perform(post("/portfolio/customers/" + pep + "/kyc").with(httpBasic("admin", "password")));

        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "customer", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding", "{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding", "{\"decision\":\"FAILED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please enter a reason for rejecting this record"));
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding",
                        "{\"decision\":\"FAILED\",\"reason\":\"Source of funds not evidenced\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eddStatus").value("FAILED"))
                .andExpect(jsonPath("$.eddRejectionReason").value("Source of funds not evidenced"));
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding", "{\"decision\":\"VERIFIED\"}"))
                .andExpect(jsonPath("$.eddStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.eddRejectionReason").value(""));

        mockMvc.perform(get("/portfolio/history/edd/" + pep).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("Rejected"))
                .andExpect(jsonPath("$[1].newState").value("VERIFIED"));

        mockMvc.perform(get("/me").with(httpBasic("onboarding", "password")))
                .andExpect(jsonPath("$.canReviewEdd").value(true))
                .andExpect(jsonPath("$.canManagePolicies").value(false));
        mockMvc.perform(get("/me").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.canManagePolicies").value(true));
    }

    @Test
    void aKycRejectionNeedsAReasonTheCustomerSees() throws Exception {
        String ref = customer("Tebogo Ramotswa", "tebogo", false);
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "onboarding", "{\"decision\":\"FAILED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "onboarding",
                        "{\"decision\":\"FAILED\",\"reason\":\"Omang photo is unreadable\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("FAILED"))
                .andExpect(jsonPath("$.status").value("KYC failed"));
        mockMvc.perform(get("/portfolio/shop").with(httpBasic("tebogo", "password1")))
                .andExpect(jsonPath("$.customer.kycRejectionReason").value("Omang photo is unreadable"));
    }

    @Test
    void aPurchaseIsPendingPaymentUntilTheCustomerPaysThenActive() throws Exception {
        String ref = customer("Kefilwe Mogae", "kefilwe", false);
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(httpBasic("admin", "password")));

        String policy = buy("kefilwe", "{\"product\":\"Term Life\",\"tier\":\"Silver\",\"beneficiaries\":["
                + "{\"fullName\":\"Lesego Mogae\",\"relationship\":\"Spouse\",\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62001234\"}]}");
        // Reserved, not cover: no cover dates until the first premium is collected.
        mockMvc.perform(get("/portfolio/shop").with(httpBasic("kefilwe", "password1")))
                .andExpect(jsonPath("$.policies").isEmpty())
                .andExpect(jsonPath("$.requests[0].status").value("Pending Payment"))
                .andExpect(jsonPath("$.requests[0].paymentDueBy").isNotEmpty())
                .andExpect(jsonPath("$.requests[0].coverStartDate").value(""))
                .andExpect(jsonPath("$.requests[0].beneficiaries[0].fullName").value("Lesego Mogae"))
                .andExpect(jsonPath("$.requests[0].beneficiaries[0].primary").value(true))
                .andExpect(jsonPath("$.customer.policies").value(0));

        // Spec 01 BR-8: no second open purchase for the same product and tier.
        mockMvc.perform(json(post("/portfolio/shop"), "kefilwe", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "You already have Term Life Silver awaiting payment (" + policy + "). Pay it from Your policies."));

        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), "kefilwe", "{\"method\":\"Cash\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), "kefilwe", "{\"method\":\"Orange Money\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies[0].id").value(policy))
                .andExpect(jsonPath("$.policies[0].status").value("Active"))
                .andExpect(jsonPath("$.policies[0].coverStartDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.policies[0].nextPremiumDue").value(LocalDate.now().plusMonths(1).toString()))
                .andExpect(jsonPath("$.policies[0].gracePeriodEnd").value(LocalDate.now().plusMonths(1).plusDays(14).toString()))
                .andExpect(jsonPath("$.policies[0].paymentMethod").value("Orange Money"))
                .andExpect(jsonPath("$.requests").isEmpty())
                .andExpect(jsonPath("$.customer.policies").value(1));

        // A premium on an Active policy pays the next period.
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"))
                .andExpect(jsonPath("$.nextPremiumDue").value(LocalDate.now().plusMonths(2).toString()));
        // Staff without the policy function cannot; another customer cannot see it at all.
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "onboarding", "{\"method\":\"Card\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), "customer", "{\"method\":\"Card\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[0].action").value("Created"))
                .andExpect(jsonPath("$[0].newState").value("Pending Payment"))
                .andExpect(jsonPath("$[?(@.action == 'Payment Received')].newState").value("Active"))
                .andExpect(jsonPath("$[?(@.action == 'Premium Received')].performedBy").value("admin"));
    }

    @Test
    void anUnpaidPolicyExpiresOrIsCancelledAndAnUnpaidActiveOneLapses() throws Exception {
        String ref = customer("Gaone Seretse", "gaone", true);
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(httpBasic("admin", "password")));

        // Spec 01 BR-25: no plan selection while EDD is not clear.
        mockMvc.perform(json(post("/portfolio/shop"), "gaone", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Your enhanced due diligence review must be cleared before you can request cover."));
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/edd"), "onboarding", "{\"decision\":\"VERIFIED\"}"));

        // Cancel: only unpaid, and only with a reason.
        String cancelled = buy("gaone", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}");
        mockMvc.perform(json(post("/portfolio/policies/" + cancelled + "/cancel"), "admin", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Give a reason for cancelling the policy. The customer is told why."));
        mockMvc.perform(json(post("/portfolio/policies/" + cancelled + "/cancel"), "admin",
                        "{\"reason\":\"Customer asked to cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Cancelled"))
                .andExpect(jsonPath("$.reviewNote").value("Customer asked to cancel"));
        mockMvc.perform(json(post("/portfolio/shop/policies/" + cancelled + "/pay"), "gaone", "{\"method\":\"Card\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Policy " + cancelled + " is Cancelled and can no longer be paid. Please buy a new policy."));

        // Expire: the payment window passes; a late payment is refused and the expiry sticks.
        String expired = buy("gaone", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}");
        tx.executeWithoutResult(status -> em.createQuery(
                        "update InsurancePolicy p set p.paymentDueBy = :past where p.ref = :ref")
                .setParameter("past", LocalDateTime.now().minusMinutes(1))
                .setParameter("ref", expired)
                .executeUpdate());
        mockMvc.perform(json(post("/portfolio/shop/policies/" + expired + "/pay"), "gaone", "{\"method\":\"Card\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/portfolio/shop").with(httpBasic("gaone", "password1")))
                .andExpect(jsonPath("$.requests[?(@.id == '" + expired + "')].status").value("Expired"));

        // Lapse: Active, grace period over, nothing paid.
        String lapsing = buy("gaone", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}");
        mockMvc.perform(json(post("/portfolio/shop/policies/" + lapsing + "/pay"), "gaone", "{\"method\":\"Card\"}"))
                .andExpect(status().isOk());
        tx.executeWithoutResult(status -> em.createQuery(
                        "update InsurancePolicy p set p.gracePeriodEnd = :past where p.ref = :ref")
                .setParameter("past", LocalDate.now().minusDays(1))
                .setParameter("ref", lapsing)
                .executeUpdate());
        mockMvc.perform(post("/portfolio/policies/sweep").with(httpBasic("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/policies/sweep").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lapsed[?(@ == '" + lapsing + "')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/shop").with(httpBasic("gaone", "password1")))
                .andExpect(jsonPath("$.policies[?(@.id == '" + lapsing + "')].status").value("Lapsed"))
                .andExpect(jsonPath("$.policies[?(@.id == '" + lapsing + "')].lapseDate").value(LocalDate.now().toString()));
    }

    @Test
    void beneficiariesAreLimitedToFiveAndOnlyThePolicyholderOrStaffChangeThem() throws Exception {
        String ref = customer("Naledi Phiri", "naledi", false);
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(httpBasic("admin", "password")));
        String policy = buy("naledi", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}");

        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/beneficiaries"), "naledi", "{\"fullName\":\"\"}"))
                .andExpect(status().isBadRequest());
        String body = "";
        for (int i = 1; i <= 5; i++) {
            body = mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/beneficiaries"), "naledi",
                            "{\"fullName\":\"Child " + i + "\",\"relationship\":\"Child\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        }
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/beneficiaries"), "admin",
                        "{\"fullName\":\"Child 6\",\"relationship\":\"Child\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A policy can have at most 5 beneficiaries."));

        Integer first = JsonPath.read(body, "$.requests[0].beneficiaries[0].id");
        mockMvc.perform(delete("/portfolio/shop/policies/" + policy + "/beneficiaries/" + first)
                        .with(httpBasic("customer", "password")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/portfolio/shop/policies/" + policy + "/beneficiaries/" + first)
                        .with(httpBasic("naledi", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].beneficiaries.length()").value(4))
                // The first one was primary; the next takes over.
                .andExpect(jsonPath("$.requests[0].beneficiaries[0].primary").value(true));
    }

    private String customer(String name, String username, boolean pep) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 001\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\",\"pep\":" + pep + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eddStatus").value(pep ? "PENDING" : ""))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Buys as the customer and returns the new Pending Payment policy's reference. */
    private String buy(String username, String request) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/shop"), username, request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].status").value("Pending Payment"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.requests[0].id");
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = user.equals("admin") || user.equals("onboarding") || user.equals("customer")
                || user.equals("assessor") ? "password" : "password1";
        return request.with(httpBasic(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

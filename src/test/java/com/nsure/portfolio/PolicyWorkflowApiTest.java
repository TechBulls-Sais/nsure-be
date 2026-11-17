package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
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
        mockMvc.perform(post("/portfolio/customers/" + plain + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/customers/" + plain + "/edd"), "onboarding", "{\"decision\":\"VERIFIED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("EDD review does not apply to this customer"));

        String pep = customer("Mpho Kgari", "mpho", true);
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding", "{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Mark KYC successful before recording the EDD review."));
        mockMvc.perform(post("/portfolio/customers/" + pep + "/kyc").with(signedIn("admin", "password")));

        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "customer", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding", "{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding", "{\"decision\":\"FAILED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Please enter a reason for rejecting this record"));
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding",
                        "{\"decision\":\"FAILED\",\"reason\":\"Source of funds not evidenced\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eddStatus").value("FAILED"))
                .andExpect(jsonPath("$.eddRejectionReason").value("Source of funds not evidenced"));
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/edd"), "onboarding", "{\"decision\":\"VERIFIED\"}"))
                .andExpect(jsonPath("$.eddStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.eddRejectionReason").value(""));

        mockMvc.perform(get("/portfolio/history/edd/" + pep).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("Rejected"))
                .andExpect(jsonPath("$[1].newState").value("VERIFIED"));

        mockMvc.perform(get("/me").with(signedIn("onboarding", "password")))
                .andExpect(jsonPath("$.canReviewEdd").value(true))
                .andExpect(jsonPath("$.canManagePolicies").value(false));
        mockMvc.perform(get("/me").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.canManagePolicies").value(true));
    }

    @Test
    void aKycRejectionNeedsAReasonTheCustomerSees() throws Exception {
        String ref = customer("Tebogo Ramotswa", "tebogo", false);
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "onboarding", "{\"decision\":\"FAILED\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "onboarding",
                        "{\"decision\":\"FAILED\",\"reason\":\"Omang photo is unreadable\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("FAILED"))
                .andExpect(jsonPath("$.status").value("KYC failed"));
        mockMvc.perform(get("/portfolio/shop").with(signedIn("tebogo", "password1")))
                .andExpect(jsonPath("$.customer.kycRejectionReason").value("Omang photo is unreadable"));
    }

    @Test
    void aPurchaseIsPendingPaymentUntilTheCustomerPaysThenActive() throws Exception {
        String ref = customer("Kefilwe Mogae", "kefilwe", false);
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")));

        String policy = buy("kefilwe", "{\"product\":\"Term Life\",\"tier\":\"Silver\",\"beneficiaries\":["
                + "{\"fullName\":\"Lesego Mogae\",\"relationship\":\"Spouse\",\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62001234\"}]}");
        // Reserved, not cover: no cover dates until the first premium is collected.
        mockMvc.perform(get("/portfolio/shop").with(signedIn("kefilwe", "password1")))
                .andExpect(jsonPath("$.policies").isEmpty())
                .andExpect(jsonPath("$.requests[0].status").value("Pending Payment"))
                .andExpect(jsonPath("$.requests[0].paymentDueBy").isNotEmpty())
                .andExpect(jsonPath("$.requests[0].coverStartDate").value(""))
                .andExpect(jsonPath("$.requests[0].beneficiaries[0].fullName").value("Lesego Mogae"))
                .andExpect(jsonPath("$.requests[0].beneficiaries[0].primary").value(true))
                .andExpect(jsonPath("$.customer.policies").value(0));

        // Spec 01 BR-8: re-entering checkout reuses the unexpired pending policy; no duplicate is created.
        mockMvc.perform(json(post("/portfolio/shop"), "kefilwe", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].id").value(policy));

        // Spec 01 BR-5: no payment before the OTP-verified declaration.
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), "kefilwe", "{\"method\":\"Orange Money\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Complete the declaration and consent step before paying for " + policy + "."));
        declare("kefilwe", policy);
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), "kefilwe", "{\"method\":\"Cash\"}"))
                .andExpect(status().isUnprocessableEntity());
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

        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].action").value("Created"))
                .andExpect(jsonPath("$[0].newState").value("Pending Payment"))
                .andExpect(jsonPath("$[?(@.action == 'Payment Received')].newState").value("Active"))
                .andExpect(jsonPath("$[?(@.action == 'Premium Received')].performedBy").value("admin"));
    }

    @Test
    void anUnpaidPolicyExpiresOrIsCancelledAndAnUnpaidActiveOneLapses() throws Exception {
        String ref = customer("Gaone Seretse", "gaone", true);
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")));

        // Spec 01 BR-25: no plan selection while EDD is not clear.
        mockMvc.perform(json(post("/portfolio/shop"), "gaone", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Your enhanced due diligence review must be cleared before you can request cover."));
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/edd"), "onboarding", "{\"decision\":\"VERIFIED\"}"));

        // Cancel: only unpaid, and only with a reason.
        String cancelled = buy("gaone", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}");
        mockMvc.perform(json(post("/portfolio/policies/" + cancelled + "/cancel"), "admin", "{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Give a reason for cancelling the policy. The customer is told why."));
        mockMvc.perform(json(post("/portfolio/policies/" + cancelled + "/cancel"), "admin",
                        "{\"reason\":\"Customer asked to cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Cancelled"))
                .andExpect(jsonPath("$.reviewNote").value("Customer asked to cancel"));
        mockMvc.perform(json(post("/portfolio/shop/policies/" + cancelled + "/pay"), "gaone", "{\"method\":\"Card\"}"))
                .andExpect(status().isUnprocessableEntity())
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
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/portfolio/shop").with(signedIn("gaone", "password1")))
                .andExpect(jsonPath("$.requests[?(@.id == '" + expired + "')].status").value("Expired"));

        // Lapse: Active, grace period over, nothing paid.
        String lapsing = buy("gaone", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}");
        declare("gaone", lapsing);
        mockMvc.perform(json(post("/portfolio/shop/policies/" + lapsing + "/pay"), "gaone", "{\"method\":\"Card\"}"))
                .andExpect(status().isOk());
        tx.executeWithoutResult(status -> em.createQuery(
                        "update InsurancePolicy p set p.gracePeriodEnd = :past where p.ref = :ref")
                .setParameter("past", LocalDate.now().minusDays(1))
                .setParameter("ref", lapsing)
                .executeUpdate());
        mockMvc.perform(post("/portfolio/policies/sweep").with(signedIn("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/policies/sweep").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lapsed[?(@ == '" + lapsing + "')]").isNotEmpty());
        // Spec 10 W-5 / TC-18: grace over → in force without cover for 3 months (lapse comes after that window).
        mockMvc.perform(get("/portfolio/shop").with(signedIn("gaone", "password1")))
                .andExpect(jsonPath("$.policies[?(@.id == '" + lapsing + "')].status").value("Active - No Cover"));
    }

    @Test
    void beneficiariesAreLimitedToFiveAndOnlyThePolicyholderOrStaffChangeThem() throws Exception {
        String ref = customer("Naledi Phiri", "naledi", false);
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")));
        String policy = buy("naledi", "{\"product\":\"Term Life\",\"tier\":\"Silver\"}");

        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/beneficiaries"), "naledi", "{\"fullName\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
        String body = "";
        for (int i = 1; i <= 5; i++) {
            body = mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/beneficiaries"), "naledi",
                            "{\"fullName\":\"Child " + i + "\",\"relationship\":\"Child\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        }
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/beneficiaries"), "admin",
                        "{\"fullName\":\"Child 6\",\"relationship\":\"Child\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("A Term Life policy can have at most 5 beneficiaries."));

        Integer first = JsonPath.read(body, "$.requests[0].beneficiaries[0].id");
        mockMvc.perform(delete("/portfolio/shop/policies/" + policy + "/beneficiaries/" + first)
                        .with(signedIn("customer", "password")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/portfolio/shop/policies/" + policy + "/beneficiaries/" + first)
                        .with(signedIn("naledi", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].beneficiaries.length()").value(4))
                // The first one was primary; the next takes over.
                .andExpect(jsonPath("$.requests[0].beneficiaries[0].primary").value(true));
    }

    @Test
    void staffIssueIsAnOfferForTheRightCustomerAndIsSafeToResubmit() throws Exception {
        // NS-SH01-001 / AT-SH01-01: two customers share a name; staff must say which one by reference.
        String first = customer("Neo Twin Mosweu", "neotwin1", false);
        String second = customer("Neo Twin Mosweu", "neotwin2", false);
        for (String ref : new String[] {first, second}) {
            mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"name\":\"Neo Twin Mosweu\",\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("by reference")));

        // NS-IL01-006: issued from an application, Pending Payment whatever status the client sends, and idempotent.
        String application = mockMvc.perform(json(post("/portfolio/applications"), "admin",
                        "{\"declarationAccepted\":true,\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"},\"customerRef\":\"" + second + "\",\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String app = JsonPath.read(application, "$.id");
        String issueBody = "{\"customerRef\":\"" + second + "\",\"applicationRef\":\"" + app
                + "\",\"product\":\"Term Life\",\"tier\":\"Silver\",\"status\":\"Active\"}";
        // §10: naming an offer other than the application's accepted quote version is refused.
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issueBody.replace("}",
                        ",\"acceptedOfferId\":\"" + JsonPath.read(application, "$.quoteRef")
                                + "\",\"acceptedOfferVersion\":9}")))
                .andExpect(status().isConflict());
        String issued = mockMvc.perform(json(post("/portfolio/policies"), "admin", issueBody)
                        .header("Idempotency-Key", "issue-" + app))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Pending Payment"))
                .andReturn().getResponse().getContentAsString();
        String policy = JsonPath.read(issued, "$.id");
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issueBody).header("Idempotency-Key", "issue-" + app))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(policy));
        // The same key on a different request is a conflict, not the earlier policy (§10 issue contract).
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issueBody.replace("Silver", "Gold"))
                        .header("Idempotency-Key", "issue-" + app))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        Long requested = tx.execute(st -> em.createQuery("select count(e) from OutboxEvent e where e.eventType = "
                + "'IssueRequested' and e.aggregateRef = :ref", Long.class).setParameter("ref", app).getSingleResult());
        org.assertj.core.api.Assertions.assertThat(requested).isEqualTo(1L);
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issueBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(policy)));
        mockMvc.perform(get("/portfolio/applications").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + app + "')].status").value(org.hamcrest.Matchers.hasItem("Issued")));

        // The first premium issues cover through the normal path, which raises PolicyIssued.
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
        Long issuedEvents = tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType = "
                + "'PolicyIssued' and e.aggregateRef = :ref", Long.class).setParameter("ref", policy).getSingleResult());
        String owner = tx.execute(s -> em.createQuery("select p.customerRef from InsurancePolicy p where p.ref = :ref",
                String.class).setParameter("ref", policy).getSingleResult());
        org.assertj.core.api.Assertions.assertThat(issuedEvents).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(owner).isEqualTo(second);
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

    /** Spec 01 BR-5: the OTP-verified declaration with all four consents, before the first premium. */
    private void declare(String user, String policy) throws Exception {
        String sent = mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration/otp"), user, "{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(sent, "$.devCode");
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration"), user, "{\"code\":\"" + code
                        + "\",\"accuracy\":true,\"terms\":true,\"dataProtection\":true,\"falseInformation\":true}"))
                .andExpect(status().isOk());
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = user.equals("admin") || user.equals("secadmin") || user.equals("onboarding") || user.equals("customer")
                || user.equals("assessor") || user.equals("assessor2") ? "password" : "password1";
        return request.with(signedIn(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

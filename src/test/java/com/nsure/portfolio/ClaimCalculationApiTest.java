package com.nsure.portfolio;

import static com.nsure.portfolio.PolicyWorkflowApiTest.json;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NS-SH05-002, AT-SH05-01, NS-SH03-004/005, NS-SH05-001/004: a late-reported claim pays the cover in force on the
 * event date (not today's), its eligible amount deducts premium unpaid at the event, a partial approval needs a
 * reason, a policy with underwriting terms needs an exclusion note, the source of the notification is kept, and the
 * decision keeps its calculation trace with an input hash.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-claim-calculation;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ClaimCalculationApiTest {

    private static final String EVIDENCE = "\"evidenceOverrideReason\":\"Certificate seen at the branch; copy to follow\"";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aLateClaimPaysTheCoverInForceOnTheEventDateLessUnpaidPremium() throws Exception {
        String policy = policy("Mothusi Late", "mothusilate", "Kelebogile Late", 60000);
        // The cover was raised from 40,000 to 60,000 by an endorsement effective five days ago, after the event.
        jdbc.update("insert into pol_endorsement (record_ref, policy_ref, endorsement_type, effective_date, backdated,"
                + " status, reason, changes_json, old_sum_assured, new_sum_assured, requested_by, requested_at, applied_at,"
                + " row_version) values ('END-T001', ?, 'SUM_ASSURED', ?, false, 'APPLIED', 'Increase', '{}', 40000, 60000,"
                + " 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)", policy, LocalDate.now().minusDays(5));
        // One premium of 50 fell due three days before the event and was not paid (the event was within grace).
        tx.executeWithoutResult(s -> em.createQuery("update InsurancePolicy p set p.nextPremiumDue = :due,"
                        + " p.gracePeriodEnd = :grace where p.ref = :ref")
                .setParameter("due", LocalDate.now().minusDays(13)).setParameter("grace", LocalDate.now().plusDays(5))
                .setParameter("ref", policy).executeUpdate());
        String lodged = mockMvc.perform(json(post("/portfolio/claims"), "assessor",
                        "{\"policy\":\"" + policy + "\",\"source\":\"phone\",\"claimantName\":\"Kelebogile Late\","
                                + "\"claimantMobile\":\"+267 72 000 000\",\"claimantRelationship\":\"Spouse\",\"eventDate\":\""
                                + LocalDate.now().minusDays(10) + "\",\"description\":\"Passed away in hospital.\"}"))
                .andExpect(status().isOk())
                // AT-SH05-01: the cover on the event date, not the policy's amount today.
                .andExpect(jsonPath("$.amount").value(40000.0))
                .andExpect(jsonPath("$.notificationSource").value("PHONE"))
                .andReturn().getResponse().getContentAsString();
        String claim = JsonPath.read(lodged, "$.id");
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.calculation.sumAssured").value(40000.0))
                .andExpect(jsonPath("$.calculation.deductions").value(50.0))
                .andExpect(jsonPath("$.calculation.eligible").value(39950.0))
                .andExpect(jsonPath("$.calculation.mostPayable").value(39950.0));
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        // More than the eligible amount is refused; less than it needs a reason (partial approval).
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                        "{\"payoutAmount\":40000," + EVIDENCE + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("eligible amount")));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                        "{\"payoutAmount\":30000," + EVIDENCE + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("reason for the partial approval")));
        // With no amount, the eligible amount is approved, and the decision keeps its calculation trace.
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{" + EVIDENCE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutAmount").value(39950.0));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.decisions[0].decision").value("PARTIALLY_APPROVED"))
                .andExpect(jsonPath("$.decisions[0].reason").value(Matchers.containsString("Deducted")))
                .andExpect(jsonPath("$.decisions[0].calculation.eligible").value("39950.00"))
                .andExpect(jsonPath("$.decisions[0].calculation.inputHash").value(Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.limitEntries[0].entry").value("RESERVED"));
    }

    @Test
    void aPolicyWithUnderwritingTermsNeedsAnExclusionNoteAndIsNeverStraightThrough() throws Exception {
        String policy = policy("Boitshepo Terms", "boitshepoterms", "Lesego Terms", 5000);
        // NS-SH03-003: a policy saved without a plan carries a manual, uncalculated premium on its trail.
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Created')].details").value(org.hamcrest.Matchers.hasItem(
                        Matchers.containsString("manual premium, not calculated"))));
        tx.executeWithoutResult(s -> em.createQuery("update InsurancePolicy p set p.uwTerms = :terms where p.ref = :ref")
                .setParameter("terms", "Exclusion: hazardous sports").setParameter("ref", policy).executeUpdate());
        String lodged = mockMvc.perform(json(post("/portfolio/claims"), "assessor",
                        "{\"policy\":\"" + policy + "\",\"claimantName\":\"Lesego Terms\",\"claimantMobile\":\"+267 72 000 000\","
                                + "\"claimantRelationship\":\"Spouse\",\"eventDate\":\"" + LocalDate.now().minusDays(1)
                                + "\",\"description\":\"Passed away at home.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Under Review"))
                .andExpect(jsonPath("$.stpIneligibleCode").value("UNDERWRITING_TERMS"))
                .andExpect(jsonPath("$.notificationSource").value("STAFF"))
                .andReturn().getResponse().getContentAsString();
        String claim = JsonPath.read(lodged, "$.id");
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin", "{" + EVIDENCE + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("not excluded")));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                        "{\"exclusionNote\":\"Natural causes per the death certificate\"," + EVIDENCE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusionNote").value("Natural causes per the death certificate"));
        mockMvc.perform(json(post("/portfolio/claims"), "assessor",
                        "{\"policy\":\"" + policy + "\",\"source\":\"carrier pigeon\",\"claimantName\":\"Lesego Terms\","
                                + "\"claimantMobile\":\"+267 72 000 000\",\"claimantRelationship\":\"Spouse\",\"eventDate\":\""
                                + LocalDate.now().minusDays(1) + "\",\"description\":\"Duplicate.\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void relatedClaimsAreLinkedAsTheSameEventOrSeparateBenefits() throws Exception {
        String policy = policy("Ditiro Links", "ditirolinks", "Mmoloki Links", 30000);
        String lodge = "{\"policy\":\"" + policy + "\",\"claimantName\":\"Mmoloki Links\",\"claimantMobile\":\"+267 72 000 000\","
                + "\"claimantRelationship\":\"Spouse\",\"eventDate\":\"" + LocalDate.now().minusDays(2) + "\",\"type\":\"%s\","
                + "\"description\":\"Accident on the A1.\"}";
        String first = JsonPath.read(mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodge.formatted("Death benefit")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        String second = JsonPath.read(mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodge.formatted("Funeral benefit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.related[0].possibleDuplicate").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/claims/" + second + "/links"), "assessor",
                        "{\"relatedRef\":\"" + first + "\",\"type\":\"MAYBE\",\"note\":\"Not sure\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/portfolio/claims/" + second + "/links"), "assessor",
                        "{\"relatedRef\":\"" + first + "\",\"type\":\"SEPARATE_BENEFIT\",\"note\":\"Funeral benefit beside the death benefit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.related[0].link").value("SEPARATE_BENEFIT"))
                .andExpect(jsonPath("$.related[0].possibleDuplicate").value(false));
        // The link is kept for both claims, and is made once.
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + first + "')].related[0].link").value(Matchers.hasItem("SEPARATE_BENEFIT")));
        mockMvc.perform(json(post("/portfolio/claims/" + first + "/links"), "assessor",
                        "{\"relatedRef\":\"" + second + "\",\"type\":\"SAME_EVENT\",\"note\":\"Changed my mind\"}"))
                .andExpect(status().isConflict());
    }

        /** A paid-up Term Life policy whose cover started 60 days ago, with a registered beneficiary. */
    private String policy(String name, String username, String beneficiary, int cover) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 001\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                .with(signedIn("admin", "password"))).andExpect(status().isOk());
        String body = mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"name\":\"" + name + "\",\"product\":\"Term Life\",\"amount\":" + cover + ",\"premium\":50}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String policy = JsonPath.read(body, "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        tx.executeWithoutResult(s -> em.createQuery("update InsurancePolicy p set p.coverStartDate = :start where p.ref = :ref")
                .setParameter("start", LocalDate.now().minusDays(60)).setParameter("ref", policy).executeUpdate());
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/beneficiaries"), "admin",
                        "{\"fullName\":\"" + beneficiary + "\",\"relationship\":\"Spouse\",\"mobile\":\"+267 72 000 000\","
                                + "\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62004567\"}"))
                .andExpect(status().isOk());
        tx.executeWithoutResult(s -> em.createQuery("update PolicyBeneficiary b set b.effectiveFrom ="
                        + " (select p.coverStartDate from InsurancePolicy p where p.ref = :ref)"
                        + " where b.fullName = :name and b.policy.ref = :ref")
                .setParameter("ref", policy).setParameter("name", beneficiary).executeUpdate());
        return policy;
    }
}

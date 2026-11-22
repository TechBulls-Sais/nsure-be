package com.nsure.portfolio;

import static com.nsure.portfolio.PolicyWorkflowApiTest.json;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NS-SH05-007, §09 payment state machine, NS-TXN-003, AT-SH05-02 and AT-SH05-03 on the simulated payout rail: the
 * instruction is created with the approval, the bank's answers move it (and the claim) exactly once, a rejection or a
 * return leaves the claim unpaid in the payout exception queue, and an unknown outcome is reconciled before a retry.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-claim-payout-rail;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ClaimPayoutRailApiTest {

    private static final String EVIDENCE = "\"evidenceOverrideReason\":\"Certificate seen at the branch; copy to follow\"";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void aBankRejectionLeavesTheClaimUnpaidInTheExceptionQueueAndARepeatedCallbackPaysOnce() throws Exception {
        String claim = approvedClaim("Lesedi Rail", "lesedirail", "Mpho Rail", 20000);
        // NS-TXN-003: the approval created and approved the instruction in its own transaction.
        String listed = mockMvc.perform(get("/payments/payouts?source=CLAIM&ref=" + claim).with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].attempt").value(1))
                .andExpect(jsonPath("$[0].amount").value(20000.0))
                .andExpect(jsonPath("$[0].idempotencyKey").value("CLAIM:" + claim + ":1"))
                .andReturn().getResponse().getContentAsString();
        String first = JsonPath.read(listed, "$[0].ref");

        mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Payout Sent"))
                .andExpect(jsonPath("$.payoutReference").value(first));
        mockMvc.perform(get("/payments/payouts/" + first).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.bankStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.providerReference").value(Matchers.startsWith("SB-")));

        // AT-SH05-03: the bank rejects it. The claim stays unpaid; the instruction waits in the exception queue.
        mockMvc.perform(json(post("/payments/payouts/" + first + "/simulator"), "admin",
                        "{\"action\":\"REJECT\",\"reasonCode\":\"AC04\",\"reasonText\":\"Account closed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"))
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].status").value("Payout Failed"))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].payee.verified").value(false));
        mockMvc.perform(get("/payments/payouts/exceptions").with(signedIn("assessor", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + first + "')].failureCode").value("AC04"));
        assertThat(events("ClaimPaid", claim)).isZero();
        assertThat(events("PayoutFailed", first)).isEqualTo(1);

        // Corrected, verified payee; the resend is a new instruction that replaces the failed one.
        mockMvc.perform(json(put("/portfolio/claims/" + claim + "/payee"), "assessor",
                        "{\"name\":\"Mpho Rail\",\"relationship\":\"Spouse\",\"payoutMethod\":\"Bank Account\","
                                + "\"accountRef\":\"62009988\",\"verified\":true}"))
                .andExpect(status().isOk());
        String resent = mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = JsonPath.read(resent, "$.payoutReference");
        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(get("/payments/payouts/" + second).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.replacesRef").value(first));
        mockMvc.perform(get("/payments/payouts/" + first).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.exceptionState").value("RESOLVED"));

        // The bank settles it, then sends the same callback again: one payment (AT-SH05-02).
        mockMvc.perform(json(post("/payments/payouts/" + second + "/simulator"), "admin", "{\"action\":\"SETTLE\"}"))
                .andExpect(jsonPath("$.result").value("APPLIED"))
                .andExpect(jsonPath("$.status").value("SETTLED"));
        mockMvc.perform(json(post("/payments/payouts/" + second + "/simulator"), "admin", "{\"action\":\"REPEAT\"}"))
                .andExpect(jsonPath("$.result").value("DUPLICATE"));
        String bank = JsonPath.read(mockMvc.perform(get("/payments/payouts/" + second).with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString(), "$.providerReference");
        String eventId = JsonPath.read(mockMvc.perform(get("/payments/payouts/" + second).with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString(), "$.callbacks[0].eventId");
        mockMvc.perform(json(post("/payments/payouts/callback"), "admin",
                        "{\"eventId\":\"" + eventId + "\",\"providerReference\":\"" + bank + "\",\"outcome\":\"SETTLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DUPLICATE"));
        // A callback that contradicts the settlement changes nothing; it is an exception for a person.
        mockMvc.perform(json(post("/payments/payouts/callback"), "admin",
                        "{\"eventId\":\"late-bounce-1\",\"providerReference\":\"" + bank + "\",\"outcome\":\"REJECTED\"}"))
                .andExpect(jsonPath("$.result").value("CONFLICT"))
                .andExpect(jsonPath("$.status").value("SETTLED"));
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].status").value("Paid"));
        assertThat(events("ClaimPaid", claim)).isEqualTo(1);
        assertThat(events("ClaimPaymentSettled", claim)).isEqualTo(1);
        // Only a payments user may record a callback or drive the simulated bank.
        mockMvc.perform(json(post("/payments/payouts/callback"), "assessor",
                        "{\"eventId\":\"x\",\"providerReference\":\"" + bank + "\",\"outcome\":\"SETTLED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/payments/payouts/" + second + "/simulator"), "onboarding", "{\"action\":\"REPEAT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownOutcomeIsReconciledWithTheBankBeforeAnyRetry() throws Exception {
        String claim = approvedClaim("Kago Unknown", "kagounknown", "Lone Unknown", 15000);
        String sent = mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String instruction = JsonPath.read(sent, "$.payoutReference");
        // The bank pays but its callback is lost; then the answer window runs out.
        mockMvc.perform(json(post("/payments/payouts/" + instruction + "/simulator"), "admin",
                        "{\"action\":\"SETTLE_SILENTLY\"}"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(json(post("/payments/payouts/" + instruction + "/simulator"), "admin", "{\"action\":\"TIMEOUT\"}"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
        mockMvc.perform(get("/payments/payouts/exceptions").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + instruction + "')].status").value("UNKNOWN"));
        // No retry and no failure notice while the outcome is unknown: it may already be paid.
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/payout-failed"), "admin", "{\"reason\":\"Gave up\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Reconcile")));
        mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity());
        // The status enquiry finds the settlement and applies it once.
        mockMvc.perform(post("/payments/payouts/" + instruction + "/reconcile").with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"))
                .andExpect(jsonPath("$.status").value("SETTLED"));
        mockMvc.perform(get("/payments/payouts/" + instruction).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.exceptionState").value("RESOLVED"));
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].status").value("Paid"));
        assertThat(events("ClaimPaid", claim)).isEqualTo(1);
    }

    @Test
    void aReturnedSettledPaymentReversesItsConsumptionAndNeedsANewInstructionBeforeClosing() throws Exception {
        String claim = approvedClaim("Onalenna Return", "onalennareturn", "Kefi Return", 12000);
        String sent = mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String first = JsonPath.read(sent, "$.payoutReference");
        mockMvc.perform(json(post("/payments/payouts/" + first + "/simulator"), "admin", "{\"action\":\"SETTLE\"}"))
                .andExpect(jsonPath("$.status").value("SETTLED"));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lifeUsed").value(12000.0))
                .andExpect(jsonPath("$.reserve").value(0.0));

        // §09: the settled payment comes back. Unpaid again, consumption reversed, amount reserved again.
        mockMvc.perform(json(post("/payments/payouts/" + first + "/simulator"), "admin", "{\"action\":\"RETURN\"}"))
                .andExpect(jsonPath("$.status").value("RETURNED"));
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].status").value("Payout Failed"));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lifeUsed").value(12000.0))
                .andExpect(jsonPath("$.reserve").value(12000.0));
        assertThat(events("ClaimPaymentReturned", claim)).isEqualTo(1);

        // A new instruction to verified details, settled: paid once more, and the limit is used once, not twice.
        mockMvc.perform(json(put("/portfolio/claims/" + claim + "/payee"), "assessor",
                        "{\"name\":\"Kefi Return\",\"relationship\":\"Spouse\",\"payoutMethod\":\"Orange Money\","
                                + "\"accountRef\":\"71234567\",\"verified\":true}"))
                .andExpect(status().isOk());
        String resent = mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = JsonPath.read(resent, "$.payoutReference");
        mockMvc.perform(json(post("/payments/payouts/" + second + "/simulator"), "admin", "{\"action\":\"SETTLE\"}"))
                .andExpect(jsonPath("$.status").value("SETTLED"));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/financials").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lifeUsed").value(12000.0))
                .andExpect(jsonPath("$.reserve").value(0.0));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/close"), "assessor", "{}"))
                .andExpect(status().isOk());
    }

    @Test
    void aClaimIsNotClosedWhileAPayoutExceptionIsOpen() throws Exception {
        String claim = approvedClaim("Dineo Close", "dineoclose", "Tau Close", 9000);
        String sent = mockMvc.perform(post("/portfolio/claims/" + claim + "/pay").with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String instruction = JsonPath.read(sent, "$.payoutReference");
        mockMvc.perform(json(post("/payments/payouts/" + instruction + "/simulator"), "admin", "{\"action\":\"SETTLE\"}"))
                .andExpect(status().isOk());
        String bank = JsonPath.read(mockMvc.perform(get("/payments/payouts/" + instruction)
                .with(signedIn("admin", "password"))).andReturn().getResponse().getContentAsString(), "$.providerReference");
        mockMvc.perform(json(post("/payments/payouts/callback"), "admin",
                        "{\"eventId\":\"odd-1\",\"providerReference\":\"" + bank + "\",\"outcome\":\"REJECTED\"}"))
                .andExpect(jsonPath("$.result").value("CONFLICT"));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/close"), "assessor", "{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("payout exception")));
        mockMvc.perform(json(post("/payments/payouts/" + instruction + "/resolve"), "assessor",
                        "{\"note\":\"Bank confirmed the rejection notice was sent in error\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptionState").value("RESOLVED"));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/close"), "assessor", "{}"))
                .andExpect(status().isOk());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** A claim lodged, assessed and approved for {@code payout} on a fresh, paid-up policy. */
    private String approvedClaim(String name, String username, String beneficiary, int payout) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 001\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                .with(signedIn("admin", "password"))).andExpect(status().isOk());
        String body = mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"name\":\"" + name + "\",\"product\":\"Term Life\",\"amount\":50000,\"premium\":50}"))
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
        String lodged = mockMvc.perform(json(post("/portfolio/claims"), "assessor",
                        "{\"policy\":\"" + policy + "\",\"claimantName\":\"" + beneficiary + "\",\"claimantMobile\":"
                                + "\"+267 72 000 000\",\"claimantRelationship\":\"Spouse\",\"eventDate\":\""
                                + LocalDate.now().minusDays(1) + "\",\"description\":\"Passed away at home.\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String claim = JsonPath.read(lodged, "$.id");
        mockMvc.perform(post("/portfolio/claims/" + claim + "/assess").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/approve"), "admin",
                        "{\"payoutAmount\":" + payout + "," + EVIDENCE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Approved"));
        return claim;
    }

    private long events(String type, String aggregate) {
        return tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType = :t"
                + " and e.aggregateRef = :a", Long.class).setParameter("t", type).setParameter("a", aggregate)
                .getSingleResult());
    }
}

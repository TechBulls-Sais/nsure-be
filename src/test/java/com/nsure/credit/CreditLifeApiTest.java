package com.nsure.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** IL05 Individual credit life (NS-IL05-001..006, AT-IL05-01..03). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-credit;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CreditLifeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void coverNeedsBothTheDisbursementAndTheFirstPremium() throws Exception {
        String borrower = customer("Kefilwe Borrower", 40);
        String body = loan(borrower, "LSH-1001", 120000, 36, "SINGLE", "");
        String created = mockMvc.perform(json(post("/credit/loans"), "credit", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.monthlyPremium").value(102.0))
                .andExpect(jsonPath("$.singlePremium").value(3672.0))
                .andExpect(jsonPath("$.permanentDisability").value(true))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(created, "$.ref");
        String policy = JsonPath.read(created, "$.policyRef");

        // The same loan sent again is the same loan; different terms under the same id are refused.
        mockMvc.perform(json(post("/credit/loans"), "credit", body))
                .andExpect(jsonPath("$.ref").value(ref))
                .andExpect(jsonPath("$.policyRef").value(policy));
        mockMvc.perform(json(post("/credit/loans"), "credit", loan(borrower, "LSH-1001", 150000, 36, "SINGLE", "")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from crd_loan where lender_loan_id = 'LSH-1001'", Long.class))
                .isEqualTo(1);

        // AT-IL05-01: a disbursement notice without the first premium does not activate cover.
        mockMvc.perform(json(post("/credit/loans/" + ref + "/balances"), "credit",
                        "{\"sourceReference\":\"S-0\",\"effectiveDate\":\"" + LocalDate.now() + "\",\"balance\":100000}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/credit/loans/" + ref + "/disbursement"), "credit",
                        "{\"disbursementDate\":\"" + LocalDate.now() + "\",\"reference\":\"DISB-1001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PREMIUM"))
                .andExpect(jsonPath("$.coverStart").doesNotExist());
        assertThat(events("LoanDisbursed", ref)).isEqualTo(1);
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/credit/loans/" + ref).with(signedIn("credit", "password")))
                .andExpect(jsonPath("$.loan.status").value("COVER_ACTIVE"))
                .andExpect(jsonPath("$.loan.coverStart").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.loan.coverEnd").value(LocalDate.now().plusMonths(36).toString()));

        // NS-IL05-002: term and cessation age come from the product rule; secured loans may run longer.
        mockMvc.perform(json(post("/credit/loans"), "credit", loan(borrower, "LSH-1002", 50000, 72, "REGULAR", "")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("6–60 months")));
        String older = customer("Mothusi Older", 63);
        mockMvc.perform(json(post("/credit/loans"), "credit", loan(older, "LSH-1003", 50000, 36, "REGULAR", "")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("cessation age of 65")));
        mockMvc.perform(json(post("/credit/loans"), "credit", loan(older, "LSH-1003", 50000, 36, "REGULAR", ",\"secured\":true")))
                .andExpect(status().isOk());
    }

    @Test
    void aRepeatedBalanceChangesNothingAndAConflictBlocksSettlement() throws Exception {
        String[] active = activeLoan("Oratile Balance", "LSH-2001", 120000, 36, "REGULAR", LocalDate.now().minusDays(100));
        String ref = active[0];
        String statement = "{\"sourceReference\":\"STMT-2026-10\",\"effectiveDate\":\"" + LocalDate.now().minusDays(30)
                + "\",\"balance\":110000}";
        mockMvc.perform(json(post("/credit/loans/" + ref + "/balances"), "credit", statement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loan.balance").value(110000.0))
                .andExpect(jsonPath("$.balances.length()").value(2));
        mockMvc.perform(json(post("/credit/loans/" + ref + "/balances"), "credit", statement))
                .andExpect(jsonPath("$.balances.length()").value(2));
        assertThat(events("LoanBalanceUpdated", ref)).isEqualTo(1);

        // NS-IL05-004: an older balance never replaces a newer one; it is kept as an exception.
        mockMvc.perform(json(post("/credit/loans/" + ref + "/balances"), "credit", "{\"sourceReference\":\"STMT-2026-09\","
                        + "\"effectiveDate\":\"" + LocalDate.now().minusDays(60) + "\",\"balance\":115000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loan.balance").value(110000.0))
                .andExpect(jsonPath("$.loan.balanceException").value(containsString("older than the balance held")))
                .andExpect(jsonPath("$.settlementToday.blocked").value(true));
        mockMvc.perform(json(post("/credit/loans/" + ref + "/settlement"), "credit",
                        "{\"settlementReference\":\"SET-2001\",\"settlementDate\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("balance exception is open")));
        mockMvc.perform(json(post("/credit/loans/" + ref + "/balance-exception/resolve"), "credit",
                        "{\"note\":\"Lender confirmed October statement is correct\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loan.balanceException").doesNotExist());

        // A lender file is answered row by row.
        mockMvc.perform(json(post("/credit/lenders/LND-0001/balances"), "credit", "{\"batchReference\":\"FILE-11\",\"rows\":["
                        + "{\"lenderLoanId\":\"LSH-2001\",\"effectiveDate\":\"" + LocalDate.now().minusDays(2) + "\",\"balance\":104000},"
                        + "{\"lenderLoanId\":\"LSH-9999\",\"effectiveDate\":\"" + LocalDate.now() + "\",\"balance\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.unknown").value(1));
        mockMvc.perform(json(post("/credit/lenders/LND-0001/balances"), "credit", "{\"batchReference\":\"FILE-11\",\"rows\":["
                        + "{\"lenderLoanId\":\"LSH-2001\",\"effectiveDate\":\"" + LocalDate.now().minusDays(2) + "\",\"balance\":104000}]}"))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.applied").value(0));
    }

    @Test
    void aClaimIsNotSettledOnAnAssumedBalance() throws Exception {
        String[] active = activeLoan("Tshepo Claim", "LSH-3001", 120000, 36, "REGULAR", LocalDate.now().minusDays(200));
        String ref = active[0];
        // AT-IL05-03: the only balance is 200 days old, so the claim goes to manual resolution.
        mockMvc.perform(get("/credit/loans/" + ref + "/claim-assessment").with(signedIn("credit", "password")))
                .andExpect(jsonPath("$.manualResolution").value(true))
                .andExpect(jsonPath("$.lenderAmount").doesNotExist());
        String claim = "{\"eventDate\":\"" + LocalDate.now() + "\",\"claimRef\":\"CLM-9001\",\"payeeVerified\":true}";
        mockMvc.perform(json(post("/credit/loans/" + ref + "/claim"), "credit", claim))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("manual resolution")));
        mockMvc.perform(get("/credit/loans/" + ref).with(signedIn("credit", "password")))
                .andExpect(jsonPath("$.loan.status").value("COVER_ACTIVE"))
                .andExpect(jsonPath("$.loan.balanceException").value(containsString("needs a confirmed balance")));

        // With a current balance from the lender, the lender is paid the balance and the estate the surplus.
        mockMvc.perform(json(post("/credit/loans/" + ref + "/balance-exception/resolve"), "credit", "{\"note\":\"Lender sent a current statement\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/credit/loans/" + ref + "/balances"), "credit", "{\"sourceReference\":\"STMT-CLAIM\","
                        + "\"effectiveDate\":\"" + LocalDate.now().minusDays(2) + "\",\"balance\":90000}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/credit/loans/" + ref + "/claim"), "credit", claim.replace("true", "false")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/credit/loans/" + ref + "/claim"), "credit", claim))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loan.status").value("CLAIMED"))
                .andExpect(jsonPath("$.loan.lenderAmount").value(90000.0))
                .andExpect(jsonPath("$.loan.estateAmount").value(30000.0));
    }

    @Test
    void anEarlySettlementClosesOnceAndRefundsOnce() throws Exception {
        String[] active = activeLoan("Lebogang Settle", "LSH-4001", 120000, 36, "SINGLE", LocalDate.now());
        String ref = active[0];
        String policy = active[1];
        mockMvc.perform(get("/credit/loans/" + ref + "/settlement-preview").with(signedIn("credit", "password")))
                .andExpect(jsonPath("$.monthsRemaining").value(36))
                .andExpect(jsonPath("$.refund").value(3304.8));
        String settle = "{\"settlementReference\":\"SET-4001\",\"settlementDate\":\"" + LocalDate.now() + "\"}";
        mockMvc.perform(json(post("/credit/loans/" + ref + "/settlement"), "credit", settle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loan.status").value("SETTLED"))
                .andExpect(jsonPath("$.loan.balance").value(0.0))
                .andExpect(jsonPath("$.refund.amount").value(3304.8))
                .andExpect(jsonPath("$.refund.status").value("DUE"));
        // AT-IL05-02: the lender's file sent again closes nothing more and raises no second refund.
        mockMvc.perform(json(post("/credit/loans/" + ref + "/settlement"), "credit", settle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loan.status").value("SETTLED"));
        mockMvc.perform(json(post("/credit/loans/" + ref + "/settlement"), "credit", settle.replace("SET-4001", "SET-4002")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from crd_refund where loan_ref = ?", Long.class, ref)).isEqualTo(1);
        assertThat(events("LoanSettled", ref)).isEqualTo(1);
        assertThat(events("CreditCoverTerminated", ref)).isEqualTo(1);
        mockMvc.perform(get("/servicing/policies/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policy.contractStatus").value("Terminated"))
                .andExpect(jsonPath("$.policy.terminationReason").value("LOAN_SETTLED"));

        String refund = JsonPath.read(mockMvc.perform(get("/credit/refunds").with(signedIn("credit", "password")))
                .andReturn().getResponse().getContentAsString(), "$[0].ref");
        mockMvc.perform(json(post("/credit/refunds/" + refund + "/payment"), "credit", "{\"paymentReference\":\"EFT-1\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/credit/refunds/" + refund + "/payment"), "admin", "{\"paymentReference\":\"EFT-7781\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(json(post("/credit/refunds/" + refund + "/payment"), "admin", "{\"paymentReference\":\"EFT-7782\"}"))
                .andExpect(status().isConflict());
        assertThat(events("PremiumRefunded", refund)).isEqualTo(1);

        // Refinancing settles the old loan and links the new one back to it.
        String[] old = activeLoan("Neo Refinance", "LSH-5001", 80000, 24, "REGULAR", LocalDate.now());
        mockMvc.perform(json(post("/credit/loans/" + old[0] + "/refinance"), "credit",
                        "{\"newLenderLoanId\":\"LSH-5002\",\"principal\":100000,\"termMonths\":36,\"settlementReference\":\"REFI-5001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refinancedFrom").value(old[0]))
                .andExpect(jsonPath("$.status").value("QUOTED"));
        mockMvc.perform(get("/credit/loans/" + old[0]).with(signedIn("credit", "password")))
                .andExpect(jsonPath("$.loan.status").value("SETTLED"));
    }

    /** A disbursed loan whose first premium has been paid: {loanRef, policyRef}. */
    private String[] activeLoan(String name, String lenderLoanId, int principal, int term, String basis,
            LocalDate disbursed) throws Exception {
        String borrower = customer(name, 40);
        String created = mockMvc.perform(json(post("/credit/loans"), "credit", loan(borrower, lenderLoanId, principal, term, basis, "")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(created, "$.ref");
        String policy = JsonPath.read(created, "$.policyRef");
        mockMvc.perform(json(post("/credit/loans/" + ref + "/disbursement"), "credit",
                        "{\"disbursementDate\":\"" + disbursed + "\",\"reference\":\"DISB-" + lenderLoanId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
        return new String[] {ref, policy};
    }

    private static String loan(String borrower, String id, int principal, int term, String basis, String extra) {
        return "{\"lenderRef\":\"LND-0001\",\"lenderLoanId\":\"" + id + "\",\"customerRef\":\"" + borrower
                + "\",\"principal\":" + principal + ",\"termMonths\":" + term + ",\"premiumBasis\":\"" + basis + "\"" + extra + "}";
    }

    private long events(String type, String aggregate) {
        return jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and aggregate_ref = ?",
                Long.class, type, aggregate);
    }

    private String customer(String name, int age) throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                                + "\",\"email\":\"c" + Math.abs(name.hashCode()) + "@example.com\",\"phone\":\"+267 71 950 001\","
                                + "\"dateOfBirth\":\"" + LocalDate.now().minusYears(age).minusDays(10) + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

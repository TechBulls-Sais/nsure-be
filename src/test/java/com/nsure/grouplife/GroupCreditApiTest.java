package com.nsure.grouplife;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.finance.Journal;
import com.nsure.integration.Events;
import com.nsure.integration.OutboxDispatcher;
import com.nsure.integration.OutboxEvent;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * GL06 group credit end to end: lender agreement with loan classes (NS-GL06-001), bordereau validation with
 * referral and underwriting (NS-GL06-002, AT-GL06-01/02), premium remittance with commission and cession
 * (NS-GL06-003), lender update files with control totals and exceptions (NS-GL06-004), claims with payee split and
 * recovery (NS-GL06-005), and settlement refunds approved once with balanced journals (NS-GL06-006, AT-GL06-03).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group-credit;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupCreditApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Test
    void aLenderSchemeRunsFromBordereauThroughPremiumClaimSettlementAndRefund() throws Exception {
        String scheme = JsonPath.read(ok(post("/group/schemes"), "admin", "{\"name\":\"Credit Lender " + System.nanoTime()
                + "\",\"sector\":\"Banking\",\"inceptionDate\":\"2026-01-01\",\"renewalDate\":\"2027-01-01\"}")
                .andReturn().getResponse().getContentAsString(), "$.scheme.ref");
        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, scheme); // schemes start in SETUP (NS-GL01-004)
        ok(put("/group-life/schemes/" + scheme + "/benefits"), "admin",
                "{\"benefitType\":\"CREDIT\",\"basis\":\"FIXED\",\"fixedAmount\":750000}");
        ok(put("/group-life/schemes/" + scheme + "/lender-agreement"), "admin", "{\"lenderRef\":\"LND-0001\","
                + "\"agreementNo\":\"AGR-1\",\"premiumBasis\":\"SINGLE\",\"ratePerMille\":1.0,\"commissionPercent\":10,"
                + "\"cededPercent\":50,\"classes\":[{\"code\":\"PERSONAL\",\"name\":\"Personal loans\",\"maxTerm\":60,"
                + "\"maxAmount\":500000,\"benefits\":[\"DEATH\",\"DISABILITY\",\"CRITICAL_ILLNESS\",\"TEMPORARY_DISABILITY\"]},"
                + "{\"code\":\"VEHICLE\",\"name\":\"Vehicle finance\",\"maxTerm\":72,\"maxAmount\":800000,"
                + "\"benefits\":[\"DEATH\",\"DISABILITY\"]}]}")
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.lenderName").isNotEmpty())
                .andExpect(jsonPath("$.classes.length()").value(2));

        String rows = "[" + row("A1", "1985-05-05", "123456789", "PERSONAL", 120000, "DEATH,DISABILITY,CRITICAL_ILLNESS,TEMPORARY_DISABILITY")
                + "," + row("B1", "1950-01-01", "223456789", "PERSONAL", 50000, "DEATH")
                + "," + row("C1", "1980-01-01", "323456789", "PERSONAL", 900000, "DEATH,DISABILITY")
                + "," + row("D1", "1980-01-01", "12AB", "PERSONAL", 50000, "DEATH")
                + "," + row("F1", "1980-01-01", "523456789", "VEHICLE", 60000, "DEATH,TEMPORARY_DISABILITY")
                + "," + row("A1", "1985-05-05", "123456789", "PERSONAL", 120000, "DEATH") + "]";
        // Control totals that do not reconcile reject the whole file, which is kept.
        ok(post("/group-life/schemes/" + scheme + "/credit-loans"), "admin", "{\"batchReference\":\"BDX-BAD\","
                + "\"schemaVersion\":\"2.0\",\"controlTotals\":{\"rowCount\":5,\"totalLoanAmount\":1},\"rows\":" + rows + "}")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value(containsString("Control totals do not reconcile")));
        String file = "{\"batchReference\":\"BDX-1\",\"schemaVersion\":\"2.0\",\"controlTotals\":{\"rowCount\":6,"
                + "\"totalLoanAmount\":1300000},\"rows\":" + rows + "}";
        ok(post("/group-life/schemes/" + scheme + "/credit-loans"), "admin", file)
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.referred").value(1))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.rejected").value(3))
                .andExpect(jsonPath("$.results[1].note").value(containsString("outside the eligible ages")))
                .andExpect(jsonPath("$.results[2].note").value(containsString("above the scheme cap")))
                .andExpect(jsonPath("$.results[3].note").value(containsString("passport")))
                .andExpect(jsonPath("$.results[4].note").value(containsString("not available on loan class VEHICLE")));
        // AT-GL06-01: the same file again adds no exposure or premium.
        ok(post("/group-life/schemes/" + scheme + "/credit-loans"), "admin", file)
                .andExpect(jsonPath("$.repeated").value(true))
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(3));
        String loans = mockMvc.perform(get("/group-life/schemes/" + scheme + "/credit-loans").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse().getContentAsString();
        Integer a1 = ((List<Integer>) JsonPath.read(loans, "$[?(@.lenderLoanId == 'A1')].id")).get(0);
        Integer c1 = ((List<Integer>) JsonPath.read(loans, "$[?(@.lenderLoanId == 'C1')].id")).get(0);
        assertThat((List<Double>) JsonPath.read(loans, "$[?(@.lenderLoanId == 'A1')].premium")).containsExactly(2880.0);

        // The referred loan is accepted at the cap by an underwriter and goes on cover.
        ok(post("/group-life/credit-loans/" + c1 + "/underwriting"), "admin",
                "{\"decision\":\"ACCEPT\",\"coverAmount\":750000,\"note\":\"Cover held at the cap\"}")
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.premium").value(18000.0));

        // Premium remittance: both single premiums, with commission and the ceded share.
        String remittance = JsonPath.read(ok(post("/group-life/schemes/" + scheme + "/credit-remittances"), "admin",
                        "{\"period\":\"2026-11\"}")
                .andExpect(jsonPath("$.amount").value(20880.0))
                .andExpect(jsonPath("$.commission").value(2088.0))
                .andExpect(jsonPath("$.ceded").value(10440.0))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        ok(post("/group-life/credit-remittances/" + remittance + "/receipts"), "admin", "{\"reference\":\"RMT-1\","
                + "\"amount\":20000,\"rows\":[{\"lenderLoanId\":\"A1\",\"amount\":2880},{\"lenderLoanId\":\"C1\",\"amount\":17120}]}")
                .andExpect(jsonPath("$.status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.outstanding").value(880.0))
                .andExpect(jsonPath("$.reconciliation").value(containsString("C1 paid 17120")));

        // Lender update file: bad control totals are rejected; then balances, a settlement and an unknown loan.
        ok(post("/group-life/schemes/" + scheme + "/credit-files"), "admin", "{\"fileReference\":\"UPD-BAD\","
                + "\"schemaVersion\":\"2.0\",\"controlTotals\":{\"rowCount\":1,\"totalBalance\":1},"
                + "\"rows\":[{\"lenderLoanId\":\"A1\",\"type\":\"BALANCE\",\"balance\":100000}]}")
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/credit-files"), "admin",
                        "{\"fileReference\":\"UPD-V9\",\"schemaVersion\":\"9.0\",\"rows\":[{}]}"))
                .andExpect(status().isUnprocessableEntity());
        ok(post("/group-life/schemes/" + scheme + "/credit-files"), "admin", "{\"fileReference\":\"UPD-1\","
                + "\"schemaVersion\":\"2.0\",\"controlTotals\":{\"rowCount\":3,\"totalBalance\":105000},\"rows\":["
                + "{\"lenderLoanId\":\"A1\",\"type\":\"BALANCE\",\"balance\":100000,\"effectiveDate\":\"2026-11-10\"},"
                + "{\"lenderLoanId\":\"C1\",\"type\":\"SETTLEMENT\",\"effectiveDate\":\"2026-11-15\"},"
                + "{\"lenderLoanId\":\"Z9\",\"type\":\"BALANCE\",\"balance\":5000}]}")
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.exceptions").value(1));
        // A correction file repeating the settlement gives no second refund.
        ok(post("/group-life/schemes/" + scheme + "/credit-files"), "admin", "{\"fileReference\":\"UPD-2\","
                + "\"schemaVersion\":\"2.0\",\"controlTotals\":{\"rowCount\":1,\"totalBalance\":0},\"rows\":["
                + "{\"lenderLoanId\":\"C1\",\"type\":\"SETTLEMENT\",\"effectiveDate\":\"2026-11-15\"}]}")
                .andExpect(jsonPath("$.results[0].outcome").value("DUPLICATE"));
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/credit-exceptions").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.type == 'UNKNOWN_LOAN')].lenderLoanId").value("Z9"))
                .andExpect(jsonPath("$[?(@.type == 'REMITTANCE_MISMATCH')].status").value("OPEN"));

        // The settlement refund: 22 of 24 months of 18 000 unearned = 16 500, less 10% = 14 850; one refund only.
        String refunds = mockMvc.perform(get("/group-life/schemes/" + scheme + "/credit-refunds").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].grossUnearned").value(16500.0))
                .andExpect(jsonPath("$[0].refundAmount").value(14850.0))
                .andExpect(jsonPath("$[0].commissionReversal").value(1650.0))
                .andExpect(jsonPath("$[0].reinsuranceReversal").value(8250.0))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String refund = JsonPath.read(refunds, "$[0].ref");
        mockMvc.perform(json(post("/group-life/credit-refunds/" + refund + "/decision"), "admin",
                        "{\"approve\":true,\"note\":\"mine\"}"))
                .andExpect(status().isForbidden());
        ok(post("/group-life/credit-refunds/" + refund + "/decision"), "secadmin",
                "{\"approve\":true,\"note\":\"Settlement confirmed by the lender\"}")
                .andExpect(jsonPath("$.status").value("APPROVED"));
        ok(post("/group-life/credit-refunds/" + refund + "/payment"), "admin", "{\"paymentReference\":\"SIM-RF-1\"}")
                .andExpect(jsonPath("$.status").value("PAID"));

        // Claims: temporary disability is capped at 15 000 a month; death pays the balance to the lender.
        ok(post("/group-life/credit-loans/" + a1 + "/claims"), "admin", "{\"benefit\":\"TEMPORARY_DISABILITY\","
                + "\"eventDate\":\"2026-11-05\",\"monthlyInstalment\":20000,\"months\":3}")
                .andExpect(jsonPath("$.monthlyAmount").value(15000.0))
                .andExpect(jsonPath("$.benefitAmount").value(45000.0));
        String claim = JsonPath.read(ok(post("/group-life/credit-loans/" + a1 + "/claims"), "admin",
                        "{\"benefit\":\"DEATH\",\"eventDate\":\"2026-11-20\"}")
                .andExpect(jsonPath("$.balanceAtEvent").value(100000.0))
                .andExpect(jsonPath("$.lenderAmount").value(100000.0))
                .andExpect(jsonPath("$.borrowerAmount").value(0.0))
                .andExpect(jsonPath("$.calculation").value(containsString("LND-0001")))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group-life/credit-claims/" + claim + "/decision"), "admin", "{\"action\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        ok(post("/group-life/credit-claims/" + claim + "/decision"), "secadmin", "{\"action\":\"APPROVE\"}");
        ok(post("/group-life/credit-claims/" + claim + "/decision"), "admin",
                "{\"action\":\"PAY\",\"paymentReference\":\"SIM-CL-1\"}")
                .andExpect(jsonPath("$.status").value("PAID"));
        ok(post("/group-life/credit-claims/" + claim + "/decision"), "admin",
                "{\"action\":\"RECOVER\",\"confirmedBalance\":96000}")
                .andExpect(jsonPath("$.recoveryAmount").value(4000.0))
                .andExpect(jsonPath("$.recoveryStatus").value("DUE"));
        mockMvc.perform(get("/group-life/credit-loans/" + a1).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.history[0].type").value("ACTIVATED"))
                .andExpect(jsonPath("$.premiums[0].status").value("BILLED"));

        // AT-GL06-03: premium, commission, cession, refund, their reversals and the claim post balanced journals.
        dispatcher.dispatch();
        dispatcher.dispatch();
        String certificate = scheme + "-LC1";
        assertJournal(remittance, Events.SCHEME_INVOICE_ISSUED, "20880.00");
        assertJournal(remittance, Events.COMMISSION_ACCRUED, "2088.00");
        assertJournal(remittance, Events.RISK_CEDED, "10440.00");
        assertJournal(refund, Events.COMMISSION_REVERSED, "1650.00");
        assertJournal(refund, Events.RISK_CEDED, "8250.00");
        assertJournal(certificate, Events.GROUP_PREMIUM_REFUNDED, "14850.00");
        assertJournal(claim, Events.GROUP_CLAIM_PAID, "100000.00");
        assertThat(events(Events.UNEARNED_PREMIUM_REFUND_APPROVED, scheme)).hasSize(1);
    }

    private static String row(String id, String dob, String idNumber, String loanClass, int amount, String benefits) {
        return "{\"lenderLoanId\":\"" + id + "\",\"borrowerName\":\"Borrower " + id + "\",\"borrowerIdNumber\":\"" + idNumber
                + "\",\"dateOfBirth\":\"" + dob + "\",\"loanClass\":\"" + loanClass + "\",\"loanAmount\":" + amount
                + ",\"termMonths\":24,\"disbursementDate\":\"2026-10-01\",\"benefits\":\"" + benefits + "\"}";
    }

    private void assertJournal(String sourceRef, String eventType, String amount) {
        em.clear();
        List<Journal> journals = em.createQuery("select j from Journal j where j.sourceRef = :ref and j.eventType = :t",
                Journal.class).setParameter("ref", sourceRef).setParameter("t", eventType).getResultList();
        assertThat(journals).as(eventType + " for " + sourceRef).hasSize(1);
        assertThat(journals.get(0).getAmount()).isEqualByComparingTo(new BigDecimal(amount));
        Object[] totals = em.createQuery("select sum(l.debit), sum(l.credit) from JournalLine l where l.journal = :j",
                Object[].class).setParameter("j", journals.get(0)).getSingleResult();
        assertThat((BigDecimal) totals[0]).isEqualByComparingTo((BigDecimal) totals[1]);
    }

    private List<OutboxEvent> events(String type, String scheme) {
        return em.createQuery("select e from OutboxEvent e where e.eventType = :t and e.payload like :s order by e.id",
                OutboxEvent.class).setParameter("t", type).setParameter("s", "%\"schemeRef\":\"" + scheme + "\"%")
                .getResultList();
    }

    private ResultActions ok(MockHttpServletRequestBuilder request, String user, String body) throws Exception {
        return mockMvc.perform(json(request, user, body)).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

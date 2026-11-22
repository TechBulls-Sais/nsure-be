package com.nsure.group;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nsure.finance.Journal;
import com.nsure.integration.OutboxDispatcher;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The group lifecycle suite (specification section 14): approved scheme and categories, a staged census with errors,
 * member underwriting, certificates, an invoice and a short sponsor payment, joiner/leaver/salary movements, a claim
 * and the renewal, reconciling accepted rows to member changes, cover to certificates, bills to cover and the
 * invoice and receipt to balanced journals.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupLifecycleEndToEndTest {

    private static final String HEADER = SchemeLifecycleApiTest.HEADER + ",rowKey";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private EntityManager em;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Test
    void aSchemeRunsFromCensusThroughBillingClaimAndRenewalAndReconciles() throws Exception {
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin", body(Map.of("name",
                        "End To End " + System.nanoTime(), "sector", "Logistics", "inceptionDate", "2026-01-01",
                        "renewalDate", "2027-01-01"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.scheme.ref");
        category(scheme, "STAFF", "SALARY_MULTIPLE", "2", null);
        category(scheme, "MGMT", "FIXED", null, "800000");

        // Staged census with an error: three rows apply, one is rejected, nothing is lost.
        String first = upload(scheme, "E2E-1", "E1,Ella,One,1985-01-01,STAFF,100000,JOIN,2026-01-01,R1\n"
                + "E2,Emil,Two,1975-01-01,MGMT,,JOIN,2026-01-01,R2\n"
                + "E3,Ezra,Three,1985-01-01,NOPE,100000,JOIN,2026-01-01,R3\n"
                + "E4,Esi,Four,1990-01-01,STAFF,150000,JOIN,2026-01-01,R4");
        mockMvc.perform(post("/group/census-batches/" + first + "/commit").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.acceptedRows").value(3))
                .andExpect(jsonPath("$.rejectedRows").value(1))
                .andExpect(jsonPath("$.rows[0].changeId").isNumber());
        mockMvc.perform(get("/group/census-batches/" + first + "/reconciliation").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.appliedRows").value(3))
                .andExpect(jsonPath("$.memberChanges").value(3))
                .andExpect(jsonPath("$.rowsTracedToChanges").value(3))
                .andExpect(jsonPath("$.certificates").value(3))
                .andExpect(jsonPath("$.reconciled").value(true));

        // Member underwriting of the cover above the free-cover limit.
        String detail = detail(scheme);
        Integer manager = ((List<Integer>) JsonPath.read(detail, "$.members[?(@.memberKey == 'E2')].id")).get(0);
        com.nsure.testsupport.GroupSchemeSetup.receiveEvidence(mockMvc, manager);
        mockMvc.perform(json(post("/group/members/" + manager + "/underwriting"), "admin",
                        "{\"decision\":\"ACCEPT\",\"acceptedAmount\":700000,\"reason\":\"Partial on evidence\"}"))
                .andExpect(jsonPath("$.status").value("PARTIALLY_ACCEPTED"));

        // Certificates carry exactly the accepted cover, and the invoice bills exactly that cover.
        String certificates = mockMvc.perform(get("/group-life/schemes/" + scheme + "/certificates")
                .with(signedIn("admin", "password"))).andReturn().getResponse().getContentAsString();
        List<Double> certified = JsonPath.read(certificates, "$[*].acceptedCover");
        assertThat(certified.stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(1200000.0);
        String february = mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "admin", "{\"period\":\"2026-02\"}"))
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.amount").value(1200.0))
                .andReturn().getResponse().getContentAsString();
        String febRef = JsonPath.read(february, "$.ref");
        String receipt = JsonPath.read(mockMvc.perform(json(post("/group/invoices/" + febRef + "/receipts"), "admin",
                        "{\"amount\":1000,\"reference\":\"E2E-EFT-1\"}"))
                .andExpect(jsonPath("$.invoice.outstanding").value(200.0))
                .andReturn().getResponse().getContentAsString(), "$.receipt.ref");

        // Movements: a salary change, a mid-month leaver and joiner; a repeated row key is not applied again.
        String second = upload(scheme, "E2E-2", "E1,,,,,125000,SALARY,2026-03-01,R6\n"
                + "E4,,,,,,LEAVE,2026-03-15,R5\n"
                + "E5,Eli,Five,1992-01-01,STAFF,100000,JOIN,2026-03-16,R7\n"
                + "E9,Eve,Nine,1992-01-01,STAFF,100000,JOIN,2026-03-16,R1");
        mockMvc.perform(get("/group/census-batches/" + second).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.rows[3].error").value(containsString("rowKey R1 was already applied in " + first)));
        mockMvc.perform(post("/group/census-batches/" + second + "/commit").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.acceptedRows").value(3));
        String march = mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "admin", "{\"period\":\"2026-03\"}"))
                .andExpect(jsonPath("$.memberCount").value(4))
                .andExpect(jsonPath("$.lines[?(@.memberKey == 'E4')].daysCovered").value(15))
                .andExpect(jsonPath("$.lines[?(@.memberKey == 'E5')].daysCovered").value(16))
                .andExpect(jsonPath("$.lines[?(@.memberKey == 'E1')].acceptedCover").value(250000.0))
                .andReturn().getResponse().getContentAsString();
        List<Double> premiums = JsonPath.read(march, "$.lines[*].premium");
        assertThat(BigDecimal.valueOf(premiums.stream().mapToDouble(Double::doubleValue).sum()).setScale(2,
                java.math.RoundingMode.HALF_UP)).isEqualByComparingTo(BigDecimal.valueOf((Double) JsonPath.read(march, "$.amount")));
        mockMvc.perform(get("/group/census-batches/" + second + "/reconciliation").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.reconciled").value(true))
                .andExpect(jsonPath("$.rows[?(@.memberKey == 'E5')].invoices[0]").value(JsonPath.<String>read(march, "$.ref")));

        // A claim on the cover in force on the event date, decided by a second person and paid.
        String claim = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        "{\"memberKey\":\"E1\",\"benefitType\":\"GLA\",\"eventDate\":\"2026-04-01\",\"amount\":250000}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group/claims/" + claim + "/decision"), "secadmin", "{\"action\":\"APPROVE\",\"note\":\"Verified\"}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(json(post("/group/claims/" + claim + "/decision"), "admin",
                        "{\"action\":\"PAY\",\"paymentReference\":\"E2E-CLM\"}"))
                .andExpect(jsonPath("$.status").value("PAID"));

        // Renewal: previewed, approved by a second person, the next policy year follows.
        String renewal = JsonPath.read(mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/events"), "admin",
                        "{\"eventType\":\"RENEWAL\",\"rateChangePercent\":0,\"reason\":\"Annual renewal\"}"))
                .andExpect(jsonPath("$.impactDetail.pendingClaims").value(0))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group-life/events/" + renewal + "/decision"), "secadmin", "{\"approve\":true,\"note\":\"Renewed\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/impact").param("eventType", "RENEWAL")
                        .with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policyYear").value(2));

        // The invoice and the sponsor receipt are posted once each to balanced journals.
        dispatcher.dispatch();
        dispatcher.dispatch();
        assertJournal(febRef, new BigDecimal("1200.00"));
        assertJournal(receipt, new BigDecimal("1000.00"));
    }

    private void assertJournal(String sourceRef, BigDecimal amount) {
        em.clear();
        List<Journal> journals = em.createQuery("select j from Journal j where j.sourceRef = :ref", Journal.class)
                .setParameter("ref", sourceRef).getResultList();
        assertThat(journals).hasSize(1);
        assertThat(journals.get(0).getAmount()).isEqualByComparingTo(amount);
        Object[] totals = em.createQuery("select sum(l.debit), sum(l.credit) from JournalLine l where l.journal = :j",
                Object[].class).setParameter("j", journals.get(0)).getSingleResult();
        assertThat((BigDecimal) totals[0]).isEqualByComparingTo((BigDecimal) totals[1]);
        assertThat((BigDecimal) totals[0]).isEqualByComparingTo(amount);
    }

    private void category(String scheme, String code, String basis, String multiple, String fixed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", code + " category");
        body.put("benefitBasis", basis);
        body.put("salaryMultiple", multiple);
        body.put("fixedCover", fixed);
        body.put("freeCoverLimit", "500000");
        body.put("ratePerMille", "1");
        body.put("minEntryAge", 18);
        body.put("maxEntryAge", 64);
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin", body(body)))
                .andExpect(status().isOk());
    }

    private String upload(String scheme, String externalId, String rows) throws Exception {
        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, scheme);
        return JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        body(Map.of("externalBatchId", externalId, "mode", "PARTIAL", "csv", HEADER + "\n" + rows))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
    }

    private String detail(String scheme) throws Exception {
        return mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
    }

    private String body(Map<String, ?> value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

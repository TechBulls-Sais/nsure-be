package com.nsure.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.Events;
import com.nsure.integration.OutboxEvent;
import com.nsure.portfolio.GroupScheme;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Group administration GL01-GL05: schemes and categories, staged census with partial/atomic commit (AT-GL04-01/02),
 * cover above the free-cover limit pending underwriting (AT-GL03-01), pro-rated invoices (AT-GL05-01) and short
 * sponsor payments leaving an explicit outstanding balance (AT-GL05-02).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupAdministrationApiTest {

    private static final String HEADER = "memberKey,firstName,lastName,dateOfBirth,category,salary,action,effectiveDate";
    private static final AtomicInteger SCHEMES = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void partialCensusAppliesValidRowsOnceAndAboveLimitCoverWaitsForUnderwriting() throws Exception {
        String scheme = scheme();
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "grpadmin",
                        category("STAFF", "SALARY_MULTIPLE", "3", null, "500000", "0.45", 18, 18)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "grpadmin",
                        category("STAFF", "FIXED", null, null, "500000", "0.45", 18, 64)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "grpadmin",
                        category("STAFF", "SALARY_MULTIPLE", "3", null, "500000", "0.45", 18, 64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("STAFF"))
                .andExpect(jsonPath("$.freeCoverLimit").value(500000.0));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "grpadmin",
                        category("STAFF", "SALARY_MULTIPLE", "3", null, "500000", "0.45", 18, 64)))
                .andExpect(status().isConflict());

        // 100 rows, 3 invalid: unknown category, bad date, duplicate member key. M001 asks for 600 000 of cover.
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int i = 1; i <= 100; i++) {
            String key = i == 60 ? "M001" : String.format("M%03d", i);
            String category = i == 20 ? "NOPE" : "STAFF";
            String dob = i == 40 ? "1990/01/01" : "1990-01-01";
            String salary = i == 1 ? "200000" : "100000";
            csv.append(key).append(",First").append(i).append(",Last").append(i).append(',').append(dob).append(',')
                    .append(category).append(',').append(salary).append(",JOIN,2026-01-01\n");
        }
        String upload = body(Map.of("externalBatchId", "HR-2026-01", "mode", "PARTIAL", "csv", csv.toString()));
        String batchBody = mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "grpadmin", upload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.totalRows").value(100))
                .andExpect(jsonPath("$.validRows").value(97))
                .andExpect(jsonPath("$.invalidRows").value(3))
                .andExpect(jsonPath("$.fileHash").isNotEmpty())
                .andExpect(jsonPath("$.submittedBy").value("grpadmin"))
                .andExpect(jsonPath("$.rows[19].status").value("INVALID"))
                .andExpect(jsonPath("$.rows[19].error").value(containsString("unknown category NOPE")))
                .andExpect(jsonPath("$.rows[39].error").value(containsString("dateOfBirth must be a date")))
                .andExpect(jsonPath("$.rows[59].error").value(containsString("duplicate memberKey M001")))
                .andReturn().getResponse().getContentAsString();
        String batch = JsonPath.read(batchBody, "$.ref");

        // Staging does not touch live membership.
        mockMvc.perform(get("/group/schemes/" + scheme).with(httpBasic("grpadmin", "password")))
                .andExpect(jsonPath("$.scheme.activeMembers").value(0))
                .andExpect(jsonPath("$.censusBatches[0].ref").value(batch));

        // Same external id and content: the same batch. Same id, other content: conflict (NS-GL04-003).
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "grpadmin", upload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value(batch));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "grpadmin",
                        body(Map.of("externalBatchId", "HR-2026-01", "mode", "PARTIAL",
                                "csv", HEADER + "\nX1,A,B,1990-01-01,STAFF,1000,JOIN,2026-01-01"))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(httpBasic("customer", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(httpBasic("grpadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.acceptedRows").value(97))
                .andExpect(jsonPath("$.rejectedRows").value(3))
                .andExpect(jsonPath("$.rows[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.rows[0].memberId").isNumber())
                .andExpect(jsonPath("$.rows[19].status").value("REJECTED"));
        // Committing again changes nothing (AT-GL04-02).
        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(httpBasic("grpadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedRows").value(97));
        assertThat(memberCount(scheme)).isEqualTo(97L);

        String detail = mockMvc.perform(get("/group/schemes/" + scheme).with(httpBasic("grpadmin", "password")))
                .andExpect(jsonPath("$.scheme.activeMembers").value(97))
                .andExpect(jsonPath("$.scheme.pendingUnderwriting").value(1))
                .andExpect(jsonPath("$.categories[0].activeMembers").value(97))
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(detail, "$.members[?(@.memberKey == 'M001')].id");
        List<String> statuses = JsonPath.read(detail, "$.members[?(@.memberKey == 'M001')].status");
        List<Double> accepted = JsonPath.read(detail, "$.members[?(@.memberKey == 'M001')].acceptedCover");
        List<Double> pending = JsonPath.read(detail, "$.members[?(@.memberKey == 'M001')].pendingCover");
        assertThat(statuses).containsExactly("UNDERWRITING_PENDING");
        assertThat(accepted.get(0)).isEqualTo(500000.0);
        assertThat(pending.get(0)).isEqualTo(100000.0);
        // Dashboard snapshot: 96 × 300 000 + 500 000 accepted = 29 300 000 × 0.45 / 1000 = 13 185.
        GroupScheme snapshot = schemeRow(scheme);
        assertThat(snapshot.getMembers()).isEqualTo(97);
        assertThat(snapshot.getPremium()).isEqualTo(13185);

        long member = ids.get(0);
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting"), "customer", "{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting"), "grpadmin", "{\"decision\":\"DECLINE\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting"), "grpadmin",
                        "{\"decision\":\"ACCEPT\",\"acceptedAmount\":700000}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting"), "grpadmin",
                        "{\"decision\":\"DECLINE\",\"reason\":\"Medical evidence not provided\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_ACCEPTED"))
                .andExpect(jsonPath("$.acceptedCover").value(500000.0))
                .andExpect(jsonPath("$.pendingCover").value(0.0))
                .andExpect(jsonPath("$.underwritingDecision").value("DECLINE"))
                .andExpect(jsonPath("$.underwrittenBy").value("grpadmin"));
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting"), "grpadmin", "{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void underwriterCanAcceptPendingCoverInFull() throws Exception {
        String scheme = scheme();
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "grpadmin",
                        category("MGMT", "SALARY_MULTIPLE", "4", null, "500000", "0.50", 18, 64)))
                .andExpect(status().isOk());
        String batch = upload(scheme, "UW-1", "PARTIAL", "E1,Ada,Moeng,1980-05-05,MGMT,250000,JOIN,2026-01-01");
        String committed = commit(batch);
        Integer member = JsonPath.read(committed, "$.rows[0].memberId");
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting"), "grpadmin",
                        "{\"decision\":\"ACCEPT\",\"reason\":\"Evidence satisfactory\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.acceptedCover").value(1000000.0))
                .andExpect(jsonPath("$.pendingCover").value(0.0));
        assertThat(schemeRow(scheme).getPremium()).isEqualTo(500);
    }

    @Test
    void atomicBatchIsRefusedWhileAnyRowIsInvalidAndLeaveNeedsAnActiveMember() throws Exception {
        String scheme = scheme();
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "grpadmin",
                        category("STAFF", "FIXED", null, "100000", "500000", "1", 18, 60)))
                .andExpect(status().isOk());
        String batch = upload(scheme, "AT-1", "ATOMIC",
                "A1,Ana,One,1990-01-01,STAFF,,JOIN,2026-01-01\n"
                        + "A2,Ben,Two,1940-01-01,STAFF,,JOIN,2026-01-01\n"
                        + "Z9,,,,,,LEAVE,2026-01-31");
        String staged = mockMvc.perform(get("/group/census-batches/" + batch).with(httpBasic("grpadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ATOMIC"))
                .andExpect(jsonPath("$.invalidRows").value(2))
                .andExpect(jsonPath("$.rows[1].error").value(containsString("outside the entry ages")))
                .andExpect(jsonPath("$.rows[2].error").value(containsString("not active")))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(staged, "$.rows[0].status")).isEqualTo("VALID");

        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(httpBasic("grpadmin", "password")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/group/census-batches/" + batch).with(httpBasic("grpadmin", "password")))
                .andExpect(jsonPath("$.status").value("VALIDATED"));
        assertThat(memberCount(scheme)).isZero();
    }

    @Test
    void invoicesProRateMidMonthJoinersAndLeaversAndShortPaymentsLeaveABalance() throws Exception {
        String scheme = scheme();
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "grpadmin",
                        category("STAFF", "FIXED", null, "100000", "500000", "1", 18, 64)))
                .andExpect(status().isOk());
        commit(upload(scheme, "B-1", "PARTIAL",
                "P1,Pula,Full,1985-01-01,STAFF,,JOIN,2026-03-01\nP2,Pula,Half,1985-01-01,STAFF,,JOIN,2026-03-16"));

        mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "grpadmin", "{\"period\":\"2026-13\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "customer", "{\"period\":\"2026-03\"}"))
                .andExpect(status().isForbidden());
        // 100 000 cover at 1.00 per mille = 100.00 a month; P2 covered 16 of 31 days = 51.61.
        String invoiceBody = mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "grpadmin",
                        "{\"period\":\"2026-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.amount").value(151.61))
                .andExpect(jsonPath("$.outstanding").value(151.61))
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.lines[0].memberKey").value("P1"))
                .andExpect(jsonPath("$.lines[0].daysCovered").value(31))
                .andExpect(jsonPath("$.lines[0].premium").value(100.0))
                .andExpect(jsonPath("$.lines[1].daysCovered").value(16))
                .andExpect(jsonPath("$.lines[1].daysInPeriod").value(31))
                .andExpect(jsonPath("$.lines[1].acceptedCover").value(100000.0))
                .andExpect(jsonPath("$.lines[1].premium").value(51.61))
                .andReturn().getResponse().getContentAsString();
        String invoice = JsonPath.read(invoiceBody, "$.ref");
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "grpadmin", "{\"period\":\"2026-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value(invoice))
                .andExpect(jsonPath("$.amount").value(151.61));
        assertThat(events(Events.SCHEME_INVOICE_ISSUED, invoice)).hasSize(1);
        assertThat(events(Events.SCHEME_INVOICE_ISSUED, invoice).get(0).getPayload())
                .contains("\"amount\":\"151.61\"").contains("\"period\":\"2026-03\"");

        // A short payment leaves an explicit balance; the same reference again is the same allocation.
        mockMvc.perform(json(post("/group/invoices/" + invoice + "/receipts"), "grpadmin",
                        "{\"amount\":100,\"reference\":\"EFT-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.amount").value(100.0))
                .andExpect(jsonPath("$.receipt.outstandingAfter").value(51.61))
                .andExpect(jsonPath("$.invoice.status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.invoice.outstanding").value(51.61));
        mockMvc.perform(json(post("/group/invoices/" + invoice + "/receipts"), "grpadmin",
                        "{\"amount\":100,\"reference\":\"EFT-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.paidAmount").value(100.0))
                .andExpect(jsonPath("$.invoice.receipts.length()").value(1));
        mockMvc.perform(json(post("/group/invoices/" + invoice + "/receipts"), "grpadmin",
                        "{\"amount\":60,\"reference\":\"EFT-2\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/group/schemes").with(httpBasic("grpadmin", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + scheme + "')].outstandingBalance").value(51.61));
        mockMvc.perform(json(post("/group/invoices/" + invoice + "/receipts"), "grpadmin",
                        "{\"amount\":51.61,\"reference\":\"EFT-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.status").value("PAID"))
                .andExpect(jsonPath("$.invoice.outstanding").value(0.0));
        assertThat(events(Events.SPONSOR_RECEIPT_ALLOCATED, invoice)).hasSize(2);

        // P1 leaves on 10 April (last day of cover): 10 of 30 days = 33.33, plus P2's full month.
        commit(upload(scheme, "B-2", "PARTIAL", "P1,,,,,,LEAVE,2026-04-10"));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "grpadmin", "{\"period\":\"2026-04\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(133.33))
                .andExpect(jsonPath("$.lines[0].daysCovered").value(10))
                .andExpect(jsonPath("$.lines[0].premium").value(33.33));
    }

    @Test
    void schemeAdministrationNeedsTheGroupPermissions() throws Exception {
        mockMvc.perform(get("/group/schemes").with(httpBasic("customer", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/group/schemes"), "customer", schemeBody("Customer Scheme")))
                .andExpect(status().isForbidden());
        // Finance may read schemes but not create them.
        mockMvc.perform(get("/group/schemes").with(httpBasic("finance", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/group/schemes"), "finance", schemeBody("Finance Scheme")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(put("/group/schemes/SCH-001/categories/STAFF"), "finance",
                        category("STAFF", "SALARY_MULTIPLE", "3", null, "500000", "0.45", 18, 64)))
                .andExpect(status().isForbidden());

        // The seeded schemes carry live categories and members.
        mockMvc.perform(get("/group/schemes").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ref == 'SCH-001')].activeMembers").value(5))
                .andExpect(jsonPath("$[?(@.ref == 'SCH-001')].pendingUnderwriting").value(1))
                .andExpect(jsonPath("$[?(@.ref == 'SCH-001')].monthlyPremium").value(956.0));
        mockMvc.perform(get("/group/schemes/SCH-404").with(httpBasic("grpadmin", "password")))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoryChangesAreProspective() throws Exception {
        String scheme = scheme();
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "grpadmin",
                        category("STAFF", "SALARY_MULTIPLE", "2", null, "500000", "0.45", 18, 64)))
                .andExpect(status().isOk());
        commit(upload(scheme, "C-1", "PARTIAL", "S1,Sam,Old,1990-01-01,STAFF,100000,JOIN,2026-01-01"));
        mockMvc.perform(json(put("/group/schemes/" + scheme + "/categories/STAFF"), "grpadmin",
                        category("STAFF", "SALARY_MULTIPLE", "3", null, "500000", "0.45", 18, 64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salaryMultiple").value(3.0))
                .andExpect(jsonPath("$.activeMembers").value(1));
        commit(upload(scheme, "C-2", "PARTIAL", "S2,Sam,New,1990-01-01,STAFF,100000,JOIN,2026-02-01"));
        mockMvc.perform(get("/group/schemes/" + scheme).with(httpBasic("grpadmin", "password")))
                .andExpect(jsonPath("$.members[?(@.memberKey == 'S1')].acceptedCover").value(200000.0))
                .andExpect(jsonPath("$.members[?(@.memberKey == 'S2')].acceptedCover").value(300000.0));
        // A salary change recalculates S1 from its effective date with the category formula now in force.
        commit(upload(scheme, "C-3", "PARTIAL", "S1,,,,,120000,SALARY,2026-03-01"));
        mockMvc.perform(get("/group/schemes/" + scheme).with(httpBasic("grpadmin", "password")))
                .andExpect(jsonPath("$.members[?(@.memberKey == 'S1')].salary").value(120000.0))
                .andExpect(jsonPath("$.members[?(@.memberKey == 'S1')].acceptedCover").value(360000.0));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String scheme() throws Exception {
        String name = "Test Scheme " + SCHEMES.incrementAndGet() + " " + System.nanoTime();
        String response = mockMvc.perform(json(post("/group/schemes"), "grpadmin", schemeBody(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheme.name").value(name))
                .andExpect(jsonPath("$.scheme.billingFrequency").value("MONTHLY"))
                .andExpect(jsonPath("$.scheme.activeMembers").value(0))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.scheme.ref");
    }

    private String schemeBody(String name) throws Exception {
        return body(Map.of("name", name, "sector", "Mining", "inceptionDate", "2026-01-01",
                "renewalDate", "2027-01-01", "billingFrequency", "MONTHLY"));
    }

    private String category(String code, String basis, String multiple, String fixed, String freeCover, String rate,
            int minAge, int maxAge) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", code + " category");
        body.put("benefitBasis", basis);
        body.put("salaryMultiple", multiple);
        body.put("fixedCover", fixed);
        body.put("freeCoverLimit", freeCover);
        body.put("ratePerMille", rate);
        body.put("minEntryAge", minAge);
        body.put("maxEntryAge", maxAge);
        return body(body);
    }

    private String upload(String scheme, String externalId, String mode, String rows) throws Exception {
        String response = mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "grpadmin",
                        body(Map.of("externalBatchId", externalId, "mode", mode, "csv", HEADER + "\n" + rows))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.ref");
    }

    private String commit(String batch) throws Exception {
        return mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(httpBasic("grpadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andReturn().getResponse().getContentAsString();
    }

    private long memberCount(String scheme) {
        return em.createQuery("select count(m) from SchemeMember m where m.scheme.ref = :ref", Long.class)
                .setParameter("ref", scheme)
                .getSingleResult();
    }

    private GroupScheme schemeRow(String scheme) {
        em.clear();
        return em.createQuery("select s from GroupScheme s where s.ref = :ref", GroupScheme.class)
                .setParameter("ref", scheme)
                .getSingleResult();
    }

    private List<OutboxEvent> events(String type, String invoiceRef) {
        return em.createQuery("select e from OutboxEvent e where e.eventType = :type and e.payload like :ref",
                        OutboxEvent.class)
                .setParameter("type", type)
                .setParameter("ref", "%\"invoiceRef\":\"" + invoiceRef + "\"%")
                .getResultList();
    }

    private String body(Map<String, ?> value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(httpBasic(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

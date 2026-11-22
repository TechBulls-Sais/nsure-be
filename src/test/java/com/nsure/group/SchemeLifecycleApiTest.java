package com.nsure.group;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.Events;
import com.nsure.integration.OutboxEvent;
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
 * Scheme suspension, reactivation, closure and transfer act on member cover, with an impact preview that lists the
 * affected members, unpaid amounts and pending claims before a second person approves (NS-GL01-006, AT-GL01-03); a
 * scheme with contractual or financial activity is never deleted.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SchemeLifecycleApiTest {

    static final String HEADER = "memberKey,firstName,lastName,dateOfBirth,category,salary,action,effectiveDate";
    private static final AtomicInteger SCHEMES = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private EntityManager em;

    @Test
    void closureReportsMembersUnpaidAmountsAndPendingClaimsBeforeApprovalAndThenEndsCover() throws Exception {
        String scheme = scheme();
        category(scheme, "STAFF", "100000", "500000");
        commit(upload(scheme, "CL-1", "P1,Pula,One,1985-01-01,STAFF,,JOIN,2026-01-01\n"
                + "P2,Pula,Two,1986-01-01,STAFF,,JOIN,2026-01-01"));
        // March is part-paid, April unpaid: 100.00 a member a month.
        String march = invoice(scheme, "2026-03");
        invoice(scheme, "2026-04");
        mockMvc.perform(json(post("/group/invoices/" + march + "/receipts"), "admin", "{\"amount\":50,\"reference\":\"EFT-9\"}"))
                .andExpect(status().isOk());
        // A claim on cover in force; cover above what was accepted is not claimable.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        "{\"memberKey\":\"P1\",\"benefitType\":\"GLA\",\"eventDate\":\"2026-02-01\",\"amount\":150000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("not claimable")));
        String claim = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        "{\"memberKey\":\"P1\",\"benefitType\":\"GLA\",\"eventDate\":\"2026-02-01\",\"amount\":100000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOTIFIED"))
                .andReturn().getResponse().getContentAsString(), "$.ref");

        // AT-GL01-03: the preview lists the members, the unpaid amounts and the pending claim.
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/impact").param("eventType", "CLOSURE")
                        .param("effectiveDate", "2026-04-16").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedMembers").value(2))
                .andExpect(jsonPath("$.members[0].memberKey").value("P1"))
                .andExpect(jsonPath("$.members[1].memberKey").value("P2"))
                .andExpect(jsonPath("$.unpaidInvoices").value(2))
                .andExpect(jsonPath("$.outstandingAmount").value(350.0))
                .andExpect(jsonPath("$.invoices.length()").value(2))
                // 16-30 April already invoiced: 15 of 30 days of 2 × 100.00.
                .andExpect(jsonPath("$.premiumInvoicedAfterEffective").value(100.0))
                .andExpect(jsonPath("$.pendingClaims").value(1))
                .andExpect(jsonPath("$.claims[0].ref").value(claim));
        String request = mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/events"), "admin",
                        "{\"eventType\":\"CLOSURE\",\"effectiveDate\":\"2026-04-16\",\"reason\":\"Sponsor ceased trading\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.impact[0]").value(containsString("2 member(s) lose cover")))
                .andExpect(jsonPath("$.impactDetail.members.length()").value(2))
                .andExpect(jsonPath("$.impactDetail.outstandingAmount").value(350.0))
                .andExpect(jsonPath("$.impactDetail.pendingClaims").value(1))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(request, "$.ref");
        // The decider sees the same impact on the stored request before approving.
        mockMvc.perform(get("/group-life/events").param("schemeRef", scheme).with(signedIn("secadmin", "password")))
                .andExpect(jsonPath("$[0].impactDetail.claims[0].ref").value(claim))
                .andExpect(jsonPath("$[0].impact[*]").value(org.hamcrest.Matchers.hasItem(containsString("pending a decision"))));
        mockMvc.perform(json(post("/group-life/events/" + ref + "/decision"), "admin", "{\"approve\":true,\"note\":\"ok\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/group-life/events/" + ref + "/decision"), "secadmin", "{\"approve\":true,\"note\":\"Confirmed\"}"))
                .andExpect(status().isOk());

        // Cover ends the day before the effective date; the episodes and the claim stay on record.
        mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.scheme.status").value("CLOSED"))
                .andExpect(jsonPath("$.scheme.activeMembers").value(0))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].status").value("CEASED"))
                .andExpect(jsonPath("$.members[0].exitDate").value("2026-04-15"))
                .andExpect(jsonPath("$.members[1].exitDate").value("2026-04-15"));
        mockMvc.perform(get("/group/schemes/" + scheme + "/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].status").value("NOTIFIED"));
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/certificates").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(0));
        // Nothing more is accepted on a closed scheme, and it is never deleted.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        body(Map.of("externalBatchId", "CL-2", "mode", "PARTIAL", "csv", HEADER
                                + "\nP3,Pula,Three,1990-01-01,STAFF,,JOIN,2026-05-01"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("closed")));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin", categoryBody("MGMT", "100000", "500000")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "admin", "{\"period\":\"2026-05\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("nothing to invoice")));
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/events"), "admin",
                        "{\"eventType\":\"SUSPENSION\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("cannot be deleted")));
        List<OutboxEvent> closed = events(Events.SCHEME_CLOSED, scheme);
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).getPayload()).contains("\"membersCeased\":2").contains("\"closedOn\":\"2026-04-16\"");
    }

    @Test
    void suspensionSuspendsCoverAndBillingUntilReactivationRestoresIt() throws Exception {
        String scheme = scheme();
        category(scheme, "STAFF", "100000", "500000");
        category(scheme, "MGMT", "600000", "500000");
        commit(upload(scheme, "SU-1", "S1,Sam,Active,1985-01-01,STAFF,,JOIN,2026-01-01\n"
                + "S2,Sue,Pending,1980-01-01,MGMT,,JOIN,2026-01-01"));
        String ref = request(scheme, "{\"eventType\":\"SUSPENSION\",\"effectiveDate\":\"2026-03-16\",\"reason\":\"Premium arrears\"}");
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/events"), "admin",
                        "{\"eventType\":\"CLOSURE\",\"reason\":\"second request\"}"))
                .andExpect(status().isConflict());
        approve(ref);
        String detail = mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.scheme.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.scheme.activeMembers").value(0))
                .andReturn().getResponse().getContentAsString();
        List<String> statuses = JsonPath.read(detail, "$.members[*].status");
        assertThat(statuses).containsOnly("SUSPENDED");
        Integer pendingMember = ((List<Integer>) JsonPath.read(detail, "$.members[?(@.memberKey == 'S2')].id")).get(0);
        // No census, no underwriting and no certificates while suspended.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        body(Map.of("externalBatchId", "SU-2", "mode", "PARTIAL", "csv", HEADER
                                + "\nS3,Sid,New,1990-01-01,STAFF,,JOIN,2026-04-01"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("suspended")));
        mockMvc.perform(json(post("/group/members/" + pendingMember + "/underwriting"), "admin", "{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("suspended")));
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/certificates").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(0));
        // March bills 1-15 only: 100.00 × 15/31 = 48.39 and the accepted 500.00 × 15/31 = 241.94.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "admin", "{\"period\":\"2026-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(290.33))
                .andExpect(jsonPath("$.lines[0].daysCovered").value(15));

        mockMvc.perform(get("/group-life/schemes/" + scheme + "/impact").param("eventType", "REACTIVATION")
                        .with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.affectedMembers").value(2));
        approve(request(scheme, "{\"eventType\":\"REACTIVATION\",\"effectiveDate\":\"2026-04-01\",\"reason\":\"Arrears cleared\"}"));
        mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.scheme.status").value("ACTIVE"))
                .andExpect(jsonPath("$.scheme.activeMembers").value(2))
                .andExpect(jsonPath("$.members[?(@.memberKey == 'S1')].status").value("ACTIVE"))
                .andExpect(jsonPath("$.members[?(@.memberKey == 'S2')].status").value("UNDERWRITING_PENDING"));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "admin", "{\"period\":\"2026-04\"}"))
                .andExpect(jsonPath("$.amount").value(600.0));
        mockMvc.perform(json(post("/group/members/" + pendingMember + "/underwriting"), "admin",
                        "{\"decision\":\"ACCEPT\",\"reason\":\"Evidence received\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void transferMovesMembersToTheTargetSchemeOnItsTermsAndKeepsTheirHistory() throws Exception {
        String source = scheme();
        String target = scheme();
        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, target);
        category(source, "STAFF", "100000", "500000");
        commit(upload(source, "TR-1", "T1,Tau,One,1985-01-01,STAFF,,JOIN,2026-01-01\n"
                + "T2,Tau,Two,1986-01-01,STAFF,,JOIN,2026-01-01"));
        mockMvc.perform(get("/group-life/schemes/" + source + "/impact").param("eventType", "TRANSFER")
                        .param("targetSchemeRef", target).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.blockers[0]").value(containsString("has no category STAFF")));
        mockMvc.perform(json(post("/group-life/schemes/" + source + "/events"), "admin",
                        "{\"eventType\":\"TRANSFER\",\"targetSchemeRef\":\"" + target + "\",\"reason\":\"Merger\"}"))
                .andExpect(status().isUnprocessableEntity());
        // The target's own terms apply: its free-cover limit is lower, so the excess waits for underwriting.
        category(target, "STAFF", "100000", "50000");
        String ref = request(source, "{\"eventType\":\"TRANSFER\",\"targetSchemeRef\":\"" + target
                + "\",\"effectiveDate\":\"2026-06-01\",\"reason\":\"Sponsor merged into the group scheme\"}");
        approve(ref);
        mockMvc.perform(get("/group/schemes/" + source).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.scheme.status").value("CLOSED"))
                .andExpect(jsonPath("$.members[0].status").value("CEASED"))
                .andExpect(jsonPath("$.members[0].exitDate").value("2026-05-31"));
        mockMvc.perform(get("/group/schemes/" + target).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.scheme.activeMembers").value(2))
                .andExpect(jsonPath("$.members[0].memberKey").value("T1"))
                .andExpect(jsonPath("$.members[0].joinDate").value("2026-06-01"))
                .andExpect(jsonPath("$.members[0].sourceBatchRef").value(ref))
                .andExpect(jsonPath("$.members[0].acceptedCover").value(50000.0))
                .andExpect(jsonPath("$.members[0].pendingCover").value(50000.0));
        assertThat(events(Events.SCHEME_CLOSED, source).get(0).getPayload()).contains("\"transferredTo\":\"" + target + "\"");
    }

    @Test
    void aSchemeIsDeletedOnlyBeforeAnyContractualOrFinancialActivity() throws Exception {
        String empty = scheme();
        category(empty, "STAFF", "100000", "500000");
        mockMvc.perform(delete("/group/schemes/" + empty).with(signedIn("customer", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/group/schemes/" + empty).with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/group/schemes/" + empty).with(signedIn("admin", "password")))
                .andExpect(status().isNotFound());
        String used = scheme();
        category(used, "STAFF", "100000", "500000");
        upload(used, "DL-1", "D1,Dee,One,1985-01-01,STAFF,,JOIN,2026-01-01");
        mockMvc.perform(delete("/group/schemes/" + used).with(signedIn("admin", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("1 census batch(es)")));
        mockMvc.perform(delete("/group/schemes/SCH-001").with(signedIn("admin", "password")))
                .andExpect(status().isConflict());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String scheme() throws Exception {
        String name = "Lifecycle Scheme " + SCHEMES.incrementAndGet() + " " + System.nanoTime();
        return JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin", body(Map.of("name", name, "sector",
                        "Retail", "inceptionDate", "2026-01-01", "renewalDate", "2027-01-01"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.scheme.ref");
    }

    private void category(String scheme, String code, String fixed, String freeCover) throws Exception {
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin", categoryBody(code, fixed, freeCover)))
                .andExpect(status().isOk());
    }

    private String categoryBody(String code, String fixed, String freeCover) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", code + " category");
        body.put("benefitBasis", "FIXED");
        body.put("fixedCover", fixed);
        body.put("freeCoverLimit", freeCover);
        body.put("ratePerMille", "1");
        body.put("minEntryAge", 18);
        body.put("maxEntryAge", 64);
        return body(body);
    }

    private String upload(String scheme, String externalId, String rows) throws Exception {
        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, scheme);
        return JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        body(Map.of("externalBatchId", externalId, "mode", "PARTIAL", "csv", HEADER + "\n" + rows))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
    }

    private void commit(String batch) throws Exception {
        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));
    }

    private String invoice(String scheme, String period) throws Exception {
        return JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "admin",
                        "{\"period\":\"" + period + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
    }

    private String request(String scheme, String body) throws Exception {
        return JsonPath.read(mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/events"), "admin", body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
    }

    private void approve(String ref) throws Exception {
        mockMvc.perform(json(post("/group-life/events/" + ref + "/decision"), "secadmin",
                        "{\"approve\":true,\"note\":\"Approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private List<OutboxEvent> events(String type, String scheme) {
        return em.createQuery("select e from OutboxEvent e where e.eventType = :type and e.payload like :ref",
                        OutboxEvent.class)
                .setParameter("type", type)
                .setParameter("ref", "%\"schemeRef\":\"" + scheme + "\"%")
                .getResultList();
    }

    private String body(Map<String, ?> value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

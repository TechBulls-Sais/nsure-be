package com.nsure.servicedesk;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SH10 (work package e): a complaint carries the remedy asked for and is resolved with a coded reason and outcome
 * (NS-SH10-004); a pause needs an approved reason, its evidence and dates, and some reasons a second person
 * (NS-SH10-005); overdue complaints escalate by level and unowned cases alert supervisors; a case keeps the workflow
 * version it was opened under (NS-SH10-001); evidence on a case waits in quarantine (NS-SH09A-004).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-service-workflow;DB_CLOSE_DELAY=-1",
        "nsure.service-desk.escalation-cron=-"})
@AutoConfigureMockMvc
@SuppressWarnings("unchecked")
class ServiceCaseWorkflowApiTest {

    private static final String NEO = "CUS-1006";
    private static final byte[] PDF = "%PDF-1.4\n% statement\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void provisionModuleUsers() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aComplaintIsResolvedWithARemedyACodedReasonAndAnOutcome() throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/service/cases"), "service", "{\"customerRef\":\"" + NEO
                        + "\",\"caseType\":\"COMPLAINT\",\"category\":\"Billing\",\"subject\":\"Charged twice\","
                        + "\"requestedRemedy\":\"Refund the second debit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.requestedRemedy").value("Refund the second debit"))
                .andReturn().getResponse().getContentAsString(), "$.case.ref");
        mockMvc.perform(get("/service/reference").with(signedIn("service", "password")))
                .andExpect(jsonPath("$.resolutionCodes[*].code").value(hasItem("REDRESS_PAID")))
                .andExpect(jsonPath("$.pauseReasons[*].code").value(hasItem("AWAITING_CUSTOMER")));
        mockMvc.perform(json(post("/service/cases/" + ref + "/resolve"), "service",
                        "{\"resolution\":\"Refunded\",\"resolutionCode\":\"REDRESS_PAID\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("outcome")));
        mockMvc.perform(json(post("/service/cases/" + ref + "/resolve"), "service",
                        "{\"resolution\":\"Refunded\",\"resolutionCode\":\"NO_SUCH_CODE\",\"outcome\":\"UPHELD\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/service/cases/" + ref + "/resolve"), "service",
                        "{\"resolution\":\"Second debit refunded\",\"resolutionCode\":\"REDRESS_PAID\","
                                + "\"outcome\":\"UPHELD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.resolutionCode").value("REDRESS_PAID"))
                .andExpect(jsonPath("$.case.outcome").value("UPHELD"));
        mockMvc.perform(get("/portfolio/shop/cases").with(signedIn("customer", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + ref + "')].outcome").value(hasItem("UPHELD")));
    }

    @Test
    void aPauseThatNeedsApprovalWaitsForAnotherSupervisor() throws Exception {
        String ref = open("COMPLAINT", "Hold my complaint");
        String hold = "{\"code\":\"CUSTOMER_REQUESTED_HOLD\",\"evidence\":\"Customer phoned on 20 Nov asking us to wait\","
                + "\"expectedResume\":\"" + LocalDate.now().plusDays(10) + "\"}";
        mockMvc.perform(json(post("/service/cases/" + ref + "/pause"), "admin", hold))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.status").value("OPEN"))
                .andExpect(jsonPath("$.case.pausePending").value(true))
                .andExpect(jsonPath("$.case.pauseRequestedBy").value("admin"));
        // The requester cannot approve their own pause, and a case worker without ASSIGN_WORK cannot either.
        mockMvc.perform(json(post("/service/cases/" + ref + "/approve-pause"), "admin", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/service/cases/" + ref + "/approve-pause"), "service", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/service/cases/" + ref + "/approve-pause"), "secadmin", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.status").value("PAUSED"))
                .andExpect(jsonPath("$.case.pauseApprovedBy").value("secadmin"))
                .andExpect(jsonPath("$.case.pauseExpectedResume").value(LocalDate.now().plusDays(10).toString()));
    }

    @Test
    void overdueComplaintsEscalateByLevelAndUnownedCasesAlertSupervisors() throws Exception {
        String ref = open("COMPLAINT", "Nobody answers");
        // Most of its working time used: past 50% (team leader) but not the deadline.
        tx.executeWithoutResult(status -> em.createQuery("update ServiceCase c set c.openedAt = :opened, c.dueAt = :due"
                        + " where c.ref = :ref")
                .setParameter("opened", LocalDateTime.now().minusDays(14))
                .setParameter("due", LocalDateTime.now().plusDays(3))
                .setParameter("ref", ref).executeUpdate());
        mockMvc.perform(post("/service/escalations/run").with(signedIn("service", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/service/escalations/run").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String body = mockMvc.perform(get("/service/cases/" + ref).with(signedIn("service", "password")))
                .andReturn().getResponse().getContentAsString();
        int level = JsonPath.read(body, "$.case.escalationLevel");
        assertThat(level).isGreaterThanOrEqualTo(1);
        assertThat((List<String>) JsonPath.read(body, "$.history[*].action")).contains("Escalated", "Unowned alert");
        assertThat(jdbc.queryForObject("select count(*) from staff_alert where dedup_key = ?", Integer.class,
                "case-unowned:" + ref)).isEqualTo(1);
        // Running it again changes nothing more.
        mockMvc.perform(post("/service/escalations/run").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from staff_alert where dedup_key = ?", Integer.class,
                "case-unowned:" + ref)).isEqualTo(1);
        // The customer asks for the ombudsman: an external referral with its reference.
        mockMvc.perform(json(post("/service/cases/" + ref + "/escalate"), "service",
                        "{\"reason\":\"Customer referred the complaint\",\"external\":true}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/service/cases/" + ref + "/escalate"), "service",
                        "{\"reason\":\"Customer referred the complaint\",\"external\":true,"
                                + "\"externalReference\":\"OMB-2026-114\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.escalationLevel").value(3))
                .andExpect(jsonPath("$.case.externalReference").value("OMB-2026-114"));
    }

    @Test
    void aCaseKeepsTheWorkflowVersionItWasOpenedUnder() throws Exception {
        String before = open("SERVICE_REQUEST", "Before the change");
        int version = JsonPath.read(detail(before), "$.case.workflowVersion");
        mockMvc.perform(json(put("/ops/workflow/definitions/SERVICE_REQUEST"), "admin",
                        "{\"slaHours\":24,\"requiredEvidence\":\"ID for a change of details\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[?(@.code == 'SERVICE_REQUEST')].versionNo").value(hasItem(version)))
                .andExpect(jsonPath("$.versions[?(@.code == 'SERVICE_REQUEST')].versionNo").value(hasItem(version + 1)));
        String after = open("SERVICE_REQUEST", "After the change");
        assertThat((Integer) JsonPath.read(detail(after), "$.case.workflowVersion")).isEqualTo(version + 1);
        assertThat((Integer) JsonPath.read(detail(after), "$.case.slaHours")).isEqualTo(24);
        assertThat((Integer) JsonPath.read(detail(before), "$.case.workflowVersion")).isEqualTo(version);
        assertThat((Integer) JsonPath.read(detail(before), "$.case.slaHours")).isEqualTo(72);
        mockMvc.perform(json(put("/ops/workflow/definitions/SERVICE_REQUEST"), "admin", "{\"slaHours\":72}"))
                .andExpect(status().isOk());
    }

    @Test
    void caseEvidenceWaitsInQuarantineUntilSomeoneElseReleasesIt() throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/portfolio/shop/cases"), "customer",
                        "{\"caseType\":\"COMPLAINT\",\"category\":\"Claims\",\"subject\":\"Claim paid short\","
                                + "\"requestedRemedy\":\"Pay the balance\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(multipart("/portfolio/shop/cases/" + ref + "/documents")
                        .file(new MockMultipartFile("file", "statement.pdf", "application/pdf", PDF))
                        .param("type", "Bank statement").with(signedIn("customer", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(0))
                .andExpect(jsonPath("$.held[0].state").value("QUARANTINED"));
        // Another customer cannot see or add to it.
        mockMvc.perform(get("/portfolio/shop/cases/CMP-9999/documents").with(signedIn("customer", "password")))
                .andExpect(status().isNotFound());
        String held = mockMvc.perform(get("/portfolio/document-quarantine").with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Integer upload = ((List<Integer>) JsonPath.read(held, "$[?(@.ownerRef == '" + ref + "')].id")).get(0);
        // Onboarding may release identity evidence but has no access to service cases.
        mockMvc.perform(json(post("/portfolio/document-quarantine/" + upload + "/release"), "onboarding",
                        "{\"note\":\"Bank statement, readable, matches the claim\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/document-quarantine/" + upload + "/release"), "admin",
                        "{\"note\":\"Bank statement, readable, matches the claim\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        String docs = mockMvc.perform(get("/service/cases/" + ref + "/documents").with(signedIn("service", "password")))
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.held.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(docs, "$.documents[0].id");
        mockMvc.perform(get("/service/cases/" + ref + "/documents/" + id).with(signedIn("service", "password")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF));
        assertThat((List<String>) JsonPath.read(detail(ref), "$.history[*].action"))
                .contains("Evidence uploaded", "Evidence released");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private String open(String type, String subject) throws Exception {
        return JsonPath.read(mockMvc.perform(json(post("/service/cases"), "service", "{\"customerRef\":\"" + NEO
                        + "\",\"caseType\":\"" + type + "\",\"category\":\"General\",\"subject\":\"" + subject + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.case.ref");
    }

    private String detail(String ref) throws Exception {
        return mockMvc.perform(get("/service/cases/" + ref).with(signedIn("service", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

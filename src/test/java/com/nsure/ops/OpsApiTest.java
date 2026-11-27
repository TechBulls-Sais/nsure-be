package com.nsure.ops;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Partner portals (section 04), work queue and calendar (NS-SH10-001/002/005), documents (NS-SH09A-005/006), payouts. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-ops;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class OpsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aPartnerSeesOnlyItsOwnRecordsAndStaffWorkOneQueue() throws Exception {
        // Each partner login is confined to its portal and its own scope.
        mockMvc.perform(get("/ops/portal").with(signedIn("lender", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portalType").value("LENDER"))
                .andExpect(jsonPath("$.scopeRef").value("LND-0001"))
                .andExpect(jsonPath("$.loans").isArray());
        mockMvc.perform(get("/ops/portal").with(signedIn("employer", "password")))
                .andExpect(jsonPath("$.portalType").value("EMPLOYER"))
                .andExpect(jsonPath("$.members").isArray());
        mockMvc.perform(get("/ops/portal").with(signedIn("agent", "password")))
                .andExpect(jsonPath("$.portalType").value("AGENT"));
        mockMvc.perform(get("/portfolio/claims").with(signedIn("lender", "password"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/credit/loans").with(signedIn("lender", "password"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/ops/queue").with(signedIn("lender", "password"))).andExpect(status().isForbidden());

        // A partner's request gets a reference and lands in the staff queue with an SLA.
        String ref = JsonPath.read(mockMvc.perform(post("/ops/portal/requests").with(signedIn("lender", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestType\":\"NEW_LOAN\",\"detail\":\"Loan LSH-9 for T Molefe, 80,000 over 24 months\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        String key = "PORTAL_REQUEST:" + ref;
        mockMvc.perform(get("/ops/queue").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.key == '" + key + "')].team").value("Operations"))
                .andExpect(jsonPath("$[?(@.key == '" + key + "')].dueAt").isNotEmpty());
        // Ownership is taken once: a second person is told who has it.
        mockMvc.perform(post("/ops/queue/claim").param("key", key).with(signedIn("admin", "password"))).andExpect(status().isOk());
        mockMvc.perform(post("/ops/queue/claim").param("key", key).with(signedIn("secadmin", "password"))).andExpect(status().isConflict());
        mockMvc.perform(post("/ops/portal-requests/" + ref + "/close").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Loan registered as LN-000009\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/ops/portal").with(signedIn("lender", "password")))
                .andExpect(jsonPath("$.requests[0].status").value("DONE"));

        // Definitions, the calendar, documents and payouts.
        mockMvc.perform(put("/ops/workflow/definitions/UNDERWRITING").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slaHours\":24}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitions[?(@.code == 'UNDERWRITING')].versionNo").value(2));
        mockMvc.perform(get("/ops/documents").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retention['Policy document']").value(10));
        mockMvc.perform(get("/ops/payouts").with(signedIn("admin", "password"))).andExpect(status().isOk());
    }

    @Test
    void theEmployerSeesItsOwnSchemesCertificatesOnly() throws Exception {
        // D-36 / NS-GL01-005: the sponsor sees its own members' certificates, never another scheme's.
        String portal = mockMvc.perform(get("/ops/portal").with(signedIn("employer", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeRef").value("SCH-001"))
                .andExpect(jsonPath("$.certificates").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> schemes = JsonPath.read(portal, "$.certificates[*].schemeRef");
        org.assertj.core.api.Assertions.assertThat(schemes).isNotEmpty().allMatch("SCH-001"::equals);
        java.util.List<String> numbers = JsonPath.read(portal, "$.certificates[*].certificateNo");
        org.assertj.core.api.Assertions.assertThat(numbers).allMatch(n -> n.startsWith("SCH-001-"));
        // The member awaiting underwriting is certified for the free cover in force, with the excess shown pending.
        mockMvc.perform(get("/ops/portal").with(signedIn("employer", "password")))
                .andExpect(jsonPath("$.certificates[?(@.pendingCover > 0)].acceptedCover").isNotEmpty());
        org.assertj.core.api.Assertions.assertThat(portal).doesNotContain("uwReason").doesNotContain("underwritingReason");
        // The staff certificate endpoints stay closed to the partner login.
        mockMvc.perform(get("/group-life/schemes/SCH-002/certificates").with(signedIn("employer", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/ops/portal").with(signedIn("lender", "password")))
                .andExpect(jsonPath("$.certificates").doesNotExist());
    }

    @Test
    void slaTimeIsCountedInWorkingHours() {
        java.util.Map<String, Object> calendar = java.util.Map.of("workStartHour", 8, "workEndHour", 17,
                "workingDays", "MON,TUE,WED,THU,FRI", "holidays", "");
        // Friday 15:00 + 4 working hours = Monday 10:00.
        java.time.LocalDateTime due = OpsService.addWorkingHours(java.time.LocalDateTime.of(2026, 11, 13, 15, 0), 4, calendar);
        org.assertj.core.api.Assertions.assertThat(due).isEqualTo(java.time.LocalDateTime.of(2026, 11, 16, 10, 0));
        // A start with seconds in working hours carries on past the first close: Monday 11:32:52 + 72 working hours
        // (8 days of 9) = Thursday of the next week at 11:32:52, not about 17:00 the same day.
        java.time.LocalDateTime opened = java.time.LocalDateTime.of(2026, 11, 23, 11, 32, 52, 935_911_000);
        org.assertj.core.api.Assertions.assertThat(OpsService.addWorkingHours(opened, 72, calendar))
                .isEqualTo(java.time.LocalDateTime.of(2026, 12, 3, 11, 32, 52, 935_911_000));
    }
}

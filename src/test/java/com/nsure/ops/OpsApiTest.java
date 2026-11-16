package com.nsure.ops;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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
        mockMvc.perform(get("/ops/portal").with(httpBasic("lender", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portalType").value("LENDER"))
                .andExpect(jsonPath("$.scopeRef").value("LND-0001"))
                .andExpect(jsonPath("$.loans").isArray());
        mockMvc.perform(get("/ops/portal").with(httpBasic("employer", "password")))
                .andExpect(jsonPath("$.portalType").value("EMPLOYER"))
                .andExpect(jsonPath("$.members").isArray());
        mockMvc.perform(get("/ops/portal").with(httpBasic("agent", "password")))
                .andExpect(jsonPath("$.portalType").value("AGENT"));
        mockMvc.perform(get("/portfolio/claims").with(httpBasic("lender", "password"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/credit/loans").with(httpBasic("lender", "password"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/ops/queue").with(httpBasic("lender", "password"))).andExpect(status().isForbidden());

        // A partner's request gets a reference and lands in the staff queue with an SLA.
        String ref = JsonPath.read(mockMvc.perform(post("/ops/portal/requests").with(httpBasic("lender", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestType\":\"NEW_LOAN\",\"detail\":\"Loan LSH-9 for T Molefe, 80,000 over 24 months\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        String key = "PORTAL_REQUEST:" + ref;
        mockMvc.perform(get("/ops/queue").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.key == '" + key + "')].team").value("Operations"))
                .andExpect(jsonPath("$[?(@.key == '" + key + "')].dueAt").isNotEmpty());
        // Ownership is taken once: a second person is told who has it.
        mockMvc.perform(post("/ops/queue/claim").param("key", key).with(httpBasic("admin", "password"))).andExpect(status().isOk());
        mockMvc.perform(post("/ops/queue/claim").param("key", key).with(httpBasic("secadmin", "password"))).andExpect(status().isConflict());
        mockMvc.perform(post("/ops/portal-requests/" + ref + "/close").with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Loan registered as LN-000009\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/ops/portal").with(httpBasic("lender", "password")))
                .andExpect(jsonPath("$.requests[0].status").value("DONE"));

        // Definitions, the calendar, documents and payouts.
        mockMvc.perform(put("/ops/workflow/definitions/UNDERWRITING").with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slaHours\":24}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitions[?(@.code == 'UNDERWRITING')].versionNo").value(2));
        mockMvc.perform(get("/ops/documents").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retention['Policy document']").value(10));
        mockMvc.perform(get("/ops/payouts").with(httpBasic("admin", "password"))).andExpect(status().isOk());
    }

    @Test
    void slaTimeIsCountedInWorkingHours() {
        java.util.Map<String, Object> calendar = java.util.Map.of("workStartHour", 8, "workEndHour", 17,
                "workingDays", "MON,TUE,WED,THU,FRI", "holidays", "");
        // Friday 15:00 + 4 working hours = Monday 10:00.
        java.time.LocalDateTime due = OpsService.addWorkingHours(java.time.LocalDateTime.of(2026, 11, 13, 15, 0), 4, calendar);
        org.assertj.core.api.Assertions.assertThat(due).isEqualTo(java.time.LocalDateTime.of(2026, 11, 16, 10, 0));
    }
}

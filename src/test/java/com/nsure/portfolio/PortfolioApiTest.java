package com.nsure.portfolio;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-portfolio;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PortfolioApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void portfolioRequiresAuthenticationAndReturnsSeededRecords() throws Exception {
        mockMvc.perform(get("/portfolio/customers")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/portfolio/customers").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'CUS-1001')].name").value("Amara Dube"));
        mockMvc.perform(get("/portfolio/applications").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'APP-2048')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/policies").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'POL-10842')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/claims").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'CLM-0842')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/products").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").isNotEmpty());
        mockMvc.perform(get("/portfolio/schemes").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'SCH-001')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/receipts").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'INV-0091')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/product-configs").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'CFG-001')].code").value("TL-ESS"));
    }

    @Test
    void createsAndUpdatesPortfolioRecords() throws Exception {
        mockMvc.perform(post("/portfolio/customers")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Lerato Ndlovu","email":"lerato@example.com","phone":"+267 71 000 100"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("CUS-1007"))
                .andExpect(jsonPath("$.status").value("Pending KYC"));

        mockMvc.perform(post("/portfolio/applications")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Lerato Ndlovu","product":"Term Life","amount":250000,"premium":180}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("APP-2049"))
                .andExpect(jsonPath("$.status").value("Under review"));

        mockMvc.perform(put("/portfolio/applications/APP-2049")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Accepted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Accepted"));

        mockMvc.perform(post("/portfolio/policies")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Amara Dube","product":"Term Life","amount":500000,"premium":420}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("POL-10843"));

        // A claim is lodged against a policy; its status is the workflow's, never sent by the client.
        mockMvc.perform(post("/portfolio/claims")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policy":"POL-10843","claimantName":"Thato Dube","claimantMobile":"+267 71 000 002",
                                 "claimantRelationship":"Spouse","type":"Death","description":"Demo","eventDate":"2026-10-01",
                                 "status":"Approved"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("CLM-0843"))
                .andExpect(jsonPath("$.type").value("Death"))
                .andExpect(jsonPath("$.policy").value("POL-10843"))
                .andExpect(jsonPath("$.status").value("Under Review"));

        mockMvc.perform(put("/portfolio/claims/CLM-0843")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Approved\"}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/portfolio/claims/CLM-0843/reject")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Duplicate of an earlier claim\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Rejected"))
                .andExpect(jsonPath("$.rejectionReason").value("Duplicate of an earlier claim"));

        mockMvc.perform(put("/portfolio/receipts/INV-0091")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Paid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Paid"));

        mockMvc.perform(post("/portfolio/product-configs")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Group Life","code":"GL-01","status":"Draft","benefits":["Death benefit"],"channels":["Broker portal"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("CFG-004"))
                .andExpect(jsonPath("$.code").value("GL-01"));

        mockMvc.perform(put("/portfolio/product-configs/CFG-004")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Group Life Plus","code":"GL-01","status":"In review","benefits":["Death benefit"],"channels":["Broker portal"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Group Life Plus"))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(put("/portfolio/applications/APP-0000")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Accepted\"}"))
                .andExpect(status().isNotFound());
    }
}

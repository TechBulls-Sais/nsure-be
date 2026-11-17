package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

        mockMvc.perform(get("/portfolio/customers").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'CUS-1001')].name").value("Amara Dube"));
        mockMvc.perform(get("/portfolio/applications").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'APP-2048')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/policies").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'POL-10842')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'CLM-0842')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/products").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").isNotEmpty());
        mockMvc.perform(get("/portfolio/schemes").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'SCH-001')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/receipts").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'INV-0091')]").isNotEmpty());
        mockMvc.perform(get("/portfolio/product-configs").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'CFG-001')].code").value("TL-ESS"));
    }

    @Test
    void createsAndUpdatesPortfolioRecords() throws Exception {
        mockMvc.perform(post("/portfolio/customers")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Lerato Ndlovu","email":"lerato@example.com","phone":"+267 71 000 100"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("CUS-1007"))
                .andExpect(jsonPath("$.status").value("Pending KYC"));

        mockMvc.perform(post("/portfolio/applications")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"declarationAccepted":true,"disclosures":{"TOBACCO":"NO","TREATMENT":"NO"},"customerRef":"CUS-1007","product":"Term Life","amount":250000,"premium":180}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("APP-2049"))
                // NS-IL01-003: identity verification is outstanding while the customer is Pending KYC.
                .andExpect(jsonPath("$.status").value("Evidence needed"));

        mockMvc.perform(put("/portfolio/applications/APP-2049")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Accepted\"}"))
                // An application's state comes from its workflow: an arbitrary status is refused.
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/portfolio/applications/APP-2049")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Withdrawn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Withdrawn"));

        mockMvc.perform(post("/portfolio/policies")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Amara Dube","product":"Term Life","amount":500000,"premium":420}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("POL-10843"));
        // NS-IL01-006: a staff issue is an offer; recording the first premium issues cover.
        mockMvc.perform(post("/portfolio/policies/POL-10843/payments")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));

        // A claim is lodged against a policy; its status is the workflow's, never sent by the client.
        mockMvc.perform(post("/portfolio/claims")
                        .with(signedIn("admin", "password"))
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
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Approved\"}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/portfolio/claims/CLM-0843/reject")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Duplicate of an earlier claim\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Rejected"))
                .andExpect(jsonPath("$.rejectionReason").value("Duplicate of an earlier claim"));

        // NS-SH04-005: a receipt is never re-statused in place; only a payment or a recorded reversal changes it.
        String receiptsBefore = mockMvc.perform(get("/portfolio/receipts").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(put("/portfolio/receipts/INV-0091")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Paid\"}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/portfolio/receipts").with(signedIn("admin", "password")))
                .andExpect(content().json(receiptsBefore));

        mockMvc.perform(post("/portfolio/product-configs")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Group Life","code":"GL-01","status":"Draft","benefits":["Death benefit"],"channels":["Broker portal"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("CFG-004"))
                .andExpect(jsonPath("$.code").value("GL-01"));

        mockMvc.perform(put("/portfolio/product-configs/CFG-004")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Group Life Plus","code":"GL-01","status":"In review","benefits":["Death benefit"],"channels":["Broker portal"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Group Life Plus"))
                .andExpect(jsonPath("$.version").value(2));
        // NS-SH02-003, NS-DAT-004: the save replaced nothing; both states are kept.
        mockMvc.perform(get("/portfolio/product-configs/CFG-004/versions").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].snapshot.name").value("Group Life Plus"))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].snapshot.name").value("Group Life"))
                .andExpect(jsonPath("$[1].status").value("Draft"));
        // Maker-checker: whoever wrote the content cannot make it Active, and activation cannot carry changes.
        String reviewed = """
                {"name":"Group Life Plus","code":"GL-01","status":"Active","benefits":["Death benefit"],"channels":["Broker portal"]}
                """;
        mockMvc.perform(put("/portfolio/product-configs/CFG-004").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content(reviewed))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/portfolio/product-configs/CFG-004").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content(reviewed.replace("Group Life Plus", "Group Life Max")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/portfolio/product-configs").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewed.replace("GL-01", "GL-02")))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/portfolio/applications/APP-0000")
                        .with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Accepted\"}"))
                .andExpect(status().isNotFound());
    }
}

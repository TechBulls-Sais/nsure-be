package com.nsure.ops;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Spec 04 partner workspaces (work package e): an agent quotes for its own clients and sends an application, an
 * employer sends a census, a lender sends a loan with the borrower's consent and permitted purpose and a balance
 * file; staff process each through the owning module and the partner sees the result.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-partner-workspaces;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PartnerWorkspacesApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anAgentQuotesForItsOwnClientAndItsApplicationIsCreatedByStaff() throws Exception {
        String client = customer("Gofaone Client", "gofaone.c@example.com");
        // Not yet in the agent's book: no quote.
        mockMvc.perform(json(post("/ops/portal/quote"), "agent", "{\"customerRef\":\"" + client
                        + "\",\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isNotFound());
        String ref = JsonPath.read(mockMvc.perform(json(post("/ops/portal/submissions"), "agent",
                        "{\"type\":\"NEW_APPLICATION\",\"customerRef\":\"" + client + "\",\"product\":\"Term Life\","
                                + "\"tier\":\"Silver\",\"declarationAccepted\":true,"
                                + "\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/ops/portal/submissions"), "agent", "{\"type\":\"NEW_LOAN\"}"))
                .andExpect(status().isUnprocessableEntity());
        // Staff without the application permission cannot process it; staff who may, do.
        mockMvc.perform(json(post("/ops/portal-requests/" + ref + "/process"), "compliance", "{}"))
                .andExpect(status().isForbidden());
        String app = JsonPath.read(mockMvc.perform(json(post("/ops/portal-requests/" + ref + "/process"), "admin", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andReturn().getResponse().getContentAsString(), "$.resultRef");
        mockMvc.perform(get("/ops/portal").with(signedIn("agent", "password")))
                .andExpect(jsonPath("$.applications[*].recordRef").value(hasItem(app)))
                .andExpect(jsonPath("$.requests[?(@.recordRef == '" + ref + "')].result")
                        .value(hasItem(containsString(app))))
                .andExpect(jsonPath("$.statements").isArray())
                .andExpect(jsonPath("$.arrears").isArray());
        // Now a client in the book: the agent prices cover with the real rating service.
        mockMvc.perform(json(post("/ops/portal/quote"), "agent", "{\"customerRef\":\"" + client
                        + "\",\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/ops/portal/quote"), "lender", "{\"customerRef\":\"" + client
                        + "\",\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEmployerSendsACensusThatStaffStageAndTheEmployerSeesTheRowErrors() throws Exception {
        String csv = "memberKey,firstName,lastName,dateOfBirth,category,salary,action,effectiveDate\\n"
                + "EMP-901,Tlotlo,Ramotswe,1990-01-01,NO_SUCH_CATEGORY,120000,ADD,2026-12-01";
        mockMvc.perform(json(post("/ops/portal/submissions"), "employer", "{\"type\":\"CENSUS\",\"csv\":\"name,age\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("census header")));
        mockMvc.perform(json(post("/ops/portal/submissions"), "employer", "{\"type\":\"JOINER_LEAVER\",\"kind\":\"LEAVER\","
                        + "\"memberKey\":\"NOBODY-1\",\"effectiveDate\":\"2026-12-01\"}"))
                .andExpect(status().isUnprocessableEntity());
        String ref = JsonPath.read(mockMvc.perform(json(post("/ops/portal/submissions"), "employer",
                        "{\"type\":\"CENSUS\",\"csv\":\"" + csv + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/ops/portal-requests/" + ref + "/process"), "admin", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(containsString("Census batch CEN-")))
                .andExpect(jsonPath("$.result").value(containsString("line 2")));
        mockMvc.perform(get("/ops/portal").with(signedIn("employer", "password")))
                .andExpect(jsonPath("$.requests[?(@.recordRef == '" + ref + "')].status").value(hasItem("DONE")));
    }

    @Test
    void aLenderSendsALoanOnlyWithConsentForCreditLifeAndStaffRegisterIt() throws Exception {
        String borrower = customer("Masa Borrower", "masa.b@example.com");
        String loan = "\"type\":\"NEW_LOAN\",\"lenderLoanId\":\"LET-77-01\",\"customerRef\":\"" + borrower
                + "\",\"principal\":\"50000\",\"termMonths\":\"24\"";
        mockMvc.perform(json(post("/ops/portal/submissions"), "lender", "{" + loan + ",\"purpose\":\"CREDIT_LIFE_COVER\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("consented")));
        mockMvc.perform(json(post("/ops/portal/submissions"), "lender", "{" + loan + ",\"borrowerConsent\":true,"
                        + "\"purpose\":\"MARKETING\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("credit life cover")));
        String ref = JsonPath.read(mockMvc.perform(json(post("/ops/portal/submissions"), "lender", "{" + loan
                        + ",\"borrowerConsent\":true,\"purpose\":\"CREDIT_LIFE_COVER\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/ops/portal-requests/" + ref + "/process"), "credit", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(containsString("LET-77-01 registered as")));
        mockMvc.perform(get("/ops/portal").with(signedIn("lender", "password")))
                .andExpect(jsonPath("$.loans[*].lenderLoanId").value(hasItem("LET-77-01")));
        // A balance row for a loan that is not the lender's is refused when sent.
        mockMvc.perform(json(post("/ops/portal/submissions"), "lender", "{\"type\":\"BALANCE_UPDATE\","
                        + "\"batchReference\":\"BAL-1\",\"rows\":[{\"lenderLoanId\":\"SOMEONE-ELSE\","
                        + "\"effectiveDate\":\"2026-11-01\",\"balance\":\"1000\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("SOMEONE-ELSE")));
    }

    private String customer(String name, String email) throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                        + "\",\"email\":\"" + email + "\",\"phone\":\"+267 71 555 000\",\"dateOfBirth\":\"1985-05-05\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "admin", "{}")).andExpect(status().isOk());
        return ref;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

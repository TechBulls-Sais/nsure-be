package com.nsure.servicing;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** NS-IL04-006: an adult insured life's medical evidence reaches the policyholder only with that life's consent. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-life-consent;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class LifeConsentApiTest {

    private static final byte[] PDF = "%PDF-1.4\n% medical report\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void thePolicyholderOpensAnAdultDependantsMedicalReportOnlyWithTheirConsent() throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"Boitumelo Holder\",\"email\":\"boitumelo.holder@example.com\",\"phone\":\"+267 71 600 001\","
                                + "\"username\":\"boitumelo\",\"password\":\"password1\",\"dateOfBirth\":\""
                                + LocalDate.now().minusYears(40) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                        + customer + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\",\"lives\":[{\"relationship\":"
                        + "\"Spouse\",\"fullName\":\"Kenosi Holder\",\"dateOfBirth\":\"" + LocalDate.now().minusYears(38)
                        + "\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        String claim = JsonPath.read(mockMvc.perform(json(post("/portfolio/claims"), "assessor", "{\"policy\":\"" + policy
                        + "\",\"claimantName\":\"Boitumelo Holder\",\"claimantMobile\":\"+267 72 000 000\","
                        + "\"claimantRelationship\":\"Spouse\",\"eventDate\":\"" + LocalDate.now()
                        + "\",\"description\":\"Admitted to hospital.\",\"coveredLife\":\"Kenosi Holder\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(multipart("/portfolio/claims/" + claim + "/documents")
                        .file(new MockMultipartFile("file", "report.pdf", "application/pdf", PDF))
                        .param("type", "Medical report").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        String queue = mockMvc.perform(get("/portfolio/document-quarantine").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        Integer upload = ((List<Integer>) JsonPath.read(queue, "$[?(@.ownerRef == '" + claim + "')].id")).get(0);
        Integer document = JsonPath.read(mockMvc.perform(json(post("/portfolio/document-quarantine/" + upload + "/release"),
                        "assessor2", "{\"note\":\"Opened and checked\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.documentId");

        // The spouse is an adult and has not consented: the policyholder sees the claim but not the medical report.
        mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + document).with(signedIn("boitumelo", "password1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Kenosi Holder's medical evidence")));

        String view = mockMvc.perform(get("/servicing/policies/" + policy).with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        Integer spouse = ((List<Integer>) JsonPath.read(view, "$.lives[?(@.relationship == 'Spouse')].id")).get(0);
        Integer main = ((List<Integer>) JsonPath.read(view, "$.lives[?(@.mainInsured == true)].id")).get(0);
        mockMvc.perform(json(post("/servicing/policies/" + policy + "/lives/" + main + "/consent"), "admin",
                        "{\"status\":\"GRANTED\",\"evidence\":\"Not needed\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/servicing/policies/" + policy + "/lives/" + spouse + "/consent"), "support",
                        "{\"status\":\"GRANTED\",\"evidence\":\"Signed consent form\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/servicing/policies/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.consents[0].lifeName").value("Kenosi Holder"))
                .andExpect(jsonPath("$.consents[0].status").value("GRANTED"))
                .andExpect(jsonPath("$.consents[0].adult").value(true));
        mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + document).with(signedIn("boitumelo", "password1")))
                .andExpect(status().isOk());

        // Withdrawn consent applies from then on; the history keeps both decisions.
        mockMvc.perform(json(post("/servicing/policies/" + policy + "/lives/" + spouse + "/consent"), "support",
                        "{\"status\":\"WITHDRAWN\",\"evidence\":\"Phoned in to withdraw consent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consents.length()").value(2));
        mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + document).with(signedIn("boitumelo", "password1")))
                .andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

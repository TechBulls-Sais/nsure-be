package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * AT-SH11-01 across the customer channels: a customer with a valid login but no relationship to another customer's
 * policy gets none of its data through the portal, its documents, its declaration, payment, beneficiary or claim
 * routes, the service desk or identity evidence, nor through any staff route.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-customer-scope;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CustomerScopeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aCustomerWithoutARelationshipToAPolicyGetsNothingOfItOnAnyChannel() throws Exception {
        String owner = customer("Boipelo Owner", "boipelo.owner", "917000001");
        customer("Tiro Stranger", "tiro.stranger", "917000002");
        String policy = JsonPath.read(mockMvc.perform(post("/portfolio/policies").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerRef\":\"" + owner + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");

        // The owner sees it, with when it last changed (NS-UX-001); the stranger's own portal has no trace of it.
        assertThat(body(get("/portfolio/shop"), "boipelo.owner")).contains(policy);
        assertThat(JsonPath.<String>read(body(get("/portfolio/shop"), "boipelo.owner"), "$.lastUpdated['" + policy + "']"))
                .isNotBlank();
        String stranger = body(get("/portfolio/shop"), "tiro.stranger");
        assertThat(stranger).doesNotContain(policy).doesNotContain("Boipelo Owner").doesNotContain(owner);

        String json = "{\"fullName\":\"Someone Else\",\"relationship\":\"Spouse\",\"nationalId\":\"123456789\","
                + "\"mobile\":\"+267 71 000 111\",\"payoutMethod\":\"Bank\",\"accountRef\":\"1234\",\"policy\":\""
                + policy + "\",\"type\":\"Death\",\"description\":\"Not mine\",\"eventDate\":\"2026-10-01\","
                + "\"claimantName\":\"Tiro Stranger\",\"claimantMobile\":\"+267 71 000 112\","
                + "\"claimantRelationship\":\"Friend\",\"method\":\"Card\",\"code\":\"123456\",\"accuracy\":true,"
                + "\"terms\":true}";
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                post("/portfolio/shop/policies/" + policy + "/declaration/otp"),
                post("/portfolio/shop/policies/" + policy + "/declaration"),
                post("/portfolio/shop/policies/" + policy + "/pay"),
                post("/checkout/" + policy + "/pay"),
                post("/portfolio/shop/policies/" + policy + "/beneficiaries"),
                delete("/portfolio/shop/policies/" + policy + "/beneficiaries/1"),
                post("/portfolio/shop/policies/" + policy + "/documents/schedule"),
                get("/portfolio/shop/policies/" + policy + "/documents/1"),
                post("/portfolio/shop/claims"),
                // Staff routes are closed to a customer whatever the record.
                get("/portfolio/policies"),
                get("/portfolio/customers"),
                get("/portfolio/customers/" + owner + "/identity-documents"),
                get("/portfolio/policies/" + policy + "/documents"),
                get("/servicing/policies/" + policy)}) {
            MvcResult result = mockMvc.perform(request.with(signedIn("tiro.stranger", "password123"))
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI())
                    .isIn(403, 404);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("Boipelo Owner");
        }
        // The service desk and identity evidence list only the stranger's own records.
        assertThat(body(get("/portfolio/shop/cases"), "tiro.stranger")).doesNotContain(policy).doesNotContain(owner);
        assertThat(body(get("/portfolio/shop/identity-documents"), "tiro.stranger")).isEqualTo("[]");
        // Nothing the stranger tried changed the owner's policy.
        assertThat(body(get("/portfolio/shop"), "boipelo.owner")).doesNotContain("Someone Else");
    }

    private String body(MockHttpServletRequestBuilder request, String user) throws Exception {
        return mockMvc.perform(request.with(signedIn(user, "password123")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String customer(String name, String username, String omang) throws Exception {
        String ref = JsonPath.read(mockMvc.perform(post("/portfolio/customers").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\","
                                + "\"phone\":\"+267 71 555 666\",\"poiType\":\"Omang\",\"poiNumber\":\"" + omang + "\","
                                + "\"dateOfBirth\":\"1989-05-05\",\"username\":\"" + username
                                + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }
}

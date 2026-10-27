package com.nsure.portfolio;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-shop;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CustomerPortalApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void customerLoginsReachOnlyTheCustomerPortal() throws Exception {
        mockMvc.perform(get("/me").with(httpBasic("customer", "password"))).andExpect(status().isOk());
        mockMvc.perform(get("/portfolio/shop").with(httpBasic("customer", "password"))).andExpect(status().isOk());

        for (String staffPath : new String[] {"/portfolio/policies", "/portfolio/customers", "/portfolio/applications",
                "/portfolio/products", "/admin/rate-tables"}) {
            mockMvc.perform(get(staffPath).with(httpBasic("customer", "password")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Customer accounts can only use the customer portal."));
            mockMvc.perform(get(staffPath).with(httpBasic("admin", "password"))).andExpect(status().isOk());
        }
        mockMvc.perform(get("/portfolio/policies")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerBuysAPolicyAfterKycSuccess() throws Exception {
        mockMvc.perform(get("/me").with(httpBasic("customer", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerPortal").value(true))
                .andExpect(jsonPath("$.canReviewKyc").value(false))
                .andExpect(jsonPath("$.roles[0]").value("Policyholder"));

        mockMvc.perform(get("/me").with(httpBasic("onboarding", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerPortal").value(false))
                .andExpect(jsonPath("$.canReviewKyc").value(true))
                .andExpect(jsonPath("$.roles[0]").value("Onboarding Specialist"));

        mockMvc.perform(post("/portfolio/shop")
                        .with(httpBasic("customer", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Term Life\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("KYC must be successful before you can buy a policy."));

        // Onboarding reviews customers; adding a policy needs CREATE_POLICY, which it does not hold.
        mockMvc.perform(post("/portfolio/policies")
                        .with(httpBasic("onboarding", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neo Modise\",\"product\":\"Term Life\",\"amount\":500000,\"premium\":420}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/portfolio/policies")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neo Modise\",\"product\":\"Term Life\",\"amount\":500000,\"premium\":420}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mark KYC successful before adding a policy."));

        mockMvc.perform(post("/portfolio/customers")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dineo Motsumi","email":"dineo@example.com","phone":"+267 71 111 222","username":"dineo","password":"password1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginUsername").value("dineo"))
                .andExpect(jsonPath("$.kycStatus").value("PENDING"));

        mockMvc.perform(get("/me").with(httpBasic("dineo", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerPortal").value(true));

        mockMvc.perform(post("/portfolio/customers/CUS-1006/kyc").with(httpBasic("customer", "password")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/portfolio/customers/CUS-1006/kyc").with(httpBasic("onboarding", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.status").value("Verified"));

        mockMvc.perform(post("/portfolio/shop")
                        .with(httpBasic("customer", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Care Cash\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.kycStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.policies[?(@.product == 'Care Cash')]").isEmpty())
                .andExpect(jsonPath("$.requests[?(@.product == 'Care Cash')].status").value("Pending Payment"));
    }
}

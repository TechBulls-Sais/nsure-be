package com.nsure.reference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nsure.controls.FourEyesControl;
import com.nsure.security.capability.Capability;
import com.nsure.security.exception.NoAuthorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-foundation;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class FoundationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FourEyesControl fourEyes;

    @Test
    void referenceDataIsPublicAndMatchesTheSpecSeed() throws Exception {
        mockMvc.perform(get("/reference/premium-frequencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.frequencyName == 'Monthly')].months").value(1))
                .andExpect(jsonPath("$[?(@.frequencyName == 'Semi-Annually')].months").value(6))
                .andExpect(jsonPath("$[?(@.frequencyName == 'One-time')].months").value(0));

        mockMvc.perform(get("/reference/relationships/life-assured"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.relationshipName == 'Spouse')]").isNotEmpty());

        mockMvc.perform(get("/reference/relationships/family-details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));

        mockMvc.perform(get("/reference/identity-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Omang')]").isNotEmpty());

        mockMvc.perform(get("/reference/relationships/not-a-screen"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown screen not-a-screen"))
                .andExpect(jsonPath("$.message").value("Unknown screen not-a-screen"));
    }

    @Test
    void controlsRequireTheReadCapability() throws Exception {
        mockMvc.perform(get("/admin/controls")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "controls.read")
    void seededControlsAreVisibleToAReader() throws Exception {
        mockMvc.perform(get("/admin/controls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[?(@.key == 'claim.stp_ceiling')].numericValue").value(5000.00))
                .andExpect(jsonPath("$[?(@.key == 'claim.four_eyes')].enabled").value(true))
                .andExpect(jsonPath("$[?(@.key == 'claim.four_eyes')].failMode").value("CLOSED"));
    }

    @Test
    void fourEyesStopsTheAssessorApprovingTheirOwnClaim() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NoAuthorizationException.class, () -> fourEyes.assertClaimApproval(7L, 7L));
        org.junit.jupiter.api.Assertions.assertTrue(fourEyes.assertClaimApproval(7L, 8L));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> fourEyes.assertClaimApproval(null, 8L));
    }

    @Test
    void unknownCapabilityAndProtectedRoleAreLoud() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> Capability.require("not.a.capability"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Capability.assertGrantable("Policyholder / End User", "policy.read"));
    }
}

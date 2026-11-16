package com.nsure.reference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nsure.controls.FourEyesControl;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.PermissionCatalog;
import com.nsure.security.exception.NoAuthorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Unknown screen not-a-screen"))
                .andExpect(jsonPath("$.message").value("Unknown screen not-a-screen"));
    }

    @Test
    void controlsRequireTheReadCapability() throws Exception {
        mockMvc.perform(get("/admin/controls")).andExpect(status().isUnauthorized());
    }

    @Test
    void seededControlsAreVisibleToAReader() throws Exception {
        // Actuary holds controls.read (READ_CONTROL) in the spec grant matrix.
        mockMvc.perform(get("/admin/controls").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.httpBasic("actuary", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[?(@.key == 'claim.stp_ceiling')].numericValue").value(5000.00))
                .andExpect(jsonPath("$[?(@.key == 'claim.four_eyes')].enabled").value(true))
                .andExpect(jsonPath("$[?(@.key == 'claim.four_eyes')].failMode").value("CLOSED"));
    }

    @Test
    void fourEyesStopsTheAssessorApprovingTheirOwnClaim() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NoAuthorizationException.class, () -> fourEyes.assertClaimApproval(7L, user(7L), "I am sure"));
        org.junit.jupiter.api.Assertions.assertEquals(
                FourEyesControl.Outcome.ENFORCED, fourEyes.assertClaimApproval(7L, user(8L), null));
        // C-8: no recorded assessment is a denial, not a pass.
        org.junit.jupiter.api.Assertions.assertThrows(
                NoAuthorizationException.class, () -> fourEyes.assertClaimApproval(null, user(8L), null));
    }

    private static AppUser user(long id) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername("user" + id);
        return user;
    }

    @Test
    void unknownPermissionCodesAreLoud() {
        // Spec 04 A-4: a typo must be loud, not a silent deny.
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> PermissionCatalog.require("READ_CLAMS"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> PermissionCatalog.codesFor("not.a.capability"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> user(1L).hasPermissionTo("READ_CLAMS"));
    }
}

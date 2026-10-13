package com.nsure.mipas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nsure.mipas.capability.Capability;
import com.nsure.mipas.capability.CapabilityService;
import com.nsure.mipas.capability.MipasCapabilityRepository;
import com.nsure.mipas.control.ControlKey;
import com.nsure.mipas.control.ControlService;
import com.nsure.mipas.control.FourEyesControl;
import com.nsure.security.exception.NoAuthorizationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MipasFoundationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ControlService controls;

    @Autowired
    private FourEyesControl fourEyes;

    @Autowired
    private CapabilityService capabilities;

    @Autowired
    private MipasCapabilityRepository capabilityRows;

    @Test
    void everyControlKeyHasASeededRowAndAValueWhenNeeded() {
        List<String> seeded = controls.all().stream().map(row -> row.getControlKey()).toList();
        assertThat(seeded).containsExactlyInAnyOrder(ControlKey.all().stream().map(ControlKey::key).toArray(String[]::new));
        assertThat(controls.disabled()).isEmpty();
        assertThat(controls.valueOf(ControlKey.CLAIM_STP_CEILING)).isEqualByComparingTo("5000.00");
        assertThat(controls.isEnabled(ControlKey.CLAIM_FOUR_EYES)).isTrue();
    }

    @Test
    void capabilityVocabularyMatchesTheDatabaseAndPolicyholderHasNoGrants() {
        assertThat(capabilityRows.findAll()).extracting(row -> row.getCode())
                .containsExactlyInAnyOrder(Capability.codes().toArray(String[]::new));
        assertThat(capabilities.codesForRole("Policyholder / End User")).isEmpty();
        assertThat(capabilities.codesForRole("Actuary")).contains("rate.publish", "controls.read");
        assertThat(capabilities.codesForRole("Product Configurator")).doesNotContain("rate.publish");
        assertThat(capabilities.codesForRole("Product Configurator")).contains("rate.write", "product.delete");
    }

    @Test
    void assessorCannotApproveOwnClaimAndUnassessedClaimIsDenied() {
        assertThat(fourEyes.assertClaimApproval(11L, 22L, false, null)).isTrue();
        assertThatThrownBy(() -> fourEyes.assertClaimApproval(22L, 22L, false, null))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessageContaining("cannot also authorise");
        assertThatThrownBy(() -> fourEyes.assertClaimApproval(null, 22L, false, null))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessageContaining("no recorded assessment");
    }

    @Test
    void adminCanReadControlsAndAnonymousCannot() throws Exception {
        mvc.perform(get("/controls")).andExpect(status().isUnauthorized());
        mvc.perform(get("/controls").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));
        mvc.perform(get("/reference/premium-frequencies").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Semi-Annually')].months").value(6));
        mvc.perform(get("/capabilities").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vocabulary.length()").value(26))
                .andExpect(jsonPath("$.roles.length()").value(7));
    }
}

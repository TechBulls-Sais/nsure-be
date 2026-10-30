package com.nsure.configuration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Insurer configuration as data (guide §7, §12, §15): a release goes draft → review → approved → active under
 * separate duties, new business must satisfy the active policy form, and an old policy keeps validating against the
 * form it was issued under after a stricter form is activated.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-configuration;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ConfigurationReleaseApiTest {

    private static final String EMPLOYER = "{\"key\":\"employerReference\",\"type\":\"STRING\",\"label\":\"Employer"
            + " reference\",\"required\":true,\"maxLength\":20}";
    private static final String PAYROLL = "{\"key\":\"payrollNumber\",\"type\":\"STRING\",\"label\":\"Payroll"
            + " number\",\"required\":true}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theInstallationDescribesItsInsurerWithoutSecrets() throws Exception {
        mockMvc.perform(get("/public/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insurerCode").value("test-insurer"))
                .andExpect(jsonPath("$.environment").value("test"))
                .andExpect(jsonPath("$.currency").value("BWP"))
                .andExpect(jsonPath("$.bindOnFirstStart").doesNotExist());
        mockMvc.perform(get("/admin/deployment").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insurerCode").value("test-insurer"))
                .andExpect(jsonPath("$.installationUuid").isNotEmpty())
                .andExpect(jsonPath("$.schema.changesets").value(Matchers.greaterThan(10)))
                .andExpect(jsonPath("$.activeConfigReleases[0]").value("DEMO-R1"));
        mockMvc.perform(get("/admin/deployment").with(httpBasic("customer", "password")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aReleaseNeedsADifferentApproverAndNewBusinessMustMeetTheActiveForm() throws Exception {
        long[] draft1 = release("TL-R1", "[" + EMPLOYER + "]");
        long r1 = draft1[0];

        // Only drafts change, and only from the version you read.
        mockMvc.perform(json(put("/admin/config-releases/" + r1), "configurator",
                        "{\"expectedVersion\":99,\"description\":\"x\",\"forms\":[]}"))
                .andExpect(status().isConflict());
        long v = act(r1, "submit", "configurator", draft1[1], 200);
        // The maker cannot approve their own release…
        mockMvc.perform(json(post("/admin/config-releases/" + r1 + "/approve"), "configurator",
                        "{\"expectedVersion\":" + v + "}"))
                .andExpect(status().isForbidden());
        // …an actuary who did not draft it can.
        v = act(r1, "approve", "actuary", v, 200);
        mockMvc.perform(json(post("/admin/config-releases/" + r1 + "/activate"), "actuary",
                        "{\"expectedVersion\":" + v + "}"))
                .andExpect(status().isForbidden());
        act(r1, "activate", "ops", v, 200);

        String form = mockMvc.perform(get("/configuration/forms/policy?product=Term Life")
                        .with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.fields[0].key").value("employerReference"))
                .andExpect(jsonPath("$.fields[0].required").value(true))
                .andReturn().getResponse().getContentAsString();
        int formV1 = JsonPath.read(form, "$.formVersionId");

        String customer = customer("Lorato Phiri", "lorato");
        mockMvc.perform(json(post("/portfolio/policies"), "admin", policy(customer, "{}", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("employerReference")));
        mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        policy(customer, "{\"employerReference\":\"EMP-1\",\"shoeSize\":9}", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("shoeSize")));
        mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        policy(customer, "{\"employerReference\":\"" + "X".repeat(21) + "\"}", null)))
                .andExpect(status().isBadRequest());
        String issued = mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        policy(customer, "{\"employerReference\":\"EMP-1024\"}", formV1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String oldPolicy = JsonPath.read(issued, "$.id");
        mockMvc.perform(get("/portfolio/policies/" + oldPolicy + "/attributes").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.form.formVersionId").value(formV1))
                .andExpect(jsonPath("$.values.employerReference").value("EMP-1024"))
                .andExpect(jsonPath("$.currentForm").value(true));

        // A stricter release: Term Life now also needs a payroll number (B's sixth field).
        long[] draft2 = release("TL-R2", "[" + EMPLOYER + "," + PAYROLL + "]");
        long r2 = draft2[0];
        v = act(r2, "submit", "configurator", draft2[1], 200);
        v = act(r2, "approve", "actuary", v, 200);
        act(r2, "activate", "ops", v, 200);
        mockMvc.perform(get("/admin/config-releases/" + r1).with(httpBasic("ops", "password")))
                .andExpect(jsonPath("$.status").value("RETIRED"));

        // A client still holding version 1 is told to reload; new business needs the new field.
        mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        policy(customer, "{\"employerReference\":\"EMP-2\"}", formV1)))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        policy(customer, "{\"employerReference\":\"EMP-2\"}", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("payrollNumber")));

        // The old policy is still edited against its own form: no payroll number needed.
        String view = mockMvc.perform(get("/portfolio/policies/" + oldPolicy + "/attributes")
                        .with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.currentForm").value(false))
                .andReturn().getResponse().getContentAsString();
        int rowVersion = JsonPath.read(view, "$.rowVersion");
        mockMvc.perform(json(put("/portfolio/policies/" + oldPolicy + "/attributes"), "admin",
                        "{\"expectedVersion\":" + rowVersion + ",\"customAttributes\":{\"employerReference\":\"EMP-9\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.employerReference").value("EMP-9"))
                .andExpect(jsonPath("$.form.versionNo").value(1));
        // Writing from the copy read before that change is refused, not merged.
        mockMvc.perform(json(put("/portfolio/policies/" + oldPolicy + "/attributes"), "admin",
                        "{\"expectedVersion\":" + rowVersion + ",\"customAttributes\":{\"employerReference\":\"EMP-0\"}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void definitionsAreValidatedAndReviewIsRecorded() throws Exception {
        mockMvc.perform(json(post("/admin/config-releases"), "configurator",
                        "{\"code\":\"BAD-1\",\"description\":\"x\",\"forms\":[{\"product\":\"Term Life\",\"fields\":"
                                + "[{\"key\":\"premium\",\"type\":\"NUMBER\",\"label\":\"Premium\"}]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("core policy field")));
        mockMvc.perform(json(post("/admin/config-releases"), "configurator",
                        "{\"code\":\"BAD-2\",\"description\":\"x\",\"forms\":[{\"product\":\"Space Cover\",\"fields\":["
                                + EMPLOYER + "]}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/admin/config-releases"), "configurator",
                        "{\"code\":\"BAD-3\",\"description\":\"x\",\"forms\":[{\"product\":\"Care Cash\",\"fields\":"
                                + "[{\"key\":\"tierChoice\",\"type\":\"ENUM\",\"label\":\"Choice\",\"allowedValues\":[\"A\"]}]}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/admin/config-releases"), "actuary", "{\"code\":\"NOPE\",\"description\":\"x\"}"))
                .andExpect(status().isForbidden());

        long[] draft = release("FF-R1", "[{\"key\":\"burialSociety\",\"type\":\"ENUM\",\"label\":\"Burial society\","
                + "\"allowedValues\":[\"Yes\",\"No\"]}]", "Family Funeral");
        long id = draft[0];
        long v = act(id, "submit", "configurator", draft[1], 200);
        mockMvc.perform(json(post("/admin/config-releases/" + id + "/reject"), "actuary", "{\"expectedVersion\":" + v + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/admin/config-releases/" + id + "/reject"), "actuary",
                        "{\"expectedVersion\":" + v + ",\"reason\":\"Wording not approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Wording not approved"))
                .andExpect(jsonPath("$.checksum").isNotEmpty());
        // A rejected release is history; it cannot be activated.
        mockMvc.perform(json(post("/admin/config-releases/" + id + "/activate"), "ops", "{\"expectedVersion\":" + (v + 1) + "}"))
                .andExpect(status().isConflict());
    }

    private long[] release(String code, String fields) throws Exception {
        return release(code, fields, "Term Life");
    }

    /** Drafts a one-product release and returns {id, rowVersion}. */
    private long[] release(String code, String fields, String product) throws Exception {
        String body = mockMvc.perform(json(post("/admin/config-releases"), "configurator",
                        "{\"code\":\"" + code + "\",\"description\":\"" + code + " policy fields\",\"forms\":[{\"product\":\""
                                + product + "\",\"fields\":" + fields + "}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        return new long[] {((Number) JsonPath.read(body, "$.id")).longValue(),
                ((Number) JsonPath.read(body, "$.rowVersion")).longValue()};
    }

    /** Runs an action from the given version and returns the release's new version. */
    private long act(long id, String action, String user, long version, int expected) throws Exception {
        String body = mockMvc.perform(json(post("/admin/config-releases/" + id + "/" + action), user,
                        "{\"expectedVersion\":" + version + "}"))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.rowVersion")).longValue();
    }

    private String customer(String name, String username) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 003\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\",\"pep\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(body, "$.id") + "/kyc")
                        .with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        return name;
    }

    private static String policy(String customer, String attributes, Integer formVersionId) {
        return "{\"name\":\"" + customer + "\",\"product\":\"Term Life\",\"amount\":50000,\"premium\":50,"
                + "\"customAttributes\":" + attributes
                + (formVersionId == null ? "" : ",\"formVersionId\":" + formVersionId) + "}";
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(httpBasic(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

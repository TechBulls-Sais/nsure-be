package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Party identity (NS-SH01-001/002): identity numbers checked by document type, a known identity number referred or
 * refused instead of silently creating a second party, records linked to customers by reference, and the party
 * events (PartyCreated, KycStatusChanged, PartyMerged).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-party-identity;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PartyIdentityApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void identityNumbersAreCheckedAgainstTheirDocumentType() throws Exception {
        create("Tumelo Format", "Omang", "QA96334401", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("An Omang number is 9 digits."));
        create("Tumelo Format", "Omang", "12345678", null).andExpect(status().isUnprocessableEntity());
        create("Tumelo Format", "Passport", "AB-1", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("A passport number is 6 to 20 letters and digits."));
        create("Tumelo Format", null, "912000111", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("Choose the proof of identity type")));
        // Spaces are ignored and passport letters are kept in capitals.
        create("Tumelo Format", "Omang", "912 000 111", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poiNumber").value("912000111"));
        create("Tumelo Passport", "Passport", "bw 7001234", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poiNumber").value("BW7001234"));
    }

    @Test
    void aKnownIdentityNumberIsReferredForReviewNotSilentlyCreated() throws Exception {
        String first = JsonPath.read(create("Thabo Duplicate", "Omang", "913334401", null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(events("PartyCreated", first)).isEqualTo(1);

        // A different person typed with the same Omang: refused, naming the record that holds it.
        long before = count();
        create("QA-A Different Person", "Omang", "913334401", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString(first + " (Thabo Duplicate) already holds Omang"
                        + " 913334401")));
        create("QA-A Different Person", "Omang", "913 334 401", "short")
                .andExpect(status().isUnprocessableEntity());
        assertThat(count()).isEqualTo(before);

        // Kept with a reason: created, but referred for duplicate review with the match and the reason.
        String second = JsonPath.read(create("QA-A Different Person", "Omang", "913334401",
                        "Omang card checked at the branch: the number was mistyped on the first record")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateReview").value(containsString("Same identity number as " + first)))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/platform/parties/duplicates").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.first.ref == '" + first + "' && @.second.ref == '" + second
                        + "')].confidence").value(hasItem("LIKELY")))
                .andExpect(jsonPath("$[?(@.second.ref == '" + second + "')].reasons[*]")
                        .value(hasItem(containsString(second + " referred when created"))));
        assertThat(jdbc.queryForObject("select count(*) from audit_evidence where entity_ref = ? and action = ?",
                Long.class, second, "Possible duplicate referred")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload from int_outbox_event where event_type = 'PartyCreated'"
                + " and aggregate_ref = ?", String.class, second))
                .contains("\"duplicateReview\":true").doesNotContain("913334401");
    }

    @Test
    void signUpWithARegisteredIdentityNumberIsRefusedBeforeAnyCodeIsSent() throws Exception {
        // CUS-1001 holds Omang 880618404.
        mockMvc.perform(post("/public/signup/otp").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Amara Again","email":"amara.again@example.com","phone":"+267 71 555 909",
                         "poiType":"Omang","poiNumber":"880618404","dateOfBirth":"1992-06-15",
                         "username":"amara.again","password":"password123","pep":false}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PartyRegister.SIGNUP_DUPLICATE))
                .andExpect(jsonPath("$.challenge").doesNotExist());
        mockMvc.perform(post("/public/signup/otp").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Amara Again","email":"amara.again@example.com","phone":"+267 71 555 909",
                         "poiType":"Omang","poiNumber":"QA96334401","dateOfBirth":"1992-06-15",
                         "username":"amara.again","password":"password123","pep":false}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("An Omang number is 9 digits."));
    }

    @Test
    void recordsLinkToTheCustomerReferenceNotTheName() throws Exception {
        // Two customers share a name; the policy belongs to the first one only (AT-SH11-01).
        String owner = verified("Kagiso Samename", "914000001");
        String other = verified("Kagiso Samename", "914000002");
        String policy = JsonPath.read(mockMvc.perform(post("/portfolio/policies").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerRef\":\"" + owner + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerRef").value(owner))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/portfolio/policies").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + policy + "')].customerRef").value(hasItem(owner)));
        mockMvc.perform(get("/portfolio/customers").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + owner + "')].tiers[*].policy").value(hasItem(policy)))
                .andExpect(jsonPath("$[?(@.id == '" + other + "')].tiers[*]").isEmpty());
        mockMvc.perform(get("/portfolio/applications").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == 'APP-2048')].customerRef").value(hasItem("CUS-1001")));
        assertThat(events("KycStatusChanged", owner)).isEqualTo(1);
    }

    @Test
    void aMergePublishesPartyMergedAndMovesRecordsUnderTheirVersions() throws Exception {
        String keep = verified("Onalenna Merge", "915000001");
        String gone = verified("Onalenna Merge", "915000002");
        String policy = JsonPath.read(mockMvc.perform(post("/portfolio/policies").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerRef\":\"" + gone + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        long version = jdbc.queryForObject("select row_version from ins_policy where record_ref = ?", Long.class, policy);
        // The merge is a request; a data steward who is someone else approves it (NS-SH01-002).
        String request = JsonPath.read(mockMvc.perform(post("/platform/parties/merges").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"survivingRef\":\"" + keep + "\",\"mergedRef\":\"" + gone
                                + "\",\"reason\":\"Same person captured twice at the branch\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/portfolio/parties/merge-requests/" + request + "/approve")
                        .with(signedIn("secadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movedDetail").value(org.hamcrest.Matchers.startsWith("1 applications")));
        assertThat(jdbc.queryForObject("select customer_ref from ins_policy where record_ref = ?", String.class, policy))
                .isEqualTo(keep);
        assertThat(jdbc.queryForObject("select row_version from ins_policy where record_ref = ?", Long.class, policy))
                .isGreaterThan(version);
        assertThat(events("PartyMerged", keep)).isEqualTo(1);
    }

    @Test
    void partiesAreSearchedOnTheServer() throws Exception {
        String ref = JsonPath.read(create("Searchable Masego", "Omang", "918 123 456", null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
        for (String term : new String[] {"searchable", ref.toLowerCase(), "918123456", "918 123", "party@example"}) {
            mockMvc.perform(get("/portfolio/customers").param("search", term).with(signedIn("onboarding", "password")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].id").value(hasItem(ref)));
        }
        mockMvc.perform(get("/portfolio/customers").param("search", "Amara").with(signedIn("onboarding", "password")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("CUS-1001"));
        mockMvc.perform(get("/portfolio/customers").param("search", "71 234 501").with(signedIn("onboarding", "password")))
                .andExpect(jsonPath("$[*].id").value(hasItem("CUS-1001")));
    }

    private String verified(String name, String omang) throws Exception {
        String ref = JsonPath.read(create(name, "Omang", omang, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }

    private ResultActions create(String name, String poiType, String poiNumber, String duplicateReason) throws Exception {
        StringBuilder body = new StringBuilder("{\"name\":\"" + name + "\",\"email\":\"party@example.com\","
                + "\"phone\":\"+267 71 222 333\",\"dateOfBirth\":\"1990-01-01\"");
        if (poiType != null) {
            body.append(",\"poiType\":\"").append(poiType).append('"');
        }
        if (poiNumber != null) {
            body.append(",\"poiNumber\":\"").append(poiNumber).append('"');
        }
        if (duplicateReason != null) {
            body.append(",\"duplicateReason\":\"").append(duplicateReason).append('"');
        }
        return mockMvc.perform(post("/portfolio/customers").with(signedIn("onboarding", "password"))
                .contentType(MediaType.APPLICATION_JSON).content(body.append('}').toString()));
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from ins_customer", Long.class);
    }

    private long events(String type, String aggregate) {
        return jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and aggregate_ref = ?",
                Long.class, type, aggregate);
    }
}

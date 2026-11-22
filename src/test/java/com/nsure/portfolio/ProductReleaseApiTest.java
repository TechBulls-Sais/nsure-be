package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SH02 release (NS-SH02-001/002/003/005/006, AT-SH02-01, AT-SH02-03, NS-SH03-006): a product configuration passes its
 * release checklist (refund, termination, accounting, golden cases), is approved by a second person with a frozen
 * manifest, and on activation is what quoting sells — within its configured ages — until it is withdrawn. Packages
 * export signed and an import with a missing dependency is refused before anything is created.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-product-release;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ProductReleaseApiTest {

    private static final String RULES = """
            {"ageBasis":"LAST_BIRTHDAY","cessationAge":70,"lapseAfterMissedPremiums":2,"coolingOffDays":30,
             "refundOnCancellation":"FULL_WITHIN_COOLING_OFF","maxBeneficiaries":3}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void aConfigurationIsReleasedOnlyThroughItsChecklistAndIsThenWhatQuotingSells() throws Exception {
        String ref = create("Release Life", "RL-01", "", 300);
        // AT-SH02-01: no refund or termination rules, no golden case: the checklist fails and nobody can approve it.
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/submit").with(signedIn("configurator", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("In review"));
        mockMvc.perform(get("/portfolio/product-configs/" + ref + "/checklist").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.items[?(@.key == 'refund')].ok").value(false))
                .andExpect(jsonPath("$.items[?(@.key == 'termination')].ok").value(false))
                .andExpect(jsonPath("$.items[?(@.key == 'golden')].ok").value(false))
                .andExpect(jsonPath("$.items[?(@.key == 'accounting')].ok").value(true));
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Refund and cancellation rules")));

        // NS-SH02-005: a rule outside its schema or a prohibited combination is refused.
        mockMvc.perform(json(put("/portfolio/product-configs/" + ref), "configurator",
                        body("Release Life", "RL-01", "{\"cessationAge\":140}", 300, "In review")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("between 50 and 100")));
        mockMvc.perform(json(put("/portfolio/product-configs/" + ref), "configurator",
                        body("Release Life", "RL-01", "{\"script\":\"rm -rf\"}", 300, "In review")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("not a product rule")));

        // The maker completes the rules and proposes a golden case; an actuary approves it.
        mockMvc.perform(json(put("/portfolio/product-configs/" + ref), "configurator",
                        body("Release Life", "RL-01", RULES, 300, "In review")))
                .andExpect(status().isOk());
        long golden = golden(ref, "configurator", LocalDate.now().minusYears(30), "300.00");
        long refused = golden(ref, "configurator", LocalDate.now().minusYears(70), null);
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/golden-cases/" + golden + "/approve")
                        .with(signedIn("configurator", "password")))
                .andExpect(status().isForbidden());
        for (long id : new long[] {golden, refused}) {
            mockMvc.perform(post("/portfolio/product-configs/" + ref + "/golden-cases/" + id + "/approve")
                            .with(signedIn("actuary", "password")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        }

        // AT-SH02-01: a missing accounting mapping fails the checklist too.
        jdbc.update("update fin_posting_rule set status = 'INACTIVE' where event_type = 'PremiumRefunded'");
        mockMvc.perform(get("/portfolio/product-configs/" + ref + "/checklist").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.items[?(@.key == 'accounting')].ok").value(false))
                .andExpect(jsonPath("$.items[?(@.key == 'accounting')].detail")
                        .value(Matchers.hasItem(Matchers.containsString("PremiumRefunded"))));
        jdbc.update("update fin_posting_rule set status = 'ACTIVE' where event_type = 'PremiumRefunded'");

        // NS-SH02-003: the maker cannot approve; a second person does, freezing the manifest (NS-SH02-002).
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/approve").with(signedIn("configurator", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/product-configs/" + ref + "/approve"), "admin", "{\"note\":\"Checked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Approved"))
                .andExpect(jsonPath("$.approvedBy").value("admin"))
                .andExpect(jsonPath("$.manifestChecksum").value(Matchers.matchesPattern("[0-9a-f]{64}")));
        mockMvc.perform(get("/portfolio/product-configs/" + ref + "/checklist").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.manifestFrozen").value(true))
                .andExpect(jsonPath("$.manifest.rates.basis").value("FLAT"))
                .andExpect(jsonPath("$.manifest.accounting.length()").value(3))
                .andExpect(jsonPath("$.manifest.wording.template").isNotEmpty());
        assertThat(events("ProductVersionApproved", ref)).isEqualTo(1);

        // NS-SH02-001: activation publishes the plan; quoting sells exactly the configured cover and premium.
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/activate").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"))
                .andExpect(jsonPath("$.published").value(true));
        assertThat(events("ProductActivated", ref)).isEqualTo(1);
        mockMvc.perform(get("/portfolio/products").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.name == 'Release Life')].tiers[0].name").value("Standard"))
                .andExpect(jsonPath("$[?(@.name == 'Release Life')].tiers[0].premium").value(300));
        String young = customer("Thato Release", LocalDate.now().minusYears(30));
        String old = customer("Kgosi Release", LocalDate.now().minusYears(70));
        mockMvc.perform(json(post("/portfolio/quote"), "admin",
                        "{\"customerRef\":\"" + young + "\",\"product\":\"Release Life\",\"tier\":\"Standard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium").value(300))
                .andExpect(jsonPath("$.cover").value(500000));
        mockMvc.perform(json(post("/portfolio/quote"), "admin",
                        "{\"customerRef\":\"" + old + "\",\"product\":\"Release Life\",\"tier\":\"Standard\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("accepts lives aged 18–65")));
        // The contract terms version for new business comes from the configuration (3 months ≈ 90 days demo rule).
        mockMvc.perform(get("/portfolio/products/{p}/versions", "Release Life").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].waitingPeriodDays").value(90))
                .andExpect(jsonPath("$[0].maxBeneficiaries").value(3));

        // NS-SH02-006: withdrawal stops new sales.
        mockMvc.perform(json(post("/portfolio/product-configs/" + ref + "/withdraw"), "admin",
                        "{\"reason\":\"Replaced by a new plan\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Withdrawn"));
        mockMvc.perform(json(post("/portfolio/quote"), "admin",
                        "{\"customerRef\":\"" + young + "\",\"product\":\"Release Life\",\"tier\":\"Standard\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("not available")));
        assertThat(events("ProductWithdrawn", ref)).isEqualTo(1);

        // Rollback restores an earlier version's content as a draft that goes through review again.
        mockMvc.perform(json(post("/portfolio/product-configs/" + ref + "/rollback"), "configurator", "{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Draft"))
                .andExpect(jsonPath("$.rules.cessationAge").doesNotExist());
    }

    @Test
    void aChangedResultNeedsAnApprovedBaselineRevision() throws Exception {
        String ref = create("Baseline Life", "BL-01", RULES, 250);
        long id = golden(ref, "configurator", LocalDate.now().minusYears(40), "250.00");
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/golden-cases/" + id + "/approve")
                .with(signedIn("actuary", "password"))).andExpect(status().isOk());
        mockMvc.perform(get("/portfolio/product-configs/" + ref + "/checklist").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.items[?(@.key == 'golden')].ok").value(true));
        // The premium changes: the validated result no longer holds, so the release is blocked.
        mockMvc.perform(json(put("/portfolio/product-configs/" + ref), "configurator",
                        body("Baseline Life", "BL-01", RULES, 275, "Draft")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/golden-cases/run").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].result").value("FAIL"))
                .andExpect(jsonPath("$[0].actual").value("275.00"));
        mockMvc.perform(get("/portfolio/product-configs/" + ref + "/checklist").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.items[?(@.key == 'golden')].ok").value(false));
        // A baseline revision needs a reason and an actuary's approval; the old baseline is superseded.
        String revised = mockMvc.perform(json(post("/portfolio/product-configs/" + ref + "/golden-cases"), "configurator",
                        "{\"label\":\"Age 40\",\"dateOfBirth\":\"" + LocalDate.now().minusYears(40)
                                + "\",\"expectedPremium\":\"275.00\",\"supersedes\":" + id
                                + ",\"reason\":\"Repriced after the 2027 expense review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseline").value(2))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/golden-cases/"
                        + ((Number) JsonPath.read(revised, "$.id")).longValue() + "/approve")
                        .with(signedIn("actuary", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/portfolio/product-configs/" + ref + "/checklist").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.items[?(@.key == 'golden')].ok").value(true))
                .andExpect(jsonPath("$.goldenCases[0].status").value("SUPERSEDED"));
    }

    @Test
    void aSignedPackageImportsOnlyWhenEveryDependencyExistsHere() throws Exception {
        String ref = create("Package Life", "PK-01", RULES, 210);
        long id = golden(ref, "configurator", LocalDate.now().minusYears(35), "210.00");
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/golden-cases/" + id + "/approve")
                .with(signedIn("actuary", "password"))).andExpect(status().isOk());
        String exported = mockMvc.perform(get("/portfolio/product-configs/" + ref + "/package")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksum").value(Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.dependencies[?(@.type == 'POSTING_RULE')].ref").value(Matchers.hasItem("ClaimPaid")))
                .andReturn().getResponse().getContentAsString();

        // Unchanged, it is refused only because the code already exists here.
        mockMvc.perform(json(post("/portfolio/product-configs/import"), "configurator", exported))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("PK-01 already exists")));
        // Changed after export without re-signing: refused.
        ObjectNode tampered = (ObjectNode) mapper.readTree(exported);
        ((ObjectNode) tampered.get("configuration")).put("premium", 1);
        mockMvc.perform(json(post("/portfolio/product-configs/import"), "configurator", tampered.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("checksum does not match")));
        // AT-SH02-03: signed, but naming a rate table this installation does not have: refused, nothing created.
        ObjectNode missing = (ObjectNode) mapper.readTree(exported);
        ((ObjectNode) missing.get("configuration")).put("code", "PK-02");
        ((com.fasterxml.jackson.databind.node.ArrayNode) missing.get("dependencies")).addObject()
                .put("type", "RATE_TABLE").put("ref", "RT-99999");
        resign(missing);
        long before = jdbc.queryForObject("select count(*) from ins_product_config", Long.class);
        mockMvc.perform(json(post("/portfolio/product-configs/import"), "configurator", missing.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Rate table RT-99999 does not exist")));
        assertThat(jdbc.queryForObject("select count(*) from ins_product_config", Long.class)).isEqualTo(before);
        // Signed with every dependency present: a new draft, golden cases proposed for this installation's actuary.
        ObjectNode good = (ObjectNode) mapper.readTree(exported);
        ((ObjectNode) good.get("configuration")).put("code", "PK-03");
        resign(good);
        String imported = mockMvc.perform(json(post("/portfolio/product-configs/import"), "configurator", good.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Draft"))
                .andExpect(jsonPath("$.code").value("PK-03"))
                .andExpect(jsonPath("$.importedFrom").value(Matchers.containsString(ref)))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/portfolio/product-configs/" + JsonPath.read(imported, "$.id") + "/checklist")
                        .with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.goldenCases[0].status").value("PROPOSED"));
    }

    @Test
    void aRateChangeThatAltersValidatedGoldenResultsIsNotPublishedWithoutABaselineRevision() throws Exception {
        // A configuration of Family Funeral's Bronze plan priced from rate table RT-00001.
        String created = mockMvc.perform(json(post("/portfolio/product-configs"), "configurator",
                        "{\"name\":\"Rated Funeral\",\"code\":\"RF-01\",\"status\":\"Draft\",\"premium\":105,\"cover\":25000,"
                                + "\"benefits\":[\"Funeral benefit\"],\"channels\":[\"Customer portal\"],\"catalogueProduct\":"
                                + "\"Family Funeral\",\"plan\":\"Bronze\",\"rateTableRef\":\"RT-00001\",\"rules\":" + RULES + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(created, "$.id");
        LocalDate born = LocalDate.now().minusYears(32).minusDays(10);
        long id = golden(ref, "configurator", born, "17.50");
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/golden-cases/" + id + "/approve")
                .with(signedIn("actuary", "password"))).andExpect(status().isOk());
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/golden-cases/run").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].result").value("PASS"));
        // A 10% re-rate of the main insured is drafted and submitted; publishing it would change the validated result.
        mockMvc.perform(post("/admin/rate-tables/RT-00001/scale").with(signedIn("configurator", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"factor\":1.10,\"lifeType\":\"Main Insured\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/rate-tables/RT-00001/versions/2/submit").with(signedIn("configurator", "password")))
                .andExpect(status().isOk());
        String approve = "{\"overrideReason\":\"Board-approved re-rate\"}";
        mockMvc.perform(post("/admin/rate-tables/RT-00001/versions/2/approve").with(signedIn("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("changes validated golden results")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("gives 19.25")));
        // The actuary approves a revised baseline proposed with a reason; then the re-rate publishes.
        String revised = mockMvc.perform(json(post("/portfolio/product-configs/" + ref + "/golden-cases"), "configurator",
                        "{\"label\":\"Age 32\",\"dateOfBirth\":\"" + born + "\",\"expectedPremium\":\"19.25\",\"supersedes\":"
                                + id + ",\"reason\":\"Board-approved 10% re-rate of main insured lives\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/product-configs/" + ref + "/golden-cases/"
                        + ((Number) JsonPath.read(revised, "$.id")).longValue() + "/approve").with(signedIn("actuary", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/rate-tables/RT-00001/versions/2/approve").with(signedIn("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isOk());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String create(String name, String code, String rules, int premium) throws Exception {
        String created = mockMvc.perform(json(post("/portfolio/product-configs"), "configurator",
                        body(name, code, rules.isBlank() ? "{}" : rules, premium, "Draft")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    private static String body(String name, String code, String rules, int premium, String status) {
        return "{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"status\":\"" + status + "\",\"premium\":" + premium
                + ",\"cover\":500000,\"minAge\":18,\"maxAge\":65,\"waiting\":3,\"grace\":30,\"benefits\":[\"Death benefit\"],"
                + "\"channels\":[\"Customer portal\"],\"rules\":" + rules + "}";
    }

    private long golden(String ref, String user, LocalDate dob, String expected) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/product-configs/" + ref + "/golden-cases"), user,
                        "{\"label\":\"Born " + dob + "\",\"dateOfBirth\":\"" + dob + "\",\"expectedPremium\":"
                                + (expected == null ? "null" : "\"" + expected + "\"") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String customer(String name, LocalDate dob) throws Exception {
        String username = name.toLowerCase().replace(" ", "");
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 002\","
                                + "\"dateOfBirth\":\"" + dob + "\",\"username\":\"" + username
                                + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Signs a changed package as an exporting installation with the demo key would. */
    private void resign(ObjectNode pkg) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("format", pkg.get("format").asText());
        body.set("configuration", pkg.get("configuration"));
        body.set("dependencies", pkg.get("dependencies"));
        body.set("goldenCases", pkg.get("goldenCases"));
        String checksum = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("DEMO-ONLY-product-package-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        pkg.put("checksum", checksum);
        pkg.put("signature", HexFormat.of().formatHex(mac.doFinal(checksum.getBytes(StandardCharsets.UTF_8))));
    }

    private long events(String type, String aggregate) {
        return jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and aggregate_ref = ?",
                Long.class, type, aggregate);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

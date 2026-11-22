package com.nsure.integration;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** §11 batch contract / NS-SH12-002: the manifest is validated before staging, and an acknowledgement is produced. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-batch-gateway;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class BatchGatewayApiTest {

    private static final String HEADER = "memberKey,firstName,lastName,dateOfBirth,category,salary,action,effectiveDate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aManifestThatDoesNotMatchItsFileIsRejectedBeforeStagingAndAGoodOneIsAcknowledged() throws Exception {
        Map<String, Object> target = jdbc.queryForList("select s.record_ref ref, c.category_code code from ins_scheme s"
                + " join grp_benefit_category c on c.scheme_id = s.id where s.scheme_status = 'ACTIVE' order by s.id").get(0);
        String scheme = (String) target.get("REF");
        String category = (String) target.get("CODE");
        String csv = HEADER + "\nGW1,Ana,Moeti,1990-01-01," + category + ",12000,JOIN,2026-01-01"
                + "\nGW2,Ben,Moeti,1991-02-02," + category + ",8000.50,JOIN,2026-01-01"
                + "\nGW3,Bad,Row,not-a-date," + category + ",5000,JOIN,2026-01-01";
        long staged = jdbc.queryForObject("select count(*) from grp_census_batch", Long.class);

        // Declares 2 records and a wrong total: rejected whole, nothing staged.
        mockMvc.perform(send(scheme, csv, manifest("HR-GW-1", 2, "20000.50", sha256(csv))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.problems").value(hasItem(containsString("declares 2 record(s) but the file holds 3"))))
                .andExpect(jsonPath("$.problems").value(hasItem(containsString("control total"))));
        mockMvc.perform(send(scheme, csv, manifest("HR-GW-1", 3, "25000.50", "0".repeat(64))))
                .andExpect(jsonPath("$.problems").value(hasItem(containsString("checksum"))));
        assertThat(jdbc.queryForObject("select count(*) from grp_census_batch", Long.class)).isEqualTo(staged);

        String ok = mockMvc.perform(send(scheme, csv, manifest("HR-GW-2", 3, "25000.50", sha256(csv))))
                .andExpect(jsonPath("$.status").value("STAGED"))
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.rejected").value(1))
                .andReturn().getResponse().getContentAsString();
        String receipt = JsonPath.read(ok, "$.receiptRef");
        mockMvc.perform(get("/integration/batch-receipts/" + receipt + "/acknowledgement")
                        .with(signedIn("grpadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowErrors[0].memberKey").value("GW3"))
                .andExpect(jsonPath("$.manifest.schemaVersion").value("census-1"));
        // A correction must reference an earlier batch of the same scheme.
        Map<String, Object> body = body(scheme, csv, manifest("HR-GW-3", 3, "25000.50", sha256(csv)));
        body.put("correctsRef", "BR-99999");
        mockMvc.perform(post("/integration/batches/census").with(signedIn("grpadmin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mockMvc.perform(get("/integration/batch-receipts").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$[*].receiptRef").value(hasItem(receipt)));
        mockMvc.perform(post("/integration/batches/census").with(signedIn("ops", "password"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> manifest(String external, int count, String total, String checksum) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", "census-1");
        m.put("source", "Employer HR system");
        m.put("externalBatchId", external);
        m.put("createdOn", "2026-11-20");
        m.put("asOf", "2026-11-01");
        m.put("recordCount", count);
        m.put("currency", "BWP");
        m.put("controlTotal", total);
        m.put("checksum", checksum);
        return m;
    }

    private Map<String, Object> body(String scheme, String csv, Map<String, Object> manifest) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("schemeRef", scheme);
        b.put("mode", "PARTIAL");
        b.put("csv", csv);
        b.put("manifest", manifest);
        return b;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder send(String scheme, String csv,
            Map<String, Object> manifest) throws Exception {
        return post("/integration/batches/census").with(signedIn("grpadmin", "password"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body(scheme, csv, manifest)));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

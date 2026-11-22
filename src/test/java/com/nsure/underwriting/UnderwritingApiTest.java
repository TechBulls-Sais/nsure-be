package com.nsure.underwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** IL02 Individual underwriting (NS-IL02-001..006, AT-IL02-01..03, NS-IL01-005). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-underwriting;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class UnderwritingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aCaseInsideEveryLimitIsAcceptedByTheRulesAndCanBeIssued() throws Exception {
        String customer = customer("Kagiso Clean", "+267 71 800 001");
        String app = apply(customer, "Family Funeral", "Bronze", "NO", "NO", "Ready to issue");

        mockMvc.perform(get("/underwriting/applications/" + app).with(signedIn("underwriter", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underwritingCase.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.underwritingCase.decision").value("ACCEPT_STANDARD"))
                .andExpect(jsonPath("$.underwritingCase.decidedBy").value("rules"))
                .andExpect(jsonPath("$.underwritingCase.ruleSetVersion").value("UW-2026.1"))
                .andExpect(jsonPath("$.decisions.length()").value(1));
        assertThat(events("UnderwritingDecided", app)).isEqualTo(1);

        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue(app, customer, "Family Funeral", "Bronze")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Pending Payment"));
    }

    @Test
    void aReferredCaseNeedsAnUnderwriterAndALoadedOfferNeedsAcceptanceBeforeIssue() throws Exception {
        String customer = customer("Mpho Smoker", "+267 71 800 002");
        String app = apply(customer, "Term Life", "Bronze", "YES", "NO", "Under review");
        String issue = issue(app, customer, "Term Life", "Bronze");
        String ref = JsonPath.read(mockMvc.perform(get("/underwriting/applications/" + app)
                        .with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.underwritingCase.status").value("REFERRED"))
                .andExpect(jsonPath("$.underwritingCase.referralReasons[0]").value(containsString("Tobacco")))
                .andReturn().getResponse().getContentAsString(), "$.underwritingCase.ref");

        // NS-IL02-006: neither issue nor a hand-set status gets past an open case.
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("awaiting a decision")));
        mockMvc.perform(json(put("/portfolio/applications/" + app), "admin", "{\"status\":\"Ready to issue\"}"))
                .andExpect(status().isUnprocessableEntity());

        // NS-IL02-004: the person who submitted the application does not decide it.
        String loaded = "{\"decision\":\"ACCEPT_WITH_TERMS\",\"loadingPercent\":25,\"reason\":\"Smoker rates apply for this life.\"}";
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "admin", loaded))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter", loaded))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underwritingCase.status").value("OFFERED"))
                .andExpect(jsonPath("$.underwritingCase.offerVersion").value(1))
                .andExpect(jsonPath("$.underwritingCase.offerPremium").value(287.5));
        assertThat(events("OfferRevised", ref)).isEqualTo(1);
        mockMvc.perform(get("/portfolio/applications").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + app + "')].status").value("Offered"));
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("has not been accepted")));

        // AT-IL02-02: accepting v1 does not authorise changed terms; v2 needs its own acceptance.
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/offer"), "admin",
                        "{\"accept\":true,\"note\":\"Accepted by phone, call recorded\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underwritingCase.offerStatus").value("ACCEPTED"));
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter",
                        "{\"decision\":\"ACCEPT_WITH_TERMS\",\"loadingPercent\":50,\"exclusion\":\"Respiratory conditions\","
                                + "\"reason\":\"Revised after the doctor's report was reviewed.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underwritingCase.offerVersion").value(2))
                .andExpect(jsonPath("$.underwritingCase.offerStatus").value("PENDING"))
                .andExpect(jsonPath("$.underwritingCase.offerPremium").value(345.0))
                .andExpect(jsonPath("$.decisions.length()").value(2))
                // IL02: each decision names the life, the benefit and the evidence it rested on.
                .andExpect(jsonPath("$.decisions[1].lifeRef").value(customer))
                .andExpect(jsonPath("$.decisions[1].benefit").value("Term Life · Bronze"))
                .andExpect(jsonPath("$.decisions[1].sumAssured").value(250000.0))
                .andExpect(jsonPath("$.decisions[1].evidenceBasis").isNotEmpty());
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/offer"), "admin",
                        "{\"accept\":true,\"note\":\"Signed revised offer received\"}"))
                .andExpect(status().isOk());

        // IL01: each accepted underwriting offer raises OfferAccepted (alongside OfferAnswered).
        assertThat(events("OfferAccepted", ref)).isEqualTo(2);
        // A-19: each accepted offer is a new version of the price trace, and the application shows that price.
        String application = mockMvc.perform(get("/portfolio/applications").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        String quote = JsonPath.<java.util.List<String>>read(application, "$[?(@.id == '" + app + "')].quoteRef").get(0);
        assertThat(JsonPath.<java.util.List<Object>>read(application, "$[?(@.id == '" + app + "')].premium").get(0)
                .toString()).isEqualTo("345.0");
        // v1 priced 230; v2 the accepted offer v1 (287.50); v3 back to 230 when the terms changed; v4 offer v2.
        mockMvc.perform(get("/portfolio/quotes/" + quote).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].version").value(4))
                .andExpect(jsonPath("$[0].premium").value(345.0))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[0].trace.underwriting.caseRef").value(ref))
                .andExpect(jsonPath("$[0].trace.underwriting.loadingPercent").value("50"))
                .andExpect(jsonPath("$[0].differences").value(containsString("underwriting offer v2")))
                .andExpect(jsonPath("$[1].premium").value(230.0))
                .andExpect(jsonPath("$[2].premium").value(287.5))
                .andExpect(jsonPath("$[3].premium").value(230.0));
        mockMvc.perform(get("/portfolio/quotes/" + quote + "/replay").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.reproduced").value(true));

        // NS-IL02-005: the accepted offer's loaded premium and exclusion are what the contract carries.
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", issue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium").value(345))
                .andExpect(jsonPath("$.quoteVersion").value(4))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(jdbc.queryForObject("select uw_terms from ins_policy where record_ref = ?", String.class, policy))
                .contains("50%").contains("Respiratory conditions").contains("Offer v2");
        // The application's timeline shows each underwriting step that moved it, without clinical reasons.
        String timeline = mockMvc.perform(get("/portfolio/history/application/" + app).with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(timeline, "$[*].action")).containsSubsequence(
                "Submitted", "Underwriting decision", "Offer accepted", "Underwriting decision", "Offer accepted", "Issued");
        assertThat(timeline).doesNotContain("Smoker rates").doesNotContain("doctor's report");
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter",
                        "{\"decision\":\"DECLINE\",\"reason\":\"Trying to change an issued contract.\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnderwriterCannotDecideAboveTheirAuthority() throws Exception {
        String customer = customer("Tumelo Large", "+267 71 800 003");
        String app = apply(customer, "Term Life", "Silver", "YES", "NO", "Under review");
        String ref = caseRef(app);
        Long role = jdbc.queryForObject("select id from app_role where name = 'Underwriter'", Long.class);
        mockMvc.perform(json(put("/underwriting/authorities/" + role), "underwriter", "{\"maxSumAssured\":100000}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(put("/underwriting/authorities/" + role), "admin", "{\"maxSumAssured\":100000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role == 'Underwriter')].maxSumAssured").value(100000.0));
        try {
            // AT-IL02-01: refused, and the case stays referred with no cover issued.
            mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter",
                            "{\"decision\":\"ACCEPT_STANDARD\",\"reason\":\"Acceptable risk on the evidence held.\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(containsString("above your underwriting authority")));
            mockMvc.perform(get("/underwriting/cases/" + ref).with(signedIn("underwriter", "password")))
                    .andExpect(jsonPath("$.underwritingCase.status").value("REFERRED"))
                    .andExpect(jsonPath("$.underwritingCase.withinMyAuthority").value(false));
        } finally {
            mockMvc.perform(json(put("/underwriting/authorities/" + role), "admin", "{\"maxSumAssured\":1000000}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter",
                        "{\"decision\":\"POSTPONE\",\"postponeUntil\":\"" + java.time.LocalDate.now().plusMonths(6)
                                + "\",\"reason\":\"Awaiting completion of current treatment.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underwritingCase.status").value("POSTPONED"));
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue(app, customer, "Term Life", "Silver")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("postponed")));
        // NS-IL02-006: reopening keeps the earlier decision on the record.
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/reopen"), "underwriter",
                        "{\"reason\":\"Treatment completed early; reassess now.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underwritingCase.status").value("REFERRED"))
                .andExpect(jsonPath("$.decisions.length()").value(1));
    }

    @Test
    void evidenceBlocksAcceptanceAndClinicalDetailIsOnlyForUnderwriters() throws Exception {
        String customer = customer("Lesedi Treatment", "+267 71 800 004");
        String app = apply(customer, "Term Life", "Bronze", "NO", "YES", "Under review");
        String ref = caseRef(app);
        String body = mockMvc.perform(get("/underwriting/cases/" + ref).with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.underwritingCase.status").value("EVIDENCE_PENDING"))
                .andExpect(jsonPath("$.evidence[0].requirement").value("Attending doctor's report"))
                .andExpect(jsonPath("$.evidence[0].restricted").value(true))
                .andReturn().getResponse().getContentAsString();
        Integer evidence = JsonPath.read(body, "$.evidence[0].id");
        assertThat(events("EvidenceRequested", ref)).isEqualTo(1);

        String accept = "{\"decision\":\"ACCEPT_STANDARD\",\"reason\":\"Condition resolved, standard terms.\"}";
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter", accept))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("evidence outstanding")));
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/evidence/" + evidence + "/resolve"), "underwriter",
                        "{\"status\":\"RECEIVED\",\"note\":\"Report dated last month: treatment complete, no recurrence.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underwritingCase.status").value("REFERRED"))
                .andExpect(jsonPath("$.evidence[0].note").value(containsString("no recurrence")));

        // AT-IL02-03: someone who is not an underwriter sees the request and its status, never the clinical note.
        mockMvc.perform(get("/underwriting/cases/" + ref).with(signedIn("support", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.evidence[0].note").doesNotExist());
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "support", accept))
                .andExpect(status().isForbidden());

        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter", accept))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underwritingCase.status").value("ACCEPTED"));
        mockMvc.perform(get("/portfolio/applications").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + app + "')].status").value("Ready to issue"));
    }

    @Test
    void changedRulesTakeANewVersionAndApplyToLaterCases() throws Exception {
        java.util.List<Integer> ids = JsonPath.read(mockMvc.perform(get("/underwriting/rules")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$[?(@.product == 'Care Cash')].id");
        Integer id = ids.get(0);
        mockMvc.perform(json(put("/underwriting/rules/" + id), "admin", "{\"versionLabel\":\"UW-2026.1\",\"maxEntryAge\":60}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(put("/underwriting/rules/" + id), "underwriter", "{\"versionLabel\":\"UW-2026.2\",\"maxEntryAge\":60}"))
                .andExpect(status().isForbidden());
        // NS-IL01-005: a change is only proposed; the rules stay as they were until a second person approves it.
        mockMvc.perform(json(put("/underwriting/rules/" + id), "admin", "{\"versionLabel\":\"UW-2026.2\",\"maxEntryAge\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxEntryAge").value(65))
                .andExpect(jsonPath("$.versionLabel").value("UW-2026.1"))
                .andExpect(jsonPath("$.pendingChange.versionLabel").value("UW-2026.2"))
                .andExpect(jsonPath("$.pendingChange.proposal.maxEntryAge").value(60));
        mockMvc.perform(json(put("/underwriting/rules/" + id), "admin", "{\"versionLabel\":\"UW-2026.3\",\"maxEntryAge\":61}"))
                .andExpect(status().isConflict());
        Integer change = JsonPath.read(mockMvc.perform(get("/underwriting/rule-changes").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        mockMvc.perform(json(post("/underwriting/rule-changes/" + change + "/decision"), "admin", "{\"approve\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/underwriting/rule-changes/" + change + "/decision"), "secadmin",
                        "{\"approve\":true,\"note\":\"Checked against the approved underwriting guide\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("secadmin"))
                .andExpect(jsonPath("$.previous.maxEntryAge").value(65));
        mockMvc.perform(get("/underwriting/rules").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].maxEntryAge").value(60))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].versionLabel").value("UW-2026.2"));
        mockMvc.perform(json(post("/underwriting/rule-changes/" + change + "/decision"), "secadmin", "{\"approve\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void authorityIsMeasuredOnAggregateExposureAndByDecisionType() throws Exception {
        String customer = customer("Neo Aggregate", "+267 71 800 011");
        // In-force cover first: a funeral policy accepted by the rules and issued (Pending Payment counts as exposure).
        String funeral = apply(customer, "Family Funeral", "Bronze", "NO", "NO", "Ready to issue");
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue(funeral, customer, "Family Funeral", "Bronze")))
                .andExpect(status().isOk());
        String app = apply(customer, "Term Life", "Bronze", "YES", "NO", "Under review");
        String ref = caseRef(app);
        // AT-IL02-01: the junior underwriter's BWP 250,000 limit covers this sum assured but not the total cover.
        mockMvc.perform(get("/underwriting/cases/" + ref).with(signedIn("underwriter2", "password")))
                .andExpect(jsonPath("$.underwritingCase.sumAssured").value(250000.0))
                .andExpect(jsonPath("$.underwritingCase.myAuthority").value(250000.0))
                .andExpect(jsonPath("$.underwritingCase.withinMyAuthority").value(false))
                .andExpect(jsonPath("$.underwritingCase.myDecisionTypes.length()").value(3));
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter2",
                        "{\"decision\":\"ACCEPT_STANDARD\",\"reason\":\"Acceptable risk on the evidence held.\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("aggregate exposure")));
        mockMvc.perform(get("/underwriting/cases/" + ref).with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.underwritingCase.status").value("REFERRED"))
                .andExpect(jsonPath("$.underwritingCase.decision").doesNotExist());
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue(app, customer, "Term Life", "Bronze")))
                .andExpect(status().isUnprocessableEntity());

        // NS-IL02-004: authority by decision type and loading, not only amount.
        Long junior = jdbc.queryForObject("select id from app_role where name = 'Junior Underwriter'", Long.class);
        mockMvc.perform(json(put("/underwriting/authorities/" + junior), "admin",
                        "{\"maxSumAssured\":500000,\"maxLoadingPercent\":50,\"decisionTypes\":[\"ACCEPT_STANDARD\",\"ACCEPT_WITH_TERMS\",\"POSTPONE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role == 'Junior Underwriter')].maxLoadingPercent").value(50.0));
        try {
            mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter2",
                            "{\"decision\":\"DECLINE\",\"reason\":\"Risk outside appetite for this product.\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(containsString("does not include decline")));
            mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter2",
                            "{\"decision\":\"ACCEPT_WITH_TERMS\",\"loadingPercent\":75,\"reason\":\"Smoker loading for this life.\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(containsString("above your authority of 50%")));
            // NS-IL02-005: and never above the rule set's approved ceiling, whoever decides.
            mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter",
                            "{\"decision\":\"ACCEPT_WITH_TERMS\",\"loadingPercent\":350,\"reason\":\"Smoker loading for this life.\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(containsString("between 0% and 300%")));
            mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter2",
                            "{\"decision\":\"ACCEPT_WITH_TERMS\",\"loadingPercent\":25,\"reason\":\"Smoker loading for this life.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.underwritingCase.status").value("OFFERED"))
                    .andExpect(jsonPath("$.underwritingCase.decisionValidUntil").value(java.time.LocalDate.now().plusDays(90).toString()));
        } finally {
            mockMvc.perform(json(put("/underwriting/authorities/" + junior), "admin",
                    "{\"maxSumAssured\":250000,\"maxLoadingPercent\":100,\"decisionTypes\":\"ACCEPT_STANDARD,ACCEPT_WITH_TERMS,POSTPONE\"}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void expiredDecisionsAndEndedPostponementsAreReopenedForReassessment() throws Exception {
        String customer = customer("Boitumelo Expiry", "+267 71 800 012");
        String app = apply(customer, "Term Life", "Bronze", "YES", "NO", "Under review");
        String ref = caseRef(app);
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter",
                        "{\"decision\":\"ACCEPT_STANDARD\",\"reason\":\"Occasional smoker, standard terms.\"}"))
                .andExpect(status().isOk());
        // NS-IL02-006: the decision stood for 90 days; once past, issue refuses it and the review reopens it.
        jdbc.update("update uw_case set decision_valid_until = ? where record_ref = ?",
                java.sql.Date.valueOf(java.time.LocalDate.now().minusDays(1)), ref);
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue(app, customer, "Term Life", "Bronze")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("expired")));
        mockMvc.perform(post("/underwriting/review-due").with(signedIn("support", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/underwriting/review-due").with(signedIn("underwriter", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reopened[0]").value(ref));
        mockMvc.perform(get("/underwriting/cases/" + ref).with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.underwritingCase.status").value("REFERRED"))
                .andExpect(jsonPath("$.underwritingCase.decision").doesNotExist())
                .andExpect(jsonPath("$.underwritingCase.referralReasons[-1:]").value(org.hamcrest.Matchers.hasItem(
                        containsString("Decision expired"))))
                .andExpect(jsonPath("$.decisions.length()").value(1));
        mockMvc.perform(get("/portfolio/applications").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + app + "')].status").value("Under review"));

        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter",
                        "{\"decision\":\"POSTPONE\",\"postponeUntil\":\"" + java.time.LocalDate.now().plusDays(30)
                                + "\",\"reason\":\"Awaiting completion of current treatment.\"}"))
                .andExpect(status().isOk());
        jdbc.update("update uw_case set postpone_until = ? where record_ref = ?",
                java.sql.Date.valueOf(java.time.LocalDate.now()), ref);
        mockMvc.perform(post("/underwriting/review-due").with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.reopened").value(org.hamcrest.Matchers.hasItem(ref)));
        mockMvc.perform(get("/underwriting/cases/" + ref).with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.underwritingCase.status").value("REFERRED"))
                .andExpect(jsonPath("$.decisions.length()").value(2));
    }

    @Test
    void aScreeningMatchBlocksAcceptanceAndIssueUntilComplianceClearsIt() throws Exception {
        // The simulated demo list holds this fictional name; nothing else on the application would refer it.
        String customer = customer("Viktor Demo Sanctioned", "+267 71 800 013");
        String app = apply(customer, "Family Funeral", "Bronze", "NO", "NO", "Under review");
        String ref = caseRef(app);
        mockMvc.perform(get("/underwriting/cases/" + ref).with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.underwritingCase.screeningStatus").value("HIT"))
                .andExpect(jsonPath("$.underwritingCase.referralReasons[0]").value(containsString("Demo sanctions list")));
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter",
                        "{\"decision\":\"ACCEPT_STANDARD\",\"reason\":\"Nothing else of concern on file.\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("screening match")));
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue(app, customer, "Family Funeral", "Bronze")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("screening")));
        // Only compliance (CLEAR_SCREENING) answers the match; an underwriter cannot.
        String cleared = "{\"outcome\":\"CLEARED\",\"note\":\"Different date of birth and nationality from the listed person.\"}";
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/screening"), "underwriter", cleared))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/screening"), "compliance", cleared))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underwritingCase.screeningStatus").value("CLEARED"))
                .andExpect(jsonPath("$.underwritingCase.screeningResolvedBy").value("compliance"))
                // The screening was the only referral: the rules accept the case now.
                .andExpect(jsonPath("$.underwritingCase.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.underwritingCase.decidedBy").value("rules"));
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue(app, customer, "Family Funeral", "Bronze")))
                .andExpect(status().isOk());

        String other = customer("Petra Demo Exposed", "+267 71 800 014");
        String second = apply(other, "Family Funeral", "Bronze", "NO", "NO", "Under review");
        String secondRef = caseRef(second);
        mockMvc.perform(json(post("/underwriting/cases/" + secondRef + "/screening"), "compliance",
                        "{\"outcome\":\"CONFIRMED\",\"note\":\"Identity number matches the listed person.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underwritingCase.status").value("DECLINED"));
        mockMvc.perform(get("/portfolio/applications").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + second + "')].status").value("Declined"));
    }

    @Test
    void anAcceptedRequoteWithAChangedPriceReopensTheUnderwritingTerms() throws Exception {
        String customer = customer("Onalenna Requote", "+267 71 800 016");
        String app = apply(customer, "Family Funeral", "Bronze", "YES", "NO", "Under review");
        String ref = caseRef(app);
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/decision"), "underwriter",
                        "{\"decision\":\"ACCEPT_WITH_TERMS\",\"loadingPercent\":25,\"reason\":\"Smoker loading for this life.\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/underwriting/cases/" + ref + "/offer"), "admin",
                        "{\"accept\":true,\"note\":\"Accepted by phone, call recorded\"}"))
                .andExpect(status().isOk());
        String quote = JsonPath.<java.util.List<String>>read(mockMvc.perform(get("/portfolio/applications")
                        .with(signedIn("admin", "password"))).andReturn().getResponse().getContentAsString(),
                "$[?(@.id == '" + app + "')].quoteRef").get(0);
        // The main life is now older (a corrected date of birth): the re-quote prices a different age band.
        jdbc.update("update ins_customer set date_of_birth = ? where record_ref = ?",
                java.sql.Date.valueOf(java.time.LocalDate.now().minusYears(58)), customer);
        mockMvc.perform(post("/portfolio/quotes/" + quote + "/requote").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/quotes/" + quote + "/accept"), "admin", "{\"note\":\"Customer accepted the new price\"}"))
                .andExpect(status().isOk());
        // NS-IL01-004: the earlier offer's loaded premium is never applied to the new price; the case is reassessed.
        mockMvc.perform(get("/underwriting/cases/" + ref).with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.underwritingCase.status").value("REFERRED"))
                .andExpect(jsonPath("$.underwritingCase.decision").doesNotExist())
                .andExpect(jsonPath("$.underwritingCase.referralReasons[-1:]").value(org.hamcrest.Matchers.hasItem(
                        containsString("changed price"))));
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue(app, customer, "Family Funeral", "Bronze")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("awaiting a decision")));
    }

    @Test
    void occupationAndConfiguredDisclosuresReferTheCase() throws Exception {
        String customer = customer("Kabelo Miner", "+267 71 800 015");
        String app = JsonPath.read(mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"customerRef\":\""
                                + customer + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\",\"declarationAccepted\":true,"
                                + "\"occupation\":\"Underground miner\",\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\","
                                + "\"HAZARDOUS_PURSUITS\":\"YES\"}}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("Under review"))
                        .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/underwriting/applications/" + app).with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.underwritingCase.occupation").value("Underground miner"))
                .andExpect(jsonPath("$.underwritingCase.status").value("EVIDENCE_PENDING"))
                .andExpect(jsonPath("$.underwritingCase.referralReasons[?(@ =~ /.*HAZARDOUS_PURSUITS.*/)]").exists())
                .andExpect(jsonPath("$.underwritingCase.referralReasons[?(@ =~ /.*Occupation.*miner.*/)]").exists())
                .andExpect(jsonPath("$.evidence[0].requirement").value("Occupation questionnaire"))
                .andExpect(jsonPath("$.lives[0].id").isNumber());
    }

    private String apply(String customer, String product, String tier, String tobacco, String treatment, String expected)
            throws Exception {
        return JsonPath.read(mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"customerRef\":\"" + customer
                                + "\",\"product\":\"" + product + "\",\"tier\":\"" + tier + "\",\"declarationAccepted\":true,"
                                + "\"disclosures\":{\"TOBACCO\":\"" + tobacco + "\",\"TREATMENT\":\"" + treatment + "\"}}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value(expected))
                        .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String caseRef(String app) throws Exception {
        return JsonPath.read(mockMvc.perform(get("/underwriting/applications/" + app)
                        .with(signedIn("underwriter", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.underwritingCase.ref");
    }

    private static String issue(String app, String customer, String product, String tier) {
        return "{\"applicationRef\":\"" + app + "\",\"customerRef\":\"" + customer + "\",\"product\":\"" + product
                + "\",\"tier\":\"" + tier + "\"}";
    }

    private long events(String type, String aggregate) {
        return jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and (aggregate_ref = ?"
                + " or payload like ?)", Long.class, type, aggregate, "%" + aggregate + "%");
    }

    private String customer(String name, String phone) throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                                + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\",\"phone\":\"" + phone
                                + "\",\"dateOfBirth\":\"" + java.time.LocalDate.now().minusYears(36) + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

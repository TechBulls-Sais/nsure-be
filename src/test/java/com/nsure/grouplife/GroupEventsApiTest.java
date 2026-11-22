package com.nsure.grouplife;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.Events;
import com.nsure.integration.OutboxEvent;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The group events reach the outbox at their commit points: GL01 SchemeQuoted, SchemeActivated (from a quote or set up
 * directly), SchemeClosed; GL02 SchemePlanApproved (only once a second person approves) and CategoryTermsChanged; GL03
 * MemberUnderwritingReferred and MemberCoverAuthorised; GL04 CensusBatchValidated, MemberCoverChanged and
 * CensusBatchCompleted; GL06 BorrowerCoverActivated, CreditBordereauProcessed and UnearnedPremiumRefundApproved.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-grouplife;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupEventsApiTest {

    private static final String HEADER = "memberKey,firstName,lastName,dateOfBirth,category,salary,action,effectiveDate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Test
    void quotationActivationAndPlanApprovalPublishTheGroupSchemeEvents() throws Exception {
        String quote = JsonPath.read(mockMvc.perform(json(post("/group-life/quotes"), "admin",
                        "{\"sponsorName\":\"Events Quarry " + System.nanoTime() + "\",\"sector\":\"Mining\",\"lives\":10,"
                                + "\"salaryRoll\":1000000,\"benefits\":[\"GLA\",\"FAMILY\"],\"ratePerMille\":3,"
                                + "\"freeCoverLimit\":500000}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        progress(quote, "admin", "SUBMIT");
        assertThat(events(Events.SCHEME_QUOTED, quote)).isEmpty();
        progress(quote, "secadmin", "APPROVE");
        assertThat(events(Events.SCHEME_QUOTED, quote)).hasSize(1);
        progress(quote, "admin", "ACCEPT");
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group-life/quotes/" + quote + "/activation"), "admin",
                        "{\"inceptionDate\":\"" + LocalDate.now() + "\",\"wordingRef\":\"WRD-G001\",\"conditions\":[\"SIGNED_PROPOSAL\",\"SIGNED_CENSUS\",\"FIRST_PREMIUM_OR_DEFERRAL\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.schemeRef");
        List<OutboxEvent> activated = events(Events.SCHEME_ACTIVATED, scheme);
        assertThat(activated).hasSize(1);
        assertThat(activated.get(0).getPayload()).contains("\"quoteRef\":\"" + quote + "\"");
        // The plan set up from the approved quote is approved; a later change waits for a second person.
        assertThat(events(Events.SCHEME_PLAN_APPROVED, scheme)).hasSize(1);
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefits"), "admin",
                        "{\"benefitType\":\"FAMILY\",\"basis\":\"FIXED\",\"fixedAmount\":30000}"))
                .andExpect(jsonPath("$.approvalStatus").value("PENDING"));
        assertThat(events(Events.SCHEME_PLAN_APPROVED, scheme)).hasSize(1);
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/benefits/approval"), "admin", "{\"note\":\"mine\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/benefits/approval"), "secadmin",
                        "{\"note\":\"Family limit agreed with the sponsor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.benefitType == 'FAMILY')].approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$[?(@.benefitType == 'FAMILY')].approvedBy").value("secadmin"));
        assertThat(events(Events.SCHEME_PLAN_APPROVED, scheme)).hasSize(2);
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/benefits/approval"), "secadmin", "{\"note\":\"again\"}"))
                .andExpect(status().isConflict());

        // A scheme set up directly is not in force, so not announced, until it is activated on accepted terms.
        String direct = JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin",
                        "{\"name\":\"Direct Events " + System.nanoTime() + "\",\"sector\":\"Retail\","
                                + "\"inceptionDate\":\"2026-01-01\",\"renewalDate\":\"2027-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheme.status").value("SETUP"))
                .andReturn().getResponse().getContentAsString(), "$.scheme.ref");
        assertThat(events(Events.SCHEME_ACTIVATED, direct)).isEmpty();
        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, direct);
        assertThat(events(Events.SCHEME_ACTIVATED, direct)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"wording\":\"WRD-G001 v1\""));
    }

    @Test
    void categoriesCensusAndMemberUnderwritingPublishTheirEvents() throws Exception {
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin",
                        "{\"name\":\"Census Events " + System.nanoTime() + "\",\"sector\":\"Retail\","
                                + "\"inceptionDate\":\"2026-01-01\",\"renewalDate\":\"2027-01-01\"}"))
                .andReturn().getResponse().getContentAsString(), "$.scheme.ref");
        String category = "{\"code\":\"STAFF\",\"name\":\"Staff\",\"benefitBasis\":\"FIXED\",\"fixedCover\":%s,"
                + "\"freeCoverLimit\":500000,\"ratePerMille\":1,\"minEntryAge\":18,\"maxEntryAge\":64}";
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin", String.format(category, "600000")))
                .andExpect(status().isOk());
        mockMvc.perform(json(put("/group/schemes/" + scheme + "/categories/STAFF"), "admin", String.format(category, "700000")))
                .andExpect(status().isOk());
        List<OutboxEvent> terms = events(Events.CATEGORY_TERMS_CHANGED, scheme);
        assertThat(terms).hasSize(2);
        assertThat(terms.get(0).getPayload()).contains("\"change\":\"ADDED\"");
        assertThat(terms.get(1).getPayload()).contains("\"change\":\"AMENDED\"").contains("\"fixedCover\":\"700000.00\"");

        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, scheme);
        String batch = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        "{\"externalBatchId\":\"EV-1\",\"csv\":\"" + HEADER + "\\nE1,Eve,One,1985-01-01,STAFF,,JOIN,2026-01-01"
                                + "\\nE2,Eli,Two,1985-01-01,NOPE,,JOIN,2026-01-01\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        assertThat(events(Events.CENSUS_BATCH_VALIDATED, scheme)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"validRows\":1").contains("\"invalidRows\":1"));
        assertThat(events(Events.CENSUS_BATCH_COMPLETED, scheme)).isEmpty();
        String committed = mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(events(Events.CENSUS_BATCH_COMPLETED, scheme)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"acceptedRows\":1").contains("\"rejectedRows\":1"));
        assertThat(events(Events.MEMBER_COVER_CHANGED, scheme)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"changeType\":\"JOIN\"")
                        .contains("\"coverAfter\":\"500000.00\"").contains("\"pendingAfter\":\"200000.00\""));
        // 700 000 asked for, 500 000 free: the excess is referred, then authorised by the underwriter.
        assertThat(events(Events.MEMBER_UNDERWRITING_REFERRED, scheme)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"pendingCover\":\"200000.00\""));
        Integer member = JsonPath.read(committed, "$.rows[0].memberId");
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting"), "admin",
                        "{\"decision\":\"ACCEPT\",\"reason\":\"Evidence satisfactory\"}"))
                .andExpect(status().isOk());
        assertThat(events(Events.MEMBER_COVER_AUTHORISED, scheme)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"decision\":\"ACCEPT\"")
                        .contains("\"acceptedCover\":\"700000.00\""));
        assertThat(events(Events.MEMBER_COVER_CHANGED, scheme)).hasSize(2);
        // No payload carries the member's name.
        assertThat(events(Events.MEMBER_COVER_CHANGED, scheme)).allSatisfy(e -> assertThat(e.getPayload()).doesNotContain("Eve"));
    }

    @Test
    void groupCreditPublishesCoverBordereauAndApprovedRefundEvents() throws Exception {
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin",
                        "{\"name\":\"Lender Events " + System.nanoTime() + "\",\"sector\":\"Banking\","
                                + "\"inceptionDate\":\"2026-01-01\",\"renewalDate\":\"2027-01-01\"}"))
                .andReturn().getResponse().getContentAsString(), "$.scheme.ref");
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefits"), "admin",
                        "{\"benefitType\":\"CREDIT\",\"basis\":\"LOAN_BALANCE\"}"))
                .andExpect(status().isOk());
        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, scheme);
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/credit-loans"), "admin",
                        "{\"batchReference\":\"EVB-1\",\"rows\":[{\"lenderLoanId\":\"EV1\",\"borrowerName\":\"B One\","
                                + "\"loanAmount\":120000,\"termMonths\":12}]}"))
                .andExpect(jsonPath("$.accepted").value(1));
        assertThat(events(Events.BORROWER_COVER_ACTIVATED, scheme)).hasSize(1);
        assertThat(events(Events.CREDIT_BORDEREAU_PROCESSED, scheme)).hasSize(1);
        Integer loan = JsonPath.read(mockMvc.perform(get("/group-life/schemes/" + scheme + "/credit-loans")
                .with(signedIn("admin", "password"))).andReturn().getResponse().getContentAsString(), "$[0].id");
        // The early-settlement preview shows the refund without settling; the settlement then pays exactly that.
        Double preview = JsonPath.read(mockMvc.perform(get("/group-life/credit-loans/" + loan + "/settlement")
                        .with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monthsUnearned").value(12))
                .andReturn().getResponse().getContentAsString(), "$.refundPreview");
        assertThat(events(Events.UNEARNED_PREMIUM_REFUND_APPROVED, scheme)).isEmpty();
        mockMvc.perform(json(post("/group-life/credit-loans/" + loan + "/settlement"), "admin", "{}"))
                .andExpect(jsonPath("$.refund").value(preview));
        assertThat(events(Events.UNEARNED_PREMIUM_REFUND_APPROVED, scheme)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"amount\":\"" + String.format("%.2f", preview) + "\""));
    }

    private void progress(String quote, String user, String action) throws Exception {
        mockMvc.perform(json(post("/group-life/quotes/" + quote + "/progress"), user, "{\"action\":\"" + action + "\"}"))
                .andExpect(status().isOk());
    }

    private List<OutboxEvent> events(String type, String ref) {
        return em.createQuery("select e from OutboxEvent e where e.eventType = :type and (e.payload like :scheme"
                        + " or e.payload like :quote) order by e.id", OutboxEvent.class)
                .setParameter("type", type)
                .setParameter("scheme", "%\"schemeRef\":\"" + ref + "\"%")
                .setParameter("quote", "%\"quoteRef\":\"" + ref + "\"%")
                .getResultList();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

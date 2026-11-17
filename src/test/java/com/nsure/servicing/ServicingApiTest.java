package com.nsure.servicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.portfolio.PolicyWorkflow;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/** IL03 policy administration and IL04 family administration (NS-IL03-002..006, NS-IL04-001..005, AT-IL03/IL04). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-servicing;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ServicingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PolicyWorkflow policies;

    @Autowired
    private com.nsure.integration.OutboxDispatcher dispatcher;

    @Test
    void anEndorsementIsPreviewedThenAppliedOrHeldForTheRightReview() throws Exception {
        String policy = activePolicy("Onalenna Payer", "Term Life", "Bronze", "");
        String base = "/servicing/policies/" + policy;

        // NS-IL03-003: the preview changes nothing and names the route.
        String payer = "{\"type\":\"PAYER\",\"payerName\":\"Kabelo Payer\",\"payerRelationship\":\"Spouse\",\"reason\":\"Spouse now pays\"}";
        // Test C-42: a missing value is named in plain language, not by its API field name.
        mockMvc.perform(json(post(base + "/endorsements/preview"), "admin", payer.replace("Kabelo Payer", " ")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Enter the payer's name."));
        mockMvc.perform(json(post(base + "/endorsements/preview"), "admin", payer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("APPLY"))
                .andExpect(jsonPath("$.after").value("Payer: Kabelo Payer (Spouse)"));
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policy.payerName").doesNotExist())
                .andExpect(jsonPath("$.policy.contractStatus").value("Active"))
                .andExpect(jsonPath("$.policy.coverStatus").value("In cover"))
                .andExpect(jsonPath("$.policy.paymentStatus").value("Up to date"));
        mockMvc.perform(json(post(base + "/endorsements"), "admin", payer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policy.payerName").value("Kabelo Payer"));
        assertThat(events("PolicyChanged", policy)).isEqualTo(1);

        // A material increase in risk is decided by underwriting, by someone other than the requester.
        String increase = "{\"type\":\"SUM_ASSURED\",\"tier\":\"Silver\",\"reason\":\"Customer wants more cover\"}";
        mockMvc.perform(json(post(base + "/endorsements/preview"), "admin", increase))
                .andExpect(jsonPath("$.route").value("UNDERWRITING"))
                .andExpect(jsonPath("$.oldSumAssured").value(250000.0))
                .andExpect(jsonPath("$.newSumAssured").value(500000.0))
                .andExpect(jsonPath("$.oldPremium").value(230.0))
                .andExpect(jsonPath("$.newPremium").value(420.0))
                .andExpect(jsonPath("$.documents", hasItem("Revised policy schedule")));
        String ref = JsonPath.read(mockMvc.perform(json(post(base + "/endorsements"), "admin", increase))
                .andExpect(jsonPath("$.status").value("REFERRED_UNDERWRITING"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policy.sumAssured").value(250000.0));
        String approve = "{\"approve\":true,\"note\":\"Within limits for this life\"}";
        mockMvc.perform(json(post("/servicing/endorsements/" + ref + "/decision"), "admin", approve))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/servicing/endorsements/" + ref + "/decision"), "underwriter", approve))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policy.sumAssured").value(500000.0))
                .andExpect(jsonPath("$.policy.premium").value(420.0))
                .andExpect(jsonPath("$.policy.tier").value("Silver"));
    }

    @Test
    void backdatingNeedsAuthorityAndAChangeAcrossAPaidClaimIsHeldForImpactReview() throws Exception {
        String policy = activePolicy("Boitshepo Backdate", "Term Life", "Bronze", "");
        dates(policy, LocalDate.now().minusDays(60), LocalDate.now().plusDays(1), LocalDate.now().plusDays(31));
        String base = "/servicing/policies/" + policy;
        String backdated = "{\"type\":\"FREQUENCY\",\"frequency\":\"ANNUAL\",\"effectiveDate\":\"" + LocalDate.now().minusDays(20)
                + "\",\"reason\":\"Agreed with the customer last month\"}";

        // NS-IL03-003: support staff can endorse but not backdate.
        mockMvc.perform(json(post(base + "/endorsements"), "support", backdated))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post(base + "/endorsements"), "admin", backdated.replace(LocalDate.now().minusDays(20).toString(),
                        LocalDate.now().minusDays(200).toString())))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post(base + "/endorsements"), "admin", backdated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.backdated").value(true));

        // AT-IL03-01: once a claim has been paid for an event after the effective date, the change is held.
        jdbc.update("update ins_claim set policy_ref = ?, status = 'Paid', event_date = ? where id = (select min(id) from ins_claim)",
                policy, LocalDate.now().minusDays(5).toString());
        String held = JsonPath.read(mockMvc.perform(json(post(base + "/endorsements"), "admin",
                        backdated.replace("ANNUAL", "QUARTERLY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPACT_REVIEW"))
                .andExpect(jsonPath("$.holdReason").value(containsString("must be reviewed first")))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policy.premiumFrequency").value("MONTHLY"));
        mockMvc.perform(get("/servicing/endorsements?status=HELD").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + held + "')]").isNotEmpty());
    }

    @Test
    void livesAreAddedRemovedAndReAddedWithTheirHistoryKept() throws Exception {
        String policy = activePolicy("Thato Family", "Family Funeral", "Bronze",
                ",\"lives\":[{\"relationship\":\"Spouse\",\"fullName\":\"Neo Family\",\"dateOfBirth\":\"" + LocalDate.now().minusYears(33) + "\"}]");
        dates(policy, LocalDate.now().minusDays(45), LocalDate.now().plusDays(1), LocalDate.now().plusDays(31));
        String base = "/servicing/policies/" + policy;
        String view = mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lives.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        Integer spouse = ((List<Integer>) JsonPath.read(view, "$.lives[?(@.relationship == 'Spouse')].id")).get(0);
        Integer main = ((List<Integer>) JsonPath.read(view, "$.lives[?(@.mainInsured == true)].id")).get(0);
        double premium = ((Number) JsonPath.read(view, "$.policy.premium")).doubleValue();

        // AT-IL04-01: an ineligible life is refused with the product rule and nothing is recorded.
        mockMvc.perform(json(post(base + "/endorsements"), "admin", "{\"type\":\"ADD_LIFE\",\"fullName\":\"Old Child\","
                        + "\"relationship\":\"Child\",\"dateOfBirth\":\"" + LocalDate.now().minusYears(30) + "\",\"reason\":\"Add a child\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post(base + "/endorsements"), "admin", "{\"type\":\"REMOVE_LIFE\",\"lifeId\":" + main
                        + ",\"reason\":\"Remove the main insured\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.endorsements").isEmpty())
                .andExpect(jsonPath("$.policy.premium").value(premium));

        // A new life is a risk increase: underwriting approves it, and its waiting period starts when it joins.
        String add = JsonPath.read(mockMvc.perform(json(post(base + "/endorsements"), "admin", "{\"type\":\"ADD_LIFE\","
                        + "\"fullName\":\"Tumo Family\",\"relationship\":\"Child\",\"dateOfBirth\":\"" + LocalDate.now().minusYears(4)
                        + "\",\"reason\":\"New child\"}"))
                .andExpect(jsonPath("$.status").value("REFERRED_UNDERWRITING"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/servicing/endorsements/" + add + "/decision"), "underwriter", "{\"approve\":true,\"note\":\"Eligible child\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lives.length()").value(3))
                .andExpect(jsonPath("$.lives[?(@.fullName == 'Tumo Family')].waitingPeriodStart").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.lives[?(@.fullName == 'Tumo Family')].status").value("ACTIVE"));
        assertThat(events("InsuredLifeAdded", policy)).isEqualTo(1);

        // Removal ends the cover but keeps the life on the record.
        mockMvc.perform(json(post(base + "/endorsements"), "admin", "{\"type\":\"REMOVE_LIFE\",\"lifeId\":" + spouse
                        + ",\"reason\":\"Divorce finalised\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lives.length()").value(3))
                .andExpect(jsonPath("$.lives[?(@.id == " + spouse + ")].status").value("CEASED"))
                .andExpect(jsonPath("$.lives[?(@.id == " + spouse + ")].effectiveTo").value(LocalDate.now().toString()));
        assertThat(events("InsuredLifeCeased", policy)).isEqualTo(1);

        // AT-IL04-02: the same life added again keeps its original waiting-period start; it is not reset.
        String again = JsonPath.read(mockMvc.perform(json(post(base + "/endorsements"), "admin", "{\"type\":\"ADD_LIFE\","
                        + "\"fullName\":\"Neo Family\",\"relationship\":\"Spouse\",\"dateOfBirth\":\"" + LocalDate.now().minusYears(33)
                        + "\",\"reason\":\"Reconciled\"}"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/servicing/endorsements/" + again + "/decision"), "underwriter", "{\"approve\":true,\"note\":\"Previously covered\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lives[?(@.fullName == 'Neo Family' && @.status == 'ACTIVE')].waitingPeriodStart")
                        .value(LocalDate.now().minusDays(45).toString()));

        // NS-IL04-005: a child nearing the review age gets a dated task; ceasing them reduces the premium.
        jdbc.update("update ins_policy_life_cover set date_of_birth = ? where full_name = 'Tumo Family'",
                LocalDate.now().minusYears(21).plusDays(30));
        // IL04 roster: the warning shows on the roster before any review task is run, with the life's limit and
        // its underwriting outcome (added by an endorsement underwriting approved).
        String roster = mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer tumo = ((List<Integer>) JsonPath.read(roster, "$.lives[?(@.fullName == 'Tumo Family')].id")).get(0);
        assertThat((List<List<String>>) JsonPath.read(roster, "$.roster.lives[?(@.lifeId == " + tumo + ")].warnings"))
                .singleElement().satisfies(w -> assertThat(String.join(" ", w)).contains("child review age of 21"));
        assertThat((List<String>) JsonPath.read(roster, "$.roster.lives[?(@.lifeId == " + tumo + ")].underwriting"))
                .singleElement().satisfies(u -> assertThat(u).contains("approved by underwriter"));
        assertThat((List<Number>) JsonPath.read(roster, "$.roster.lives[?(@.lifeId == " + tumo + ")].used"))
                .singleElement().satisfies(u -> assertThat(u.doubleValue()).isZero());
        assertThat(((Number) JsonPath.read(roster, "$.roster.policyLimit")).doubleValue()).isPositive();
        mockMvc.perform(post("/servicing/life-reviews/run").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
        mockMvc.perform(post("/servicing/life-reviews/run").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.created").value(0));
        Integer review = ((List<Integer>) JsonPath.read(mockMvc.perform(get("/servicing/life-reviews").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString(), "$[?(@.lifeName == 'Tumo Family')].id")).get(0);
        mockMvc.perform(json(post("/servicing/life-reviews/" + review + "/close"), "admin",
                        "{\"outcome\":\"CEASE\",\"note\":\"No longer a student\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CEASED"));
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lives[?(@.fullName == 'Tumo Family')].status").value("CEASED"));
        // IL04 events: raising the review and closing it both change the dependant's eligibility.
        assertThat(events("DependantEligibilityChanged", policy)).isEqualTo(2);
        assertThat(jdbc.queryForList("select payload from int_outbox_event where event_type = 'DependantEligibilityChanged'"
                + " and aggregate_ref = ? order by id", String.class, policy).get(1)).contains("\"outcome\":\"CEASED\"");
    }

    @Test
    void livesCapturedAtApplicationAreAnnouncedAtIssueAndCeaseWithThePolicy() throws Exception {
        String policy = activePolicy("Goitse Roster", "Family Funeral", "Bronze",
                ",\"lives\":[{\"relationship\":\"Spouse\",\"fullName\":\"Mpho Roster\",\"dateOfBirth\":\""
                        + LocalDate.now().minusYears(31) + "\"}]");
        assertThat(events("InsuredLifeAdded", policy)).isZero();
        // PolicyIssued announces both lives captured at application, once however often it is delivered.
        dispatcher.dispatch();
        assertThat(events("InsuredLifeAdded", policy)).isEqualTo(2);
        tx.executeWithoutResult(s -> em.createQuery("update EventDelivery d set d.status = 'PENDING', d.attempts = 0,"
                        + " d.nextAttemptAt = :now where d.handler = 'servicing.insured-lives' and d.event.id in"
                        + " (select e.id from OutboxEvent e where e.aggregateRef = :ref)")
                .setParameter("now", java.time.LocalDateTime.now().minusSeconds(1)).setParameter("ref", policy)
                .executeUpdate());
        dispatcher.dispatch();
        assertThat(events("InsuredLifeAdded", policy)).isEqualTo(2);

        // Ending the policy ends the lives it covers, each with InsuredLifeCeased.
        mockMvc.perform(json(post("/servicing/policies/" + policy + "/termination"), "admin",
                        "{\"reason\":\"DEATH\",\"note\":\"Death certificate received\"}"))
                .andExpect(status().isOk());
        dispatcher.dispatch();
        dispatcher.dispatch();
        assertThat(events("InsuredLifeCeased", policy)).isEqualTo(2);
        mockMvc.perform(get("/servicing/policies/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.lives[?(@.status == 'ACTIVE')]").isEmpty())
                .andExpect(jsonPath("$.lives[?(@.fullName == 'Mpho Roster')].effectiveTo").value(LocalDate.now().toString()));
    }

    @Test
    void contractualBenefitsExistOnlyWhereTheProductDefinesThem() throws Exception {
        String policy = activePolicy("Kelebogile Benefit", "Term Life", "Bronze", "");
        String base = "/servicing/policies/" + policy;

        // AT-IL03-03: no surrender rule, so no surrender value and no surrender action.
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.benefitsOffered", hasItem("CASHBACK")))
                .andExpect(jsonPath("$.surrenderOffered").value(false))
                .andExpect(jsonPath("$.schedule[0].benefitType").value("CASHBACK"))
                .andExpect(jsonPath("$.schedule[0].amount").value(2300.0));
        mockMvc.perform(get(base + "/surrender-quote").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("no approved surrender rule")));
        mockMvc.perform(json(post(base + "/surrender"), "admin", "{\"reason\":\"Customer asked\"}"))
                .andExpect(status().isUnprocessableEntity());

        // The cashback falls due after 36 months in force, once, and is paid through approval.
        dates(policy, LocalDate.now().minusMonths(37), LocalDate.now().plusDays(1), LocalDate.now().plusDays(31));
        String ref = ((List<String>) JsonPath.read(mockMvc.perform(post("/servicing/benefit-events/run").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andReturn().getResponse().getContentAsString(), "$.refs")).get(0);
        mockMvc.perform(post("/servicing/benefit-events/run").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.created").value(0));
        mockMvc.perform(json(post("/servicing/benefit-events/" + ref + "/payment"), "assessor", "{\"paymentReference\":\"EFT-1\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/servicing/benefit-events/" + ref + "/decision"), "support", "{\"approve\":true,\"note\":\"ok\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/servicing/benefit-events/" + ref + "/decision"), "assessor",
                        "{\"approve\":true,\"note\":\"Policy in force for the full period\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.amount").value(2300.0));
        mockMvc.perform(json(post("/servicing/benefit-events/" + ref + "/payment"), "assessor", "{\"paymentReference\":\"EFT-20261\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(events("ContractualBenefitPaid", ref)).isEqualTo(1);

        // Once the insurer approves a surrender rule for the product, the value is offered and ends the contract.
        mockMvc.perform(json(put("/servicing/benefit-rules"), "support", "{\"product\":\"Term Life\",\"benefitType\":\"SURRENDER\","
                        + "\"percent\":40,\"minMonths\":12,\"description\":\"40% of premiums paid after 12 months\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(put("/servicing/benefit-rules"), "admin", "{\"product\":\"Term Life\",\"benefitType\":\"SURRENDER\","
                        + "\"percent\":40,\"minMonths\":12,\"description\":\"40% of premiums paid after 12 months\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(base + "/surrender-quote").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(92.0));
        String surrender = JsonPath.read(mockMvc.perform(json(post(base + "/surrender"), "admin", "{\"reason\":\"Customer emigrating\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.benefitType").value("SURRENDER"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/servicing/benefit-events/" + surrender + "/decision"), "assessor",
                        "{\"approve\":true,\"note\":\"Signed surrender form received\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policy.contractStatus").value("Terminated"))
                .andExpect(jsonPath("$.policy.terminationReason").value("SURRENDER"))
                .andExpect(jsonPath("$.policy.coverStatus").value("Ended"));
        assertThat(events("PolicyTerminated", policy)).isEqualTo(1);
        jdbc.update("delete from prod_benefit_rule where product = 'Term Life' and benefit_type = 'SURRENDER'");
    }

    @Test
    void aLatePremiumRestoresCoverOnlyWhereTheProductRulesAllowIt() throws Exception {
        String policy = activePolicy("Lorato Reinstate", "Whole of Life", "Bronze", "");
        Integer rule = ((List<Integer>) JsonPath.read(mockMvc.perform(get("/servicing/rules").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString(), "$[?(@.product == 'Whole of Life')].id")).get(0);
        mockMvc.perform(json(put("/servicing/rules/" + rule), "admin", "{\"reinstatementNeedsApproval\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reinstatementNeedsApproval").value(true));
        dates(policy, LocalDate.now().minusMonths(4), LocalDate.now().minusDays(40), LocalDate.now().minusDays(10));
        tx.execute(s -> policies.lapseOverdue());
        assertThat(events("CoverLapsed", policy)).isEqualTo(1);

        // NS-IL03-004: the premium is received, but cover stays off until the reinstatement is approved.
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Orange Money\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/servicing/policies/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policy.contractStatus").value("Reinstatement Pending"))
                .andExpect(jsonPath("$.policy.coverStatus").value("No cover"));
        mockMvc.perform(get("/servicing/reinstatements").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + policy + "')]").isNotEmpty());
        mockMvc.perform(json(post("/servicing/policies/" + policy + "/reinstatement"), "support", "{\"approve\":true,\"note\":\"ok\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/servicing/policies/" + policy + "/reinstatement"), "admin",
                        "{\"approve\":true,\"note\":\"Health declaration received\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("Active"));
        assertThat(events("PolicyReinstated", policy)).isEqualTo(1);

        // A termination states its cause; lapse keeps its own process.
        mockMvc.perform(json(post("/servicing/policies/" + policy + "/termination"), "admin",
                        "{\"reason\":\"LAPSE\",\"note\":\"wrong route\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/servicing/policies/" + policy + "/termination"), "admin",
                        "{\"reason\":\"OTHER\",\"note\":\"Court order received\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("Terminated"))
                .andExpect(jsonPath("$.terminationReason").value("OTHER"))
                // Test C-37: an ended contract has no premium due.
                .andExpect(jsonPath("$.nextPremiumDue").doesNotExist())
                .andExpect(jsonPath("$.paymentStatus").value("Closed"));
        mockMvc.perform(json(put("/servicing/rules/" + rule), "admin", "{\"reinstatementNeedsApproval\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    void terminationCausesDistinguishCancellationAndExpiry() throws Exception {
        // Test C-37 / IL03: cancellation and expiry are causes too; a cancellation takes the cancellation process.
        String cancelled = activePolicy("Mpho Cancel", "Term Life", "Bronze", "");
        mockMvc.perform(json(post("/servicing/policies/" + cancelled + "/termination"), "support",
                        "{\"reason\":\"CANCELLATION\",\"note\":\"Written request\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/servicing/policies/" + cancelled + "/termination"), "admin",
                        "{\"reason\":\"CANCELLATION\",\"note\":\"Written request from the policyholder\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("Cancelled"))
                .andExpect(jsonPath("$.terminationReason").value("CANCELLATION"))
                .andExpect(jsonPath("$.nextPremiumDue").doesNotExist());
        assertThat(events("PolicyCancelled", cancelled)).isEqualTo(1);
        assertThat(events("PolicyTerminated", cancelled)).isEqualTo(1);

        String expired = activePolicy("Kagiso Expiry", "Term Life", "Bronze", "");
        mockMvc.perform(json(post("/servicing/policies/" + expired + "/termination"), "admin",
                        "{\"reason\":\"expiry\",\"note\":\"Contract term ended\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("Terminated"))
                .andExpect(jsonPath("$.terminationReason").value("EXPIRY"))
                .andExpect(jsonPath("$.coverStatus").value("Ended"));
        mockMvc.perform(json(post("/servicing/policies/" + expired + "/termination"), "admin", "{\"reason\":\"OTHER\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private String activePolicy(String name, String product, String tier, String extra) throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                                + "\",\"email\":\"s" + Math.abs(name.hashCode()) + "@example.com\",\"phone\":\"+267 71 900 001\","
                                + "\"dateOfBirth\":\"" + LocalDate.now().minusYears(36) + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\"" + customer
                                + "\",\"product\":\"" + product + "\",\"tier\":\"" + tier + "\"" + extra + "}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
        return policy;
    }

    private void dates(String ref, LocalDate start, LocalDate nextDue, LocalDate graceEnd) {
        tx.executeWithoutResult(s -> em.createQuery("update InsurancePolicy p set p.coverStartDate = :start,"
                        + " p.nextPremiumDue = :due, p.gracePeriodEnd = :grace where p.ref = :ref")
                .setParameter("start", start).setParameter("due", nextDue).setParameter("grace", graceEnd)
                .setParameter("ref", ref).executeUpdate());
        tx.executeWithoutResult(s -> em.createQuery("update PremiumPayment t set t.periodStart = :start,"
                        + " t.periodEnd = :end where t.policyRef = :ref")
                .setParameter("start", start).setParameter("end", start.plusMonths(1).minusDays(1))
                .setParameter("ref", ref).executeUpdate());
        tx.executeWithoutResult(s -> em.createQuery("update Instalment i set i.periodStart = :start, i.dueDate = :start,"
                        + " i.periodEnd = :end where i.policyRef = :ref")
                .setParameter("start", start).setParameter("end", start.plusMonths(1).minusDays(1))
                .setParameter("ref", ref).executeUpdate());
    }

    private long events(String type, String aggregate) {
        return jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and aggregate_ref = ?",
                Long.class, type, aggregate);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

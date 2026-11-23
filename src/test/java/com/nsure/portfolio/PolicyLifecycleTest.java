package com.nsure.portfolio;

import static com.nsure.portfolio.PolicyWorkflowApiTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** The target policy state machine (spec 10 §1.3, Tap Cover TC-17…TC-24) and race-free references (BR-59). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-policy-lifecycle;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PolicyLifecycleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PolicyWorkflow policies;

    @Autowired
    private ReferenceNumbers references;

    @Test
    void graceEndsIntoNoCoverThenPayingResumesCoverWithoutANewWaitingPeriod() throws Exception {
        String policy = activePolicy("Lorato Dintwe");
        LocalDate start = LocalDate.now().minusMonths(4);
        dates(policy, start, LocalDate.now().minusDays(40), LocalDate.now().minusDays(10));

        List<String> moved = tx.execute(s -> policies.lapseOverdue());
        assertThat(moved).contains(policy);
        assertThat(statusOf(policy)).isEqualTo(PolicyWorkflow.ACTIVE_NO_COVER);

        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Orange Money\"}"))
                .andExpect(status().isOk());
        assertThat(statusOf(policy)).isEqualTo(PolicyWorkflow.ACTIVE);
        // TC-18: the original cover start stands, so waiting periods are not restarted.
        assertThat(coverStart(policy)).isEqualTo(start);
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Cover Ended')]").isNotEmpty());
    }

    /** §09-POLICY: an overdue premium puts the policy in Grace, still in cover; paying ends grace, not paying ends cover. */
    @Test
    void anOverduePremiumPutsThePolicyInGraceWhichPaymentEnds() throws Exception {
        String policy = activePolicy("Kagiso Grace");
        LocalDate due = LocalDate.now().minusDays(5);
        dates(policy, due.minusMonths(1), due, LocalDate.now().plusDays(25));

        List<String> moved = tx.execute(s -> policies.lapseOverdue());
        assertThat(moved).contains(policy);
        assertThat(statusOf(policy)).isEqualTo(PolicyWorkflow.GRACE);
        Long graceEvents = tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.aggregateRef = :ref"
                + " and e.eventType = 'PolicyGraceStarted'", Long.class).setParameter("ref", policy).getSingleResult());
        assertThat(graceEvents).isEqualTo(1L);
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Grace Started')].newState").value("Grace"))
                .andExpect(jsonPath("$[?(@.action == 'Grace Started')].effectiveDate").value(due.plusDays(1).toString()));
        // A second sweep the same day moves nothing: the policy is already in grace, and grace has not ended.
        List<String> again = tx.execute(s -> policies.lapseOverdue());
        assertThat(again).doesNotContain(policy);

        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Orange Money\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[-1].previousState").value("Grace"))
                .andExpect(jsonPath("$[-1].newState").value("Active"));
    }

    @Test
    void graceThatRunsOutEndsCoverFromGrace() throws Exception {
        String policy = activePolicy("Neo Grace");
        LocalDate due = LocalDate.now().minusDays(5);
        dates(policy, due.minusMonths(1), due, LocalDate.now().plusDays(1));
        tx.execute(s -> policies.lapseOverdue());
        assertThat(statusOf(policy)).isEqualTo(PolicyWorkflow.GRACE);

        dates(policy, due.minusMonths(1), due, LocalDate.now().minusDays(1));
        tx.execute(s -> policies.lapseOverdue());
        assertThat(statusOf(policy)).isEqualTo(PolicyWorkflow.ACTIVE_NO_COVER);
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Cover Ended')].previousState").value("Grace"));
    }

    @Test
    void threeMonthsWithoutCoverLapsesThePolicyForGood() throws Exception {
        String policy = activePolicy("Gaone Kebonang");
        dates(policy, LocalDate.now().minusYears(1), LocalDate.now().minusMonths(5), LocalDate.now().minusMonths(4));
        // A sweep that catches up after downtime moves through both steps: cover ended, then the 3-month window.
        tx.execute(s -> policies.lapseOverdue());
        assertThat(statusOf(policy)).isEqualTo(PolicyWorkflow.LAPSED);
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Cover Ended')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.action == 'Lapsed')]").isNotEmpty());
        // TC-21: a premium after lapse does not reinstate.
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Orange Money\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anInForcePolicyIsCancelledOnRequestAndCoolingOffIsFlagged() throws Exception {
        String policy = activePolicy("Masego Tau");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/cancel"), "admin",
                        "{\"reason\":\"Customer asked in writing\"}"))
                .andExpect(status().isOk());
        assertThat(statusOf(policy)).isEqualTo(PolicyWorkflow.CANCELLED);
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Cancelled')].details").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.startsWith("Cooling-off cancellation"))));
    }

    @Test
    void referencesStayUniqueUnderConcurrency() throws Exception {
        tx.execute(s -> references.next("TST-", 4, () -> 0L));
        Set<String> taken = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 40; i++) {
            pool.submit(() -> {
                go.await();
                taken.add(tx.execute(s -> references.next("TST-", 4, () -> 0L)));
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(taken).hasSize(40).doesNotContain("TST-0001");
    }

    private String activePolicy(String name) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"x" + Math.abs(name.hashCode()) + "@example.com\","
                                + "\"phone\":\"+267 71 000 002\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String body = mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"name\":\"" + name + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Pending Payment"))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.id");
        // Staff issue makes an offer; cover starts on the first premium (NS-IL01-006).
        mockMvc.perform(json(post("/portfolio/policies/" + ref + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
        return ref;
    }

    private void dates(String ref, LocalDate start, LocalDate nextDue, LocalDate graceEnd) {
        tx.executeWithoutResult(s -> em.createQuery("update InsurancePolicy p set p.coverStartDate = :start,"
                        + " p.nextPremiumDue = :due, p.gracePeriodEnd = :grace where p.ref = :ref")
                .setParameter("start", start).setParameter("due", nextDue).setParameter("grace", graceEnd)
                .setParameter("ref", ref).executeUpdate());
        // The first premium paid the month cover started, not today.
        tx.executeWithoutResult(s -> em.createQuery("update PremiumPayment t set t.periodStart = :start,"
                        + " t.periodEnd = :end where t.policyRef = :ref")
                .setParameter("start", start).setParameter("end", start.plusMonths(1).minusDays(1))
                .setParameter("ref", ref).executeUpdate());
        // ...and so did the instalment it settled.
        tx.executeWithoutResult(s -> em.createQuery("update Instalment i set i.periodStart = :start, i.dueDate = :start,"
                        + " i.periodEnd = :end where i.policyRef = :ref")
                .setParameter("start", start).setParameter("end", start.plusMonths(1).minusDays(1))
                .setParameter("ref", ref).executeUpdate());
    }

    private String statusOf(String ref) {
        return tx.execute(s -> em.createQuery("select p.status from InsurancePolicy p where p.ref = :ref", String.class)
                .setParameter("ref", ref).getSingleResult());
    }

    private LocalDate coverStart(String ref) {
        List<LocalDate> rows = tx.execute(s -> em.createQuery(
                        "select p.coverStartDate from InsurancePolicy p where p.ref = :ref", LocalDate.class)
                .setParameter("ref", ref).getResultList());
        return rows.get(0);
    }
}

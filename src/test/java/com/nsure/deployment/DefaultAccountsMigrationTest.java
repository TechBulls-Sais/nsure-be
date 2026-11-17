package com.nsure.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nsure.NsureApplication;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The default logins (password "password") exist only in demo installations (migration 079). A production
 * migration (contexts "!demo") leaves no account that accepts that password, and the first administrator comes from
 * the bootstrap settings instead.
 */
class DefaultAccountsMigrationTest {

    private static final List<String> DEFAULT_LOGINS = List.of("admin", "customer", "onboarding", "assessor");
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    @Test
    void aProductionContextMigrationLeavesNoPasswordPasswordLogin() {
        try (ConfigurableApplicationContext app = start("jdbc:h2:mem:nsure-prod-context;DB_CLOSE_DELAY=-1", "!demo")) {
            JdbcTemplate jdbc = new JdbcTemplate(app.getBean(DataSource.class));
            List<Map<String, Object>> users = jdbc.queryForList("select username, password, enabled from app_user");
            assertThat(users).isNotEmpty();
            assertThat(users).filteredOn(DefaultAccountsMigrationTest::acceptsPassword).isEmpty();
            // The logins that older migrations created outside the demo context are there, but locked.
            for (String username : DEFAULT_LOGINS) {
                Map<String, Object> user = jdbc.queryForMap(
                        "select password, enabled from app_user where username = ?", username);
                assertThat((Boolean) user.get("enabled")).as(username + " enabled").isFalse();
                assertThat((String) user.get("password")).as(username + " password").startsWith("!demo-only");
            }
            // No demo-context login was created at all.
            assertThat(jdbc.queryForObject("select count(*) from app_user where username in"
                    + " ('secadmin', 'actuary', 'support', 'agent', 'lender')", Long.class)).isZero();
            // Nobody can act as a Super Admin until the first administrator is provisioned.
            assertThat(enabledSuperAdmins(jdbc)).isEmpty();
        }
    }

    @Test
    void theBootstrapSettingsProvisionTheFirstAdministratorOfAProductionInstallation() {
        String url = "jdbc:h2:mem:nsure-prod-bootstrap;DB_CLOSE_DELAY=-1";
        // A short bootstrap password is refused outright.
        assertThatThrownBy(() -> start(url, "!demo", "--nsure.bootstrap.admin-username=first.admin",
                "--nsure.bootstrap.admin-password=short").close())
                .hasStackTraceContaining("must be at least 12 characters");
        // So is taking over a locked demo login.
        assertThatThrownBy(() -> start(url, "!demo", "--nsure.bootstrap.admin-username=admin",
                "--nsure.bootstrap.admin-password=a-long-first-secret").close())
                .hasStackTraceContaining("exists but is not an enabled Super Admin");

        try (ConfigurableApplicationContext app = start(url, "!demo", "--nsure.bootstrap.admin-username=first.admin",
                "--nsure.bootstrap.admin-password=a-long-first-secret")) {
            JdbcTemplate jdbc = new JdbcTemplate(app.getBean(DataSource.class));
            assertThat(enabledSuperAdmins(jdbc)).containsExactly("first.admin");
            String hash = jdbc.queryForObject("select password from app_user where username = 'first.admin'",
                    String.class);
            assertThat(BCRYPT.matches("a-long-first-secret", hash)).isTrue();
            assertThat(BCRYPT.matches("password", hash)).isFalse();
        }
        // Once an administrator exists, the bootstrap settings create nobody else.
        try (ConfigurableApplicationContext app = start(url, "!demo", "--nsure.bootstrap.admin-username=second.admin",
                "--nsure.bootstrap.admin-password=another-long-secret")) {
            JdbcTemplate jdbc = new JdbcTemplate(app.getBean(DataSource.class));
            assertThat(enabledSuperAdmins(jdbc)).containsExactly("first.admin");
        }
    }

    @Test
    void aDemoMigrationKeepsTheDefaultLogins() {
        try (ConfigurableApplicationContext app = start("jdbc:h2:mem:nsure-demo-context;DB_CLOSE_DELAY=-1", "demo")) {
            JdbcTemplate jdbc = new JdbcTemplate(app.getBean(DataSource.class));
            for (String username : List.of("admin", "customer", "onboarding", "assessor", "secadmin", "agent")) {
                Map<String, Object> user = jdbc.queryForMap(
                        "select username, password, enabled from app_user where username = ?", username);
                assertThat((Boolean) user.get("enabled")).as(username + " enabled").isTrue();
                assertThat(acceptsPassword(user)).as(username + " password").isTrue();
            }
            assertThat(enabledSuperAdmins(jdbc)).contains("admin", "secadmin");
        }
    }

    private static boolean acceptsPassword(Map<String, Object> user) {
        String hash = (String) user.get("password");
        return hash != null && hash.startsWith("$2") && BCRYPT.matches("password", hash);
    }

    private static List<String> enabledSuperAdmins(JdbcTemplate jdbc) {
        return jdbc.queryForList("select u.username from app_user u join app_user_role ur on ur.user_id = u.id"
                + " join app_role r on r.id = ur.role_id where r.name = 'Super Admin' and u.enabled = ?"
                + " order by u.username", String.class, Boolean.TRUE);
    }

    private static ConfigurableApplicationContext start(String url, String contexts, String... extra) {
        String[] args = {
            "--spring.datasource.url=" + url,
            "--spring.liquibase.contexts=" + contexts,
            "--nsure.insurer.code=accounts-check",
            "--nsure.insurer.environment=test",
            "--nsure.insurer.bind-on-first-start=true",
            "--spring.main.banner-mode=off"
        };
        String[] all = new String[args.length + extra.length];
        System.arraycopy(args, 0, all, 0, args.length);
        System.arraycopy(extra, 0, all, args.length, extra.length);
        return new SpringApplicationBuilder(NsureApplication.class).web(WebApplicationType.NONE).run(all);
    }
}

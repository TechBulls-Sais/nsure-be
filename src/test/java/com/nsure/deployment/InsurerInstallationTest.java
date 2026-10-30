package com.nsure.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nsure.NsureApplication;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two insurers, one build (guide §5, §18 "Independent insurer releases"): each installation binds its own database,
 * an insurer-B backend pointed at insurer A's database refuses to migrate or start, and a production-style start
 * refuses a database nobody has provisioned.
 */
class InsurerInstallationTest {

    private static final String DB_A = "jdbc:h2:mem:nsure-insurer-a;DB_CLOSE_DELAY=-1";
    private static final String DB_B = "jdbc:h2:mem:nsure-insurer-b;DB_CLOSE_DELAY=-1";

    @Test
    void eachInsurerOwnsItsDatabaseAndNoBackendCanCrossOver() {
        String installationA;
        try (ConfigurableApplicationContext a = start(DB_A, "insurer-a", true, true)) {
            installationA = identity(a)[2];
            assertThat(identity(a)[0]).isEqualTo("insurer-a");
            assertThat(a.getBean(InsurerDeploymentProperties.class).displayName()).isEqualTo("Insurer insurer-a");
        }

        // Insurer B's backend aimed at A's database: stopped before Liquibase applies anything.
        assertThatThrownBy(() -> start(DB_A, "insurer-b", true, true).close())
                .hasStackTraceContaining("belongs to insurer 'insurer-a'")
                .hasStackTraceContaining("Refusing to migrate it");
        // …and also at startup when migrations are disabled (the production runtime).
        assertThatThrownBy(() -> start(DB_A, "insurer-b", false, true).close())
                .hasStackTraceContaining("Refusing to start");

        // A production-style start may not claim an unprovisioned database.
        assertThatThrownBy(() -> start(DB_B, "insurer-b", true, false).close())
                .hasStackTraceContaining("has no installation identity");

        // Provisioning (bind allowed) gives B its own installation; A is untouched.
        try (ConfigurableApplicationContext b = start(DB_B, "insurer-b", true, true)) {
            assertThat(identity(b)[0]).isEqualTo("insurer-b");
            assertThat(identity(b)[2]).isNotEqualTo(installationA);
        }
        try (ConfigurableApplicationContext a = start(DB_A, "insurer-a", false, false)) {
            assertThat(identity(a)[0]).isEqualTo("insurer-a");
            assertThat(identity(a)[2]).isEqualTo(installationA);
        }
    }

    private static String[] identity(ConfigurableApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class)).queryForObject(
                "select insurer_code, environment_name, installation_uuid from deployment_identity",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
    }

    private static ConfigurableApplicationContext start(String url, String insurer, boolean migrate, boolean bind) {
        return new SpringApplicationBuilder(NsureApplication.class)
                .web(WebApplicationType.NONE)
                // Command-line arguments: they outrank application.yml, as NSURE_* variables do in a deployment.
                .run("--spring.datasource.url=" + url,
                        "--spring.liquibase.enabled=" + migrate,
                        "--nsure.insurer.code=" + insurer,
                        "--nsure.insurer.environment=test",
                        "--nsure.insurer.display-name=Insurer " + insurer,
                        "--nsure.insurer.bind-on-first-start=" + bind,
                        "--spring.main.banner-mode=off");
    }
}

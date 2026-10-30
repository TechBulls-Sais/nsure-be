package com.nsure.deployment;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Runs once every bean exists (schema migrated, before the web server accepts traffic) and refuses to start unless
 * this database is bound to this insurer and environment. On an unbound database it binds the identity when
 * {@code nsure.insurer.bind-on-first-start} allows it (local start, the migrate job), otherwise it stops: a
 * production application must never claim a database that provisioning has not bound.
 *
 * <p>It also creates the optional bootstrap administrator of a clean installation, only while no enabled
 * Super Admin exists, so a configured bootstrap password cannot re-create access later.
 */
@Component
@EnableConfigurationProperties(InsurerDeploymentProperties.class)
public class InstallationGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(InstallationGuard.class);

    private final DataSource dataSource;
    private final InsurerDeploymentProperties insurer;
    private final PasswordEncoder passwords;
    private final String bootstrapUsername;
    private final String bootstrapPassword;

    public InstallationGuard(DataSource dataSource, InsurerDeploymentProperties insurer, PasswordEncoder passwords,
            @Value("${nsure.bootstrap.admin-username:}") String bootstrapUsername,
            @Value("${nsure.bootstrap.admin-password:}") String bootstrapPassword) {
        this.dataSource = dataSource;
        this.insurer = insurer;
        this.passwords = passwords;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void afterSingletonsInstantiated() {
        InstallationIdentity.Bound bound = InstallationIdentity.read(dataSource).orElse(null);
        if (bound == null) {
            if (!insurer.bindOnFirstStart()) {
                throw new IllegalStateException("This database has no installation identity and binding on first"
                        + " start is disabled. Run the migrate job for insurer '" + insurer.code() + "' ("
                        + insurer.environment() + ") first.");
            }
            bound = InstallationIdentity.bind(dataSource, insurer.code(), insurer.environment(), "system");
            log.info("Bound this database to insurer {} ({}), installation {}", insurer.code(),
                    insurer.environment(), bound.installationUuid());
        } else {
            InstallationIdentity.requireMatch(bound, insurer.code(), insurer.environment(), "start");
            log.info("Installation {} verified for insurer {} ({})", bound.installationUuid(), insurer.code(),
                    insurer.environment());
        }
        bootstrapAdministrator();
    }

    private void bootstrapAdministrator() {
        if (bootstrapUsername == null || bootstrapUsername.isBlank()) {
            return;
        }
        if (bootstrapPassword == null || bootstrapPassword.length() < 12) {
            throw new IllegalStateException("NSURE_BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters.");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long admins = jdbc.queryForObject("select count(*) from app_user u join app_user_role ur on ur.user_id = u.id"
                + " join app_role r on r.id = ur.role_id where r.name = 'Super Admin' and u.enabled = ?", Long.class,
                Boolean.TRUE);
        if (admins != null && admins > 0) {
            log.info("Bootstrap administrator skipped: an enabled Super Admin already exists");
            return;
        }
        Long taken = jdbc.queryForObject("select count(*) from app_user where username = ?", Long.class,
                bootstrapUsername);
        if (taken != null && taken > 0) {
            throw new IllegalStateException("Bootstrap user " + bootstrapUsername + " exists but is not an enabled"
                    + " Super Admin; resolve it by hand rather than letting startup change it.");
        }
        jdbc.update("insert into app_user (username, password, enabled, office_id)"
                        + " select ?, ?, ?, id from office where name = 'Head Office'",
                bootstrapUsername, passwords.encode(bootstrapPassword), Boolean.TRUE);
        jdbc.update("insert into app_user_role (user_id, role_id) select u.id, r.id from app_user u, app_role r"
                + " where u.username = ? and r.name = 'Super Admin'", bootstrapUsername);
        log.warn("Created bootstrap administrator {} at {}; remove NSURE_BOOTSTRAP_ADMIN_* now", bootstrapUsername,
                Timestamp.valueOf(LocalDateTime.now()));
    }
}

package com.nsure.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * With the {@code migrate} profile the application is a one-shot job: by the time this runs Liquibase has applied
 * the changelog (after {@link PreMigrationIdentityCheck}) and {@link InstallationGuard} has bound or verified the
 * identity, so it only reports and exits. A failure anywhere before this aborts the job with a non-zero exit.
 */
@Component
@Profile("migrate")
public class MigrationJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationJob.class);

    private final ConfigurableApplicationContext context;
    private final InsurerDeploymentProperties insurer;

    public MigrationJob(ConfigurableApplicationContext context, InsurerDeploymentProperties insurer) {
        this.context = context;
        this.insurer = insurer;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Migration complete for insurer {} ({}); exiting.", insurer.code(), insurer.environment());
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}

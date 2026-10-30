package com.nsure.deployment;

import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Verifies the target before Liquibase touches it (guide §10: "verify target identity → … migration"). Liquibase
 * runs when the SpringLiquibase bean initialises; this check runs just before that, so pointing insurer A's
 * migration at insurer B's database fails without applying a single changeset. A database with no identity yet is
 * a first install and passes.
 */
@Component
public class PreMigrationIdentityCheck implements BeanPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(PreMigrationIdentityCheck.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof SpringLiquibase liquibase && liquibase.getDataSource() != null) {
            String code = environment.getRequiredProperty("nsure.insurer.code");
            String env = environment.getRequiredProperty("nsure.insurer.environment");
            InstallationIdentity.read(liquibase.getDataSource()).ifPresentOrElse(
                    bound -> {
                        InstallationIdentity.requireMatch(bound, code, env, "migrate it");
                        log.info("Migrating the database of insurer {} ({}), installation {}", code, env,
                                bound.installationUuid());
                    },
                    () -> log.info("No installation identity yet: first migration for insurer {} ({})", code, env));
        }
        return bean;
    }
}

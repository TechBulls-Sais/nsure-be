package com.nsure.deployment;

import com.nsure.configuration.ConfigurationService;
import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The installation as the outside world may see it. {@code /public/config} is the branding and locale one frontend
 * build loads behind every insurer hostname; it carries nothing secret. {@code /admin/deployment} adds the bound
 * identity, schema history and active configuration for the insurer's own administrators.
 */
@RestController
public class DeploymentController {

    public record PublicConfig(String insurerCode, String environment, String displayName, String legalName,
            String currency, String timezone, String locale, String supportEmail, String supportPhone,
            String primaryColor, String logoUrl) {
    }

    public record SchemaState(long changesets, String lastChangeset, LocalDateTime lastAppliedAt) {
    }

    public record DeploymentView(String insurerCode, String environment, String displayName, String installationUuid,
            String boundBy, LocalDateTime boundAt, List<String> activeProfiles, SchemaState schema,
            List<String> activeConfigReleases) {
    }

    private final InsurerDeploymentProperties insurer;
    private final DataSource dataSource;
    private final Environment environment;
    private final PlatformSecurityContext security;
    private final ConfigurationService configuration;

    public DeploymentController(InsurerDeploymentProperties insurer, DataSource dataSource, Environment environment,
            PlatformSecurityContext security, ConfigurationService configuration) {
        this.insurer = insurer;
        this.dataSource = dataSource;
        this.environment = environment;
        this.security = security;
        this.configuration = configuration;
    }

    @GetMapping("/public/config")
    public PublicConfig publicConfig() {
        return new PublicConfig(insurer.code(), insurer.environment(), insurer.displayName(), insurer.legalName(),
                insurer.currency(), insurer.timezone(), insurer.locale(), insurer.supportEmail(),
                insurer.supportPhone(), insurer.primaryColor(), insurer.logoUrl());
    }

    @GetMapping("/admin/deployment")
    public DeploymentView deployment() {
        security.authenticatedUser().validateHasPermissionTo("READ_CONFIG");
        InstallationIdentity.Bound bound = InstallationIdentity.read(dataSource)
                .orElseThrow(() -> new IllegalStateException("Installation identity missing"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SchemaState schema = jdbc.query(
                "select count(*), max(DATEEXECUTED) from DATABASECHANGELOG",
                rs -> {
                    rs.next();
                    long count = rs.getLong(1);
                    java.sql.Timestamp at = rs.getTimestamp(2);
                    String last = jdbc.query("select ID from DATABASECHANGELOG order by ORDEREXECUTED desc",
                            r -> r.next() ? r.getString(1) : null);
                    return new SchemaState(count, last, at == null ? null : at.toLocalDateTime());
                });
        return new DeploymentView(insurer.code(), insurer.environment(), insurer.displayName(),
                bound.installationUuid(), bound.boundBy(), bound.boundAt(),
                List.of(environment.getActiveProfiles()), schema, configuration.activeReleaseCodes());
    }
}

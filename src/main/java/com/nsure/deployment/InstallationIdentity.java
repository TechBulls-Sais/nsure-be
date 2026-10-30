package com.nsure.deployment;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads and writes the single deployment_identity row of one insurer database with plain JDBC, so it can run before
 * Liquibase (when JPA does not exist yet) as well as at startup.
 */
final class InstallationIdentity {

    record Bound(String insurerCode, String environment, String installationUuid, String boundBy,
            LocalDateTime boundAt) {
    }

    private InstallationIdentity() {
    }

    /** The bound identity, or empty when the table is missing (fresh database) or has no row yet. */
    static Optional<Bound> read(DataSource dataSource) {
        if (!tableExists(dataSource)) {
            return Optional.empty();
        }
        List<Bound> rows = new JdbcTemplate(dataSource).query(
                "select insurer_code, environment_name, installation_uuid, bound_by, bound_at"
                        + " from deployment_identity order by id",
                (rs, n) -> new Bound(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toLocalDateTime()));
        if (rows.size() > 1) {
            throw new IllegalStateException("deployment_identity holds " + rows.size()
                    + " rows; an installation has exactly one. Refusing to start.");
        }
        return rows.stream().findFirst();
    }

    static Bound bind(DataSource dataSource, String insurerCode, String environment, String actor) {
        Bound bound = new Bound(insurerCode, environment, UUID.randomUUID().toString(), actor, LocalDateTime.now());
        new JdbcTemplate(dataSource).update(
                "insert into deployment_identity (insurer_code, environment_name, installation_uuid, bound_by, bound_at)"
                        + " values (?, ?, ?, ?, ?)",
                bound.insurerCode(), bound.environment(), bound.installationUuid(), bound.boundBy(),
                Timestamp.valueOf(bound.boundAt()));
        return bound;
    }

    /** Throws unless the database belongs to this insurer and environment. */
    static void requireMatch(Bound bound, String insurerCode, String environment, String when) {
        if (!bound.insurerCode().equals(insurerCode) || !bound.environment().equals(environment)) {
            throw new IllegalStateException("This database belongs to insurer '" + bound.insurerCode() + "' ("
                    + bound.environment() + ", installation " + bound.installationUuid() + "), but this backend is"
                    + " configured for '" + insurerCode + "' (" + environment + "). Refusing to " + when
                    + ". Check NSURE_DB_URL and NSURE_INSURER_CODE / NSURE_ENVIRONMENT.");
        }
    }

    private static boolean tableExists(DataSource dataSource) {
        try {
            new JdbcTemplate(dataSource).queryForObject("select count(*) from deployment_identity", Long.class);
            return true;
        } catch (BadSqlGrammarException ex) {
            // No table yet: a database the migrations have never run on. Connection failures still propagate.
            return false;
        }
    }
}

package com.nsure.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only access for the Life module APIs. The product ships only the roles in the Nsure roles spec, so the demo
 * logins grpadmin, finance, distribution, reinsurance, service and ops hold no role after migration 044. Module tests
 * still need users who hold exactly one module's permission codes (to prove segregation), so this gives each of those
 * logins a test role with those codes and re-enables it. Idempotent; call it before each test.
 */
public final class ModuleTestUsers {

    private static final Map<String, List<String>> CODES = new LinkedHashMap<>();

    static {
        CODES.put("grpadmin", List.of("READ_SCHEME", "MANAGE_SCHEME", "PROCESS_CENSUS", "UNDERWRITE_MEMBER",
                "BILL_SCHEME", "READ_REPORT"));
        CODES.put("finance", List.of("READ_FINANCE", "MANAGE_FINANCE", "CLOSE_PERIOD", "EXPORT_GL",
                "READ_DISTRIBUTION", "PAY_COMMISSION", "READ_REINSURANCE", "READ_SCHEME", "READ_INTEGRATION",
                "READ_REPORT"));
        CODES.put("distribution", List.of("READ_DISTRIBUTION", "MANAGE_DISTRIBUTION", "READ_POLICY"));
        CODES.put("reinsurance", List.of("READ_REINSURANCE", "MANAGE_REINSURANCE", "APPROVE_TREATY", "READ_POLICY"));
        CODES.put("service", List.of("READ_SERVICE_CASE", "MANAGE_SERVICE_CASE", "READ_COMMUNICATION",
                "MANAGE_COMMUNICATION", "READ_CUSTOMER"));
        CODES.put("ops", List.of("READ_INTEGRATION", "MANAGE_INTEGRATION", "READ_COMMUNICATION", "APPROVE_TEMPLATE",
                "READ_SERVICE_CASE", "READ_REPORT", "READ_CONFIG", "ACTIVATE_CONFIG"));
    }

    private ModuleTestUsers() {
    }

    public static void provision(JdbcTemplate jdbc) {
        CODES.forEach((username, codes) -> {
            String role = "Test module role " + username;
            jdbc.update("INSERT INTO app_role (name, description, disabled) SELECT ?, 'Test-only role.', FALSE "
                    + "WHERE NOT EXISTS (SELECT 1 FROM app_role WHERE name = ?)", role, role);
            for (String code : codes) {
                jdbc.update("INSERT INTO app_role_permission (role_id, permission_id) "
                        + "SELECT r.id, p.id FROM app_role r JOIN app_permission p ON p.code = ? "
                        + "WHERE r.name = ? AND NOT EXISTS (SELECT 1 FROM app_role_permission x "
                        + "WHERE x.role_id = r.id AND x.permission_id = p.id)", code, role);
            }
            jdbc.update("INSERT INTO app_user_role (user_id, role_id) "
                    + "SELECT u.id, r.id FROM app_user u JOIN app_role r ON r.name = ? WHERE u.username = ? "
                    + "AND NOT EXISTS (SELECT 1 FROM app_user_role x WHERE x.user_id = u.id AND x.role_id = r.id)",
                    role, username);
            jdbc.update("UPDATE app_user SET enabled = TRUE WHERE username = ?", username);
        });
    }
}

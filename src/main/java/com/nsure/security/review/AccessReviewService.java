package com.nsure.security.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.StaffAlertService;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Role;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.AppUserRepository;
import com.nsure.security.service.PlatformSecurityContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access recertification (NS-SH11-007, SH11 "access-review screen"): a reviewer snapshots every enabled login that
 * holds access, then confirms (KEEP) or flags (REVOKE, with a reason) each one. Nobody reviews their own access.
 * Closing the review needs every login decided; each REVOKE is then alerted to the identity administrators
 * (UPDATE_USER), who remove the access through the usual four-eyes change, so the reviewer never changes access
 * themselves. Every step is recorded as audit evidence.
 */
@Service
public class AccessReviewService {

    public static final String REVIEW_CODE = "REVIEW_ACCESS";
    private static final Set<String> DECISIONS = Set.of("KEEP", "REVOKE");

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final PlatformSecurityContext security;
    private final EvidenceChain evidence;
    private final StaffAlertService alerts;

    public AccessReviewService(JdbcTemplate jdbc, AppUserRepository users, PlatformSecurityContext security,
            EvidenceChain evidence, StaffAlertService alerts) {
        this.jdbc = jdbc;
        this.users = users;
        this.security = security;
        this.evidence = evidence;
        this.alerts = alerts;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        security.authenticatedUser().validateHasAnyPermission(REVIEW_CODE, "READ_AUDIT");
        return jdbc.query("select r.*, (select count(*) from acc_review_item i where i.review_id = r.id) as items,"
                + " (select count(*) from acc_review_item i where i.review_id = r.id and i.decision is not null) as"
                + " decided, (select count(*) from acc_review_item i where i.review_id = r.id and i.decision ="
                + " 'REVOKE') as revokes from acc_review r order by r.id desc", AccessReviewService::row);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long id) {
        security.authenticatedUser().validateHasAnyPermission(REVIEW_CODE, "READ_AUDIT");
        Map<String, Object> review = find(id);
        review.put("items", jdbc.query("select * from acc_review_item where review_id = ? order by username",
                AccessReviewService::row, id));
        return review;
    }

    /** Starts a review: one item per enabled login holding access, with its roles as they are now. */
    @Transactional
    public Map<String, Object> start() {
        AppUser reviewer = reviewer();
        Integer open = jdbc.queryForObject("select count(*) from acc_review where status = 'OPEN'", Integer.class);
        if (open != null && open > 0) {
            throw new ConflictException("An access review is already open. Close it before starting another.");
        }
        Integer count = jdbc.queryForObject("select count(*) from acc_review", Integer.class);
        String ref = String.format("AR-%04d", (count == null ? 0 : count) + 1);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into acc_review (record_ref, status, started_by, started_at) values (?, 'OPEN', ?, ?)", ref,
                reviewer.getUsername(), now);
        Long id = jdbc.queryForObject("select id from acc_review where record_ref = ?", Long.class, ref);
        int snapshot = 0;
        List<AppUser> all = new ArrayList<>(users.findAll());
        all.sort(Comparator.comparing(AppUser::getUsername));
        for (AppUser user : all) {
            if (!user.isEnabled() || user.getRoles() == null) {
                continue;
            }
            Set<String> roles = new TreeSet<>();
            Set<String> codes = new TreeSet<>();
            boolean desk = false;
            for (Role role : user.getRoles()) {
                boolean access = role.isDeskAccess() || (role.getPermissions() != null && !role.getPermissions().isEmpty())
                        || AppUser.SUPER_ADMIN_ROLE.equalsIgnoreCase(role.getName());
                if (!access) {
                    continue;
                }
                roles.add(role.getName() + (role.isDisabled() ? " (disabled)" : ""));
                desk |= role.isDeskAccess() && !role.isDisabled();
                if (!role.isDisabled() && role.getPermissions() != null) {
                    role.getPermissions().forEach(p -> codes.add(p.getCode()));
                }
            }
            if (roles.isEmpty()) {
                continue;
            }
            jdbc.update("insert into acc_review_item (review_id, user_id, username, roles, permission_count,"
                    + " desk_access) values (?, ?, ?, ?, ?, ?)", id, user.getId(), user.getUsername(),
                    truncate(String.join(", ", roles), 1000), codes.size(), desk);
            snapshot++;
        }
        evidence.record(reviewer.getUsername(), "Access review started", "Access review", ref,
                snapshot + " login(s) to recertify");
        return get(id);
    }

    /** {decision: KEEP | REVOKE, note}: a REVOKE needs the reason. */
    @Transactional
    public Map<String, Object> decide(Long id, Long itemId, JsonNode body) {
        AppUser reviewer = reviewer();
        Map<String, Object> review = openReview(id);
        Map<String, Object> item = jdbc.query("select * from acc_review_item where id = ? and review_id = ?",
                AccessReviewService::row, itemId, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Review item " + itemId + " not found"));
        if (reviewer.getId().equals(((Number) item.get("userId")).longValue())) {
            throw new NoAuthorizationException("Nobody recertifies their own access; another reviewer decides it.");
        }
        String decision = text(body, "decision").toUpperCase(Locale.ROOT);
        if (!DECISIONS.contains(decision)) {
            throw new IllegalArgumentException("The decision is KEEP or REVOKE.");
        }
        String note = text(body, "note");
        if ("REVOKE".equals(decision) && note.length() < 5) {
            throw new IllegalArgumentException("Say why this access should be removed (at least 5 characters).");
        }
        jdbc.update("update acc_review_item set decision = ?, note = ?, decided_by = ?, decided_at = ? where id = ?",
                decision, note.isEmpty() ? null : truncate(note, 500), reviewer.getUsername(), LocalDateTime.now(),
                itemId);
        evidence.record(reviewer.getUsername(), "Access review: " + ("KEEP".equals(decision) ? "kept" : "revoke"),
                "User", (String) item.get("username"), review.get("recordRef") + (note.isEmpty() ? "" : ": " + note));
        return get(id);
    }

    /** Closes a review once every login is decided; each REVOKE is alerted to the identity administrators. */
    @Transactional
    public Map<String, Object> close(Long id) {
        AppUser reviewer = reviewer();
        Map<String, Object> review = openReview(id);
        String ref = (String) review.get("recordRef");
        Integer pending = jdbc.queryForObject("select count(*) from acc_review_item where review_id = ? and decision"
                + " is null", Integer.class, id);
        if (pending != null && pending > 0) {
            throw new IllegalArgumentException(pending + " login(s) still need a decision before " + ref
                    + " can close.");
        }
        List<Map<String, Object>> revokes = jdbc.query("select * from acc_review_item where review_id = ? and"
                + " decision = 'REVOKE' order by username", AccessReviewService::row, id);
        Integer kept = jdbc.queryForObject("select count(*) from acc_review_item where review_id = ? and decision ="
                + " 'KEEP'", Integer.class, id);
        String summary = kept + " kept, " + revokes.size() + " to revoke";
        jdbc.update("update acc_review set status = 'CLOSED', closed_by = ?, closed_at = ?, summary = ? where id = ?",
                reviewer.getUsername(), LocalDateTime.now(), summary, id);
        for (Map<String, Object> item : revokes) {
            alerts.raise("ACCESS_REVIEW_REVOKE", "UPDATE_USER", "Access review " + ref + ": remove "
                    + item.get("username") + "'s access",
                    item.get("decidedBy") + " found that " + item.get("username") + " (" + item.get("roles")
                            + ") should lose this access: " + item.get("note") + ". Change the user under Access.",
                    "ACCESS_REVIEW_REVOKE:" + ref + ":" + item.get("username"), reviewer.getUsername());
        }
        evidence.record(reviewer.getUsername(), "Access review closed", "Access review", ref, summary);
        return get(id);
    }

    private AppUser reviewer() {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(REVIEW_CODE);
        return user;
    }

    private Map<String, Object> find(Long id) {
        return jdbc.query("select * from acc_review where id = ?", AccessReviewService::row, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Access review " + id + " not found"));
    }

    private Map<String, Object> openReview(Long id) {
        Map<String, Object> review = find(id);
        if (!"OPEN".equals(review.get("status"))) {
            throw new ConflictException(review.get("recordRef") + " is closed.");
        }
        return review;
    }

    private static String text(JsonNode body, String field) {
        return body == null || !body.hasNonNull(field) ? "" : body.get(field).asText().trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** A row as a map with camelCase keys and local date-times. */
    private static Map<String, Object> row(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            String[] parts = rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT).split("_");
            StringBuilder key = new StringBuilder(parts[0]);
            for (int p = 1; p < parts.length; p++) {
                key.append(Character.toUpperCase(parts[p].charAt(0))).append(parts[p].substring(1));
            }
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Timestamp t) {
                v = t.toLocalDateTime();
            }
            m.put(key.toString(), v);
        }
        return m;
    }
}

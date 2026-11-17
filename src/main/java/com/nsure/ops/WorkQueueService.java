package com.nsure.ops;

import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.PermissionCatalog;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.AppUserRepository;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.servicedesk.ServiceDeskService;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The work queue (Nsure Life SH10, NS-SH10-002): every open piece of work across the modules, each with its team,
 * priority, age, working-hours due time, owner, linked contract and next action.
 *
 * <ul>
 *   <li>Each user sees the work their permissions let them act on (D-24): the codes the module's own endpoint checks
 *       for that step, and never an approval they made themselves (every module refuses self-approval). A holder of
 *       {@value #ASSIGN_PERMISSION} (a supervisor) sees everything, to hand it out.</li>
 *   <li>Ownership is taken atomically and only by someone who may act on the item; an unknown key is refused. A
 *       supervisor assigns work to someone else who may act on it. {@link WorkOwnershipGuard} makes the module's own
 *       actions respect the owner.</li>
 *   <li>Items are views over domain status: closing or releasing a queue item never changes a record's status.</li>
 *   <li>TaskAssigned is published when work is taken or assigned, SlaBreached once when open work passes its due
 *       time (spec SH10 events).</li>
 * </ul>
 */
@Service
public class WorkQueueService {

    /** Assign work to someone else, and see every team's work (a supervisor). */
    public static final String ASSIGN_PERMISSION = "ASSIGN_WORK";

    private static final List<String> PRIORITIES = List.of("HIGH", "NORMAL", "LOW");

    /**
     * One kind of open work: the workflow definition it follows, the page that handles it, the query that finds it
     * (columns ref, subject, detail, opened, status, and optionally contract_ref, maker, item_priority, module_owner,
     * module_due), who may act on an item and what comes next.
     *
     * @param ownedInModule ownership lives in the module itself (service desk cases), not in the queue
     */
    private record Source(String workflow, String page, String sql, Function<Map<String, Object>, List<String>> actors,
            Function<Map<String, Object>, String> nextAction, boolean ownedInModule) {
    }

    private static Source source(String workflow, String page, String sql, List<String> actors,
            Map<String, String> next) {
        return new Source(workflow, page, sql, item -> actors, item -> next.getOrDefault(status(item), "Act on it"),
                false);
    }

    private static final List<Source> SOURCES = List.of(
            source("UNDERWRITING", "underwriting", "select record_ref as ref, customer_name as subject, product as"
                    + " detail, created_at as opened, status, application_ref as contract_ref from uw_case where status"
                    + " in ('REFERRED', 'EVIDENCE_PENDING')", List.of("UNDERWRITE_APPLICATION"),
                    Map.of("REFERRED", "Decide within authority", "EVIDENCE_PENDING", "Chase or record the evidence")),
            new Source("ENDORSEMENT", "servicing", "select record_ref as ref, policy_ref as subject, endorsement_type as"
                    + " detail, requested_at as opened, status, policy_ref as contract_ref, requested_by as maker from"
                    + " pol_endorsement where status in ('PENDING_APPROVAL', 'REFERRED_UNDERWRITING', 'IMPACT_REVIEW')",
                    item -> "REFERRED_UNDERWRITING".equals(status(item)) ? List.of("UNDERWRITE_APPLICATION")
                            : List.of("APPROVE_ENDORSEMENT"),
                    item -> switch (status(item)) {
                        case "REFERRED_UNDERWRITING" -> "Underwriting decides the change";
                        case "IMPACT_REVIEW" -> "Review the impact, then approve or reject";
                        default -> "Approve or reject the change";
                    }, false),
            source("BENEFIT", "servicing", "select record_ref as ref, policy_ref as subject, benefit_type as detail,"
                    + " created_at as opened, status, policy_ref as contract_ref from pol_benefit_event where status ="
                    + " 'DUE'", List.of("APPROVE_BENEFIT"),
                    Map.of("DUE", "Confirm the policy was in force, then approve or decline")),
            source("CARE_CASH", "care-cash", "select record_ref as ref, life_name as subject, event_type as detail,"
                    + " created_at as opened, status, policy_ref as contract_ref, created_by as maker from cc_event"
                    + " where status = 'ASSESSED'", List.of("APPROVE_CLAIM"),
                    Map.of("ASSESSED", "Check the benefit trace, then approve or decline")),
            source("SCHEME_EVENT", "scheme-lifecycle", "select record_ref as ref, scheme_ref as subject, event_type as"
                    + " detail, requested_at as opened, status, scheme_ref as contract_ref, requested_by as maker from"
                    + " grp_scheme_event where status = 'PENDING'", List.of("UNDERWRITE_MEMBER"),
                    Map.of("PENDING", "Review the impact and decide")),
            source("GROUP_QUOTE", "scheme-lifecycle", "select record_ref as ref, sponsor_name as subject, benefits as"
                    + " detail, created_at as opened, status, scheme_ref as contract_ref from grp_quote where status ="
                    + " 'SUBMITTED'", List.of("MANAGE_SCHEME", "UNDERWRITE_MEMBER"),
                    Map.of("SUBMITTED", "Check the census and rate, then approve or decline")),
            new Source("PORTAL_REQUEST", "work-queue", "select record_ref as ref, created_by as subject, request_type as"
                    + " detail, created_at as opened, status, scope_ref as contract_ref, portal_type from portal_request"
                    + " where status = 'OPEN'",
                    // The module that owns the partner actions it: distribution, group administration or credit life.
                    item -> switch (String.valueOf(item.get("portalType"))) {
                        case "AGENT" -> List.of("MANAGE_DISTRIBUTION");
                        case "EMPLOYER" -> List.of("MANAGE_SCHEME");
                        default -> List.of("MANAGE_CREDIT");
                    },
                    item -> "Action it in the owning module, then close it with a note", false),
            source("CLAIM", "claims", "select record_ref as ref, customer_name as subject, product as detail, lodged_at"
                    + " as opened, status, policy_ref as contract_ref from ins_claim where status in ('Initiated',"
                    + " 'Under Review')", List.of("ASSESS_CLAIM", "REJECT_CLAIM"),
                    Map.of("Initiated", "Run the checks and assess", "Under Review", "Complete the assessment")),
            source("CLAIM_DECISION", "claims", "select record_ref as ref, customer_name as subject, product as detail,"
                    + " lodged_at as opened, status, policy_ref as contract_ref, recommended_by as maker from ins_claim"
                    + " where status = 'Recommended'", List.of("APPROVE_CLAIM", "REJECT_CLAIM"),
                    Map.of("Recommended", "Approve or reject the recommendation")),
            source("CLAIM_PAYOUT", "claims", "select record_ref as ref, customer_name as subject, product as detail,"
                    + " lodged_at as opened, status, policy_ref as contract_ref from ins_claim where status in"
                    + " ('Approved', 'Payout Sent', 'Payout Failed')", List.of("SETTLE_CLAIM"),
                    // NS-SH10-006: a failed payout stays here as retryable work, never as a completed claim.
                    Map.of("Approved", "Send the payout", "Payout Sent", "Confirm the payout or record that it failed",
                            "Payout Failed", "Retry the failed payout")),
            new Source("SERVICE_REQUEST", "service-desk", serviceCases("SERVICE_REQUEST"),
                    item -> List.of("MANAGE_SERVICE_CASE"), WorkQueueService::serviceCaseNext, true),
            new Source("COMPLAINT", "service-desk", serviceCases("COMPLAINT"),
                    item -> List.of("MANAGE_SERVICE_CASE"), WorkQueueService::serviceCaseNext, true),
            source("MANUAL_JOURNAL", "finance", "select record_ref as ref, description as subject, amount as detail,"
                    + " made_at as opened, status, made_by as maker from fin_manual_journal where status = 'PENDING'",
                    List.of("APPROVE_JOURNAL"), Map.of("PENDING", "Approve or reject the journal")),
            source("TREATY", "reinsurance", "select record_ref as ref, name as subject, reinsurer as detail, created_at"
                    + " as opened, status, created_by as maker from ri_treaty where status = 'DRAFT'",
                    List.of("APPROVE_TREATY"), Map.of("DRAFT", "Approve the treaty")),
            source("TEMPLATE", "communications", "select id as ref, code as subject, channel as detail, created_at as"
                    + " opened, status, created_by as maker from com_template where status = 'DRAFT'",
                    List.of("APPROVE_TEMPLATE"), Map.of("DRAFT", "Approve the template version")),
            new Source("ACCESS_APPROVAL", "access", "select c.id as ref, c.action_name, c.entity_name, c.resource_id,"
                    + " c.made_on as opened, c.status, u.username as maker from command_source c left join app_user u on"
                    + " u.id = c.maker_id where c.status = 'AWAITING_APPROVAL'",
                    item -> List.of("CHECKER_" + item.get("actionName") + "_" + item.get("entityName")),
                    item -> "Approve or reject the change", false));

    private static String serviceCases(String type) {
        return "select record_ref as ref, customer_name as subject, category as detail, opened_at as opened, status,"
                + " linked_ref as contract_ref, priority as item_priority, owner as module_owner, due_at as module_due"
                + " from svc_case where case_type = '" + type + "' and status in ('OPEN', 'IN_PROGRESS', 'PAUSED')";
    }

    private static String serviceCaseNext(Map<String, Object> item) {
        return switch (status(item)) {
            case "OPEN" -> "Take it and acknowledge the customer";
            case "PAUSED" -> "Resume when the pause reason clears";
            default -> "Resolve it with the customer";
        };
    }

    private static final RowMapper<Map<String, Object>> ROW = WorkQueueService::row;

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final AppUserRepository users;
    private final ServiceDeskService serviceDesk;
    private final Outbox outbox;
    private final EvidenceChain audit;

    public WorkQueueService(JdbcTemplate jdbc, PlatformSecurityContext security, AppUserRepository users,
            ServiceDeskService serviceDesk, Outbox outbox, EvidenceChain audit) {
        this.jdbc = jdbc;
        this.security = security;
        this.users = users;
        this.serviceDesk = serviceDesk;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** The open work the signed-in user may act on (all of it for a supervisor), most urgent first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue() {
        AppUser user = staff();
        boolean supervisor = user.hasPermissionTo(ASSIGN_PERMISSION);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item item : items()) {
            boolean canAct = canAct(user, item);
            if (canAct || supervisor || user.getUsername().equals(item.view().get("owner"))) {
                Map<String, Object> view = new LinkedHashMap<>(item.view());
                view.put("canAct", canAct);
                out.add(view);
            }
        }
        return out;
    }

    /** Takes ownership of one item. Two people cannot both take it: the second is told who has it. */
    @Transactional
    public Map<String, Object> claim(String key) {
        AppUser user = staff();
        Item item = find(key);
        if (!canAct(user, item)) {
            throw new NoAuthorizationException("You cannot act on " + item.label() + ", so you cannot take it.");
        }
        assign(item, user.getUsername(), user.getUsername(), false);
        return Map.of("key", item.key(), "owner", user.getUsername());
    }

    /** A supervisor hands an item to someone who may act on it, taking it from its current owner if need be. */
    @Transactional
    public Map<String, Object> assignTo(String key, String username) {
        AppUser supervisor = staff();
        supervisor.validateHasPermissionTo(ASSIGN_PERMISSION);
        Item item = find(key);
        AppUser assignee = users.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new NotFoundException("User " + username + " not found"));
        if (!isStaff(assignee) || !assignee.isEnabled() || !canAct(assignee, item)) {
            throw new IllegalArgumentException(assignee.getUsername() + " cannot act on " + item.label() + ".");
        }
        assign(item, assignee.getUsername(), supervisor.getUsername(), true);
        return Map.of("key", item.key(), "owner", assignee.getUsername());
    }

    /** Who a supervisor may assign an item to: enabled staff who may act on it. */
    @Transactional(readOnly = true)
    public List<String> assignees(String key) {
        staff().validateHasPermissionTo(ASSIGN_PERMISSION);
        Item item = find(key);
        return users.findAll().stream()
                .filter(u -> u.isEnabled() && isStaff(u) && canAct(u, item))
                .map(AppUser::getUsername).sorted().toList();
    }

    /** The owner gives the item back; a supervisor may release anyone's. */
    @Transactional
    public Map<String, Object> release(String key) {
        AppUser user = staff();
        if (user.hasPermissionTo(ASSIGN_PERMISSION)) {
            jdbc.update("delete from wf_assignment where item_key = ?", key);
        } else {
            jdbc.update("delete from wf_assignment where item_key = ? and owner = ?", key, user.getUsername());
        }
        return Map.of("key", key);
    }

    /**
     * Who owns these queue keys, if anyone: the module guard ({@link WorkOwnershipGuard}) refuses an action on work
     * someone else has taken.
     */
    @Transactional(readOnly = true)
    public Map<String, String> owners(List<String> keys) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (String key : keys) {
            jdbc.query("select owner from wf_assignment where item_key = ?", rs -> {
                owners.put(key, rs.getString(1));
            }, key);
        }
        return owners;
    }

    /**
     * SlaBreached, once per item, when open work passes its due time (spec SH10). Also forgets ownership and breach
     * records of work that is no longer open, so work that comes back starts clean.
     */
    @Scheduled(cron = "${nsure.workflow.sla-check-cron:0 */10 * * * *}")
    @Transactional
    public int detectBreaches() {
        List<Item> open = items();
        Set<String> openKeys = open.stream().map(Item::key).collect(Collectors.toSet());
        Set<String> recorded = new HashSet<>(jdbc.queryForList("select item_key from wf_sla_breach", String.class));
        int raised = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Item item : open) {
            if (!Boolean.TRUE.equals(item.view().get("breached")) || recorded.contains(item.key())) {
                continue;
            }
            try {
                jdbc.update("insert into wf_sla_breach (item_key, due_at, detected_at) values (?, ?, ?)", item.key(),
                        item.view().get("dueAt"), now);
            } catch (DuplicateKeyException alreadyRaised) {
                continue;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("key", item.key());
            event.put("workflow", item.workflow());
            event.put("ref", item.ref());
            event.put("team", item.view().get("team"));
            event.put("owner", item.view().get("owner"));
            event.put("dueAt", String.valueOf(item.view().get("dueAt")));
            outbox.publish(Events.SLA_BREACHED, item.key(), event, "system");
            raised++;
        }
        for (String key : recorded) {
            if (!openKeys.contains(key)) {
                jdbc.update("delete from wf_sla_breach where item_key = ?", key);
            }
        }
        for (String key : jdbc.queryForList("select item_key from wf_assignment", String.class)) {
            if (!openKeys.contains(key)) {
                jdbc.update("delete from wf_assignment where item_key = ?", key);
            }
        }
        return raised;
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────────

    /** An open item: its key, workflow, reference, who may act on it, who made it (if an approval) and its view. */
    private record Item(String key, String workflow, String ref, Source source, List<String> actors, String maker,
            Map<String, Object> view) {

        String label() {
            return view.get("workflowName") + " " + ref;
        }
    }

    /** Every open item across the modules, most urgent first: past SLA, then priority, then due time. */
    private List<Item> items() {
        Map<String, Map<String, Object>> definitions = jdbc.query("select * from wf_definition", ROW).stream()
                .collect(Collectors.toMap(d -> (String) d.get("code"), d -> d));
        Map<String, Object> calendar = jdbc.query("select * from wf_calendar where id = 1", ROW).stream().findFirst()
                .orElse(null);
        Map<String, String[]> owners = new LinkedHashMap<>();
        jdbc.query("select item_key, owner, assigned_by from wf_assignment", rs -> {
            owners.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
        });
        LocalDateTime now = LocalDateTime.now();
        List<Item> items = new ArrayList<>();
        for (Source source : SOURCES) {
            Map<String, Object> d = definitions.get(source.workflow());
            if (d == null) {
                continue;
            }
            for (Map<String, Object> r : jdbc.query(source.sql(), ROW)) {
                String ref = String.valueOf(r.get("ref"));
                String key = source.workflow() + ":" + ref;
                LocalDateTime opened = (LocalDateTime) r.get("opened");
                LocalDateTime due = r.get("moduleDue") instanceof LocalDateTime moduleDue ? moduleDue
                        : opened == null || calendar == null ? null
                        : OpsService.addWorkingHours(opened, (Integer) d.get("slaHours"), calendar);
                boolean paused = "PAUSED".equals(r.get("status"));
                String[] assignment = owners.get(key);
                String owner = source.ownedInModule() ? (String) r.get("moduleOwner")
                        : assignment == null ? null : assignment[0];
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("key", key);
                view.put("workflow", source.workflow());
                view.put("workflowName", d.get("name"));
                view.put("team", d.get("team"));
                view.put("page", source.page());
                view.put("ref", ref);
                view.put("subject", subject(source.workflow(), r));
                view.put("detail", r.get("detail") == null ? null : String.valueOf(r.get("detail")));
                view.put("status", r.get("status"));
                view.put("contractRef", r.get("contractRef"));
                view.put("priority", priority(r.get("itemPriority"), d.get("priority")));
                view.put("opened", opened);
                view.put("ageHours", opened == null ? null : ChronoUnit.HOURS.between(opened, now));
                view.put("slaHours", d.get("slaHours"));
                view.put("dueAt", due);
                view.put("breached", due != null && !paused && now.isAfter(due));
                view.put("owner", owner);
                view.put("assignedBy", source.ownedInModule() || assignment == null ? null : assignment[1]);
                view.put("nextAction", source.nextAction().apply(r));
                view.put("steps", d.get("steps"));
                items.add(new Item(key, source.workflow(), ref, source, source.actors().apply(r),
                        (String) r.get("maker"), view));
            }
        }
        items.sort(Comparator
                .comparing((Item i) -> !Boolean.TRUE.equals(i.view().get("breached")))
                .thenComparing(i -> PRIORITIES.indexOf(String.valueOf(i.view().get("priority"))))
                .thenComparing(i -> (LocalDateTime) i.view().get("dueAt"),
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return items;
    }

    private static String subject(String workflow, Map<String, Object> r) {
        if ("ACCESS_APPROVAL".equals(workflow)) {
            return r.get("actionName") + " " + r.get("entityName")
                    + (r.get("resourceId") == null ? "" : " #" + r.get("resourceId"));
        }
        return r.get("subject") == null ? null : String.valueOf(r.get("subject"));
    }

    /** The item's own priority (a service case has one), else its workflow's. */
    private static String priority(Object own, Object definition) {
        String value = own != null ? String.valueOf(own) : definition != null ? String.valueOf(definition) : "NORMAL";
        value = value.toUpperCase(Locale.ROOT);
        return PRIORITIES.contains(value) ? value : "NORMAL";
    }

    private Item find(String key) {
        for (Item item : items()) {
            if (item.key().equals(key)) {
                return item;
            }
        }
        throw new NotFoundException("There is no open work " + key + ".");
    }

    /** Holds a code the module's own action checks for this step, and did not make the change it approves. */
    private static boolean canAct(AppUser user, Item item) {
        if (item.maker() != null && item.maker().equalsIgnoreCase(user.getUsername())) {
            return false;
        }
        for (String code : item.actors()) {
            if (PermissionCatalog.isKnown(code) && user.hasPermissionTo(code)) {
                return true;
            }
        }
        return false;
    }

    private void assign(Item item, String owner, String by, boolean supervisor) {
        if (item.source().ownedInModule()) {
            // A service case keeps its owner on the case itself, with its own history.
            serviceDesk.assignFromQueue(item.ref(), owner, by, supervisor);
        } else if (supervisor) {
            jdbc.update("delete from wf_assignment where item_key = ?", item.key());
            jdbc.update("insert into wf_assignment (item_key, owner, claimed_at, assigned_by) values (?, ?, ?, ?)",
                    item.key(), owner, LocalDateTime.now(), by);
        } else {
            try {
                jdbc.update("insert into wf_assignment (item_key, owner, claimed_at, assigned_by) values (?, ?, ?, ?)",
                        item.key(), owner, LocalDateTime.now(), by);
            } catch (DuplicateKeyException taken) {
                String current = jdbc.queryForObject("select owner from wf_assignment where item_key = ?",
                        String.class, item.key());
                if (owner.equals(current)) {
                    return;
                }
                throw new ConflictException("This work is already owned by " + current + ".");
            }
        }
        if (supervisor) {
            audit.record(by, "Work assigned", "Work item", item.key(), "Assigned to " + owner);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("key", item.key());
        event.put("workflow", item.workflow());
        event.put("ref", item.ref());
        event.put("owner", owner);
        event.put("assignedBy", by);
        outbox.publish(Events.TASK_ASSIGNED, item.key(), event, by);
    }

    /** The insurer's own staff: a desk role, and not a partner portal login. */
    private static boolean isStaff(AppUser user) {
        return user.isStaffUser() && !user.isPartnerLogin()
                && !(user.hasPermissionTo("USE_PARTNER_PORTAL") && !user.hasPermissionTo("READ_DASHBOARD"));
    }

    private AppUser staff() {
        AppUser user = security.authenticatedUser();
        if (!isStaff(user)) {
            throw new NoAuthorizationException("This is for the insurer's own staff.");
        }
        return user;
    }

    private static String status(Map<String, Object> item) {
        return String.valueOf(item.get("status"));
    }

    /** A row as a map with camelCase keys, dates as local java.time values. */
    private static Map<String, Object> row(ResultSet rs, int n) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String[] parts = meta.getColumnLabel(i).toLowerCase(Locale.ROOT).split("_");
            StringBuilder key = new StringBuilder(parts[0]);
            for (int p = 1; p < parts.length; p++) {
                key.append(Character.toUpperCase(parts[p].charAt(0))).append(parts[p].substring(1));
            }
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Timestamp t) {
                v = t.toLocalDateTime();
            } else if (v instanceof java.time.OffsetDateTime o) {
                v = o.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            } else if (v instanceof Long l && meta.getColumnType(i) == java.sql.Types.INTEGER) {
                v = l.intValue();
            }
            m.put(key.toString(), v);
        }
        return m;
    }
}

package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.otp.OneTimeCodes;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The party master workspace (SH01): duplicate detection and steward-approved merges, KYC/AML screening and
 * verification, addresses, identifiers and contact changes, dated roles and delegations, and the customer view.
 *
 * <ul>
 *   <li><b>Duplicates</b> (NS-SH01-002): deterministic matches (identity number, email, name with date of birth) and
 *       a configurable fuzzy name match ({@code nsure.party.match.name-similarity}, Jaro-Winkler). A name alone is never
 *       more than "possible" (AT-SH01-01).</li>
 *   <li><b>Merge</b> (AT-SH01-02): one person asks with a reason, a data steward (APPROVE_PARTY_MERGE) who is someone
 *       else approves. Every transaction reference moves to the surviving record; the merged record stays, marked
 *       "Merged into", and its reference resolves to the survivor.</li>
 *   <li><b>KYC</b> (NS-SH01-004): screening by the (simulated) provider is CLEAR, HIT or PROVIDER_ERROR. A hit waits for
 *       an authorised review by someone other than who screened; a provider error is never clearance. A verification
 *       records its source and reference and expires after {@code nsure.kyc.review-years}; the periodic review is in
 *       the work queue before then.</li>
 *   <li><b>Contacts</b> (NS-SH01-005): an address change applies at once (PartyUpdated); an email or phone change is
 *       proved with a one-time code sent to the new contact (ContactVerified) and applied only once someone other than
 *       the requester approves it.</li>
 *   <li><b>Relationships</b> (NS-SH01-003): roles that follow from transactions are derived; others, and delegations
 *       with an authority and an end date, are recorded. A delegation that has ended gives no portal access
 *       (AT-SH01-03).</li>
 * </ul>
 */
@Service
public class PartyService {

    /** Who screens customers until the insurer connects its provider (D12): the simulated watchlist. */
    static final String SIMULATED_PROVIDER = "Simulated screening provider";
    static final Set<String> SOURCES = Set.of("DOCUMENT_REVIEW", "IN_PERSON", "ELECTRONIC", "STAFF_REVIEW");
    static final List<String> ROLES = List.of("OWNER", "INSURED", "PAYER", "BENEFICIARY", "CLAIMANT", "INTERMEDIARY",
            "EMPLOYER", "LENDER", "DELEGATE");
    static final List<String> AUTHORITIES = List.of("VIEW", "SERVICE", "CLAIM");
    static final List<String> IDENTIFIER_TYPES = List.of(PartyRegister.OMANG, PartyRegister.PASSPORT,
            "Birth certificate", "Driver's licence", "Residence permit");
    private static final Set<String> CONTACT_FIELDS = Set.of("EMAIL", "PHONE");

    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final EvidenceChain audit;
    private final Outbox outbox;
    private final PartyRegister parties;
    private final WorkflowHistory history;
    private final OneTimeCodes codes;
    private final double nameSimilarity;
    private final int reviewYears;
    /** Screening during a KYC decision is kept even when the decision is then refused (a hit or a provider error). */
    private final org.springframework.transaction.support.TransactionTemplate separately;

    public PartyService(EntityManager em, JdbcTemplate jdbc, PlatformSecurityContext security, EvidenceChain audit,
            Outbox outbox, PartyRegister parties, WorkflowHistory history, OneTimeCodes codes,
            org.springframework.transaction.PlatformTransactionManager transactions,
            @Value("${nsure.party.match.name-similarity:0.9}") double nameSimilarity,
            @Value("${nsure.kyc.review-years:5}") int reviewYears) {
        this.separately = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.separately.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.em = em;
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.outbox = outbox;
        this.parties = parties;
        this.history = history;
        this.codes = codes;
        this.nameSimilarity = nameSimilarity;
        this.reviewYears = reviewYears;
    }

    // ── duplicates (NS-SH01-002, AT-SH01-01) ─────────────────────────────────────────────────────────

    /** Pairs of active customers that may be the same person, with why and how sure; a pending merge is shown. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> duplicates() {
        user("READ_CUSTOMER");
        List<CustomerAccount> all = em.createQuery("select c from CustomerAccount c order by c.ref", CustomerAccount.class)
                .getResultList().stream().filter(c -> !merged(c)).toList();
        Map<String, String> pending = new LinkedHashMap<>();
        jdbc.query("select record_ref, surviving_ref, merged_ref from pty_merge_request where status = 'PENDING'",
                rs -> {
                    pending.put(pair(rs.getString(2), rs.getString(3)), rs.getString(1));
                });
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Map<String, Object> match = match(all.get(i), all.get(j));
                if (match != null) {
                    match.put("pendingRequest", pending.get(pair(all.get(i).getRef(), all.get(j).getRef())));
                    pairs.add(match);
                }
            }
        }
        pairs.sort((a, b) -> "LIKELY".equals(a.get("confidence")) == "LIKELY".equals(b.get("confidence")) ? 0
                : "LIKELY".equals(a.get("confidence")) ? -1 : 1);
        return pairs;
    }

    /** Why two customers may be one person, or null. Deterministic rules first, then the fuzzy name match. */
    Map<String, Object> match(CustomerAccount a, CustomerAccount b) {
        List<String> reasons = new ArrayList<>();
        boolean strong = false;
        if (same(compact(a.getPoiNumber()), compact(b.getPoiNumber()))) {
            reasons.add("Same identity number");
            strong = true;
        }
        if (same(lower(a.getEmail()), lower(b.getEmail()))) {
            reasons.add("Same email");
            strong = true;
        }
        boolean phone = same(digits(a.getPhone()), digits(b.getPhone()));
        if (phone) {
            reasons.add("Same phone");
        }
        String na = normal(a.getName());
        String nb = normal(b.getName());
        boolean exactName = same(na, nb);
        double similarity = exactName ? 1.0 : jaroWinkler(na, nb);
        boolean fuzzyName = !exactName && similarity >= nameSimilarity;
        boolean dob = a.getDateOfBirth() != null && a.getDateOfBirth().equals(b.getDateOfBirth());
        if (exactName) {
            reasons.add("Same name");
        } else if (fuzzyName) {
            reasons.add(String.format(Locale.ROOT, "Similar name (%.0f%% match)", similarity * 100));
        }
        if ((exactName || fuzzyName) && dob) {
            reasons.add("Same date of birth");
            strong = true;
        }
        if (reasons.isEmpty() || (!exactName && !fuzzyName && reasons.equals(List.of("Same phone")))) {
            return null;
        }
        for (CustomerAccount c : List.of(a, b)) {
            if (c.getDuplicateReview() != null && !c.getDuplicateReview().isBlank()) {
                reasons.add(c.getRef() + " referred when created: " + c.getDuplicateReview());
            }
        }
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("first", party(a));
        pair.put("second", party(b));
        pair.put("reasons", reasons);
        pair.put("nameSimilarity", Math.round(similarity * 100) / 100.0);
        // AT-SH01-01: a matching (or similar) name alone is only "possible": a steward's judgement, never a rule.
        pair.put("confidence", strong ? "LIKELY" : "POSSIBLE");
        return pair;
    }

    // ── merge (AT-SH01-02) ───────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mergeRequests() {
        user("READ_CUSTOMER");
        return jdbc.query("select * from pty_merge_request order by id desc", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("ref", rs.getString("record_ref"));
            m.put("survivingRef", rs.getString("surviving_ref"));
            m.put("mergedRef", rs.getString("merged_ref"));
            m.put("reason", rs.getString("reason"));
            m.put("matchDetail", blank(rs.getString("match_detail")));
            m.put("status", rs.getString("status"));
            m.put("requestedBy", rs.getString("requested_by"));
            m.put("requestedAt", String.valueOf(rs.getTimestamp("requested_at").toLocalDateTime()));
            m.put("decidedBy", blank(rs.getString("decided_by")));
            m.put("decidedAt", rs.getTimestamp("decided_at") == null ? ""
                    : String.valueOf(rs.getTimestamp("decided_at").toLocalDateTime()));
            m.put("decisionNote", blank(rs.getString("decision_note")));
            m.put("movedDetail", blank(rs.getString("moved_detail")));
            return m;
        });
    }

    /**
     * {survivingRef, mergedRef, reason}: asks a data steward to merge two records. Nothing moves until someone else
     * holding APPROVE_PARTY_MERGE approves.
     */
    @Transactional
    public Map<String, Object> requestMerge(JsonNode body) {
        AppUser user = user("KYC_CUSTOMER");
        CustomerAccount keep = customer(text(body, "survivingRef"));
        CustomerAccount gone = customer(text(body, "mergedRef"));
        String reason = text(body, "reason");
        if (keep.getRef().equals(gone.getRef())) {
            throw new IllegalArgumentException("Choose two different records.");
        }
        if (reason == null || reason.length() < 10) {
            throw new IllegalArgumentException("Say why these are the same party (at least 10 characters).");
        }
        if (merged(keep) || merged(gone)) {
            throw new ConflictException((merged(keep) ? keep : gone).getRef() + " has already been merged.");
        }
        Integer open = jdbc.queryForObject("select count(*) from pty_merge_request where status = 'PENDING' and"
                + " ((surviving_ref = ? and merged_ref = ?) or (surviving_ref = ? and merged_ref = ?))", Integer.class,
                keep.getRef(), gone.getRef(), gone.getRef(), keep.getRef());
        if (open != null && open > 0) {
            throw new ConflictException("A merge of these two records is already waiting for a data steward.");
        }
        Map<String, Object> match = match(keep, gone);
        String detail = match == null ? "No automatic match: requested on the requester's judgement"
                : match.get("confidence") + ": " + String.join(", ", castList(match.get("reasons")));
        String ref = nextRef("MRG-", "pty_merge_request");
        jdbc.update("insert into pty_merge_request (record_ref, surviving_ref, merged_ref, reason, match_detail, status,"
                + " requested_by, requested_at) values (?, ?, ?, ?, ?, 'PENDING', ?, ?)", ref, keep.getRef(),
                gone.getRef(), cut(reason, 500), cut(detail, 500), user.getUsername(), LocalDateTime.now());
        audit.record(user.getUsername(), "Party merge requested", "Customer", keep.getRef(), gone.getRef()
                + " into " + keep.getRef() + ": " + reason);
        return request(ref);
    }

    /** A data steward approves: both records are locked, every reference moves, and the merge is registered. */
    @Transactional
    public Map<String, Object> approveMerge(String ref, JsonNode body) {
        AppUser steward = user("APPROVE_PARTY_MERGE");
        Map<String, Object> request = pendingRequest(ref);
        if (steward.getUsername().equalsIgnoreCase((String) request.get("requestedBy"))) {
            throw new NoAuthorizationException("You asked for this merge, so another data steward approves it.");
        }
        CustomerAccount keep = em.find(CustomerAccount.class, customer((String) request.get("survivingRef")).getId(),
                LockModeType.PESSIMISTIC_WRITE);
        CustomerAccount gone = em.find(CustomerAccount.class, customer((String) request.get("mergedRef")).getId(),
                LockModeType.PESSIMISTIC_WRITE);
        if (merged(keep) || merged(gone)) {
            throw new ConflictException("One of the records has been merged meanwhile; reject this request.");
        }
        Map<String, Integer> moved = new LinkedHashMap<>();
        moved.put("applications, policies and claims", parties.moveRecords(gone, keep));
        moved.putAll(moveReferences(gone, keep));
        int total = moved.values().stream().mapToInt(Integer::intValue).sum();
        keep.setPolicyCount(keep.getPolicyCount() + gone.getPolicyCount());
        gone.setPolicyCount(0);
        gone.setStatus("Merged into " + keep.getRef());
        String note = text(body, "note");
        StringBuilder detail = new StringBuilder();
        moved.forEach((k, v) -> {
            if (v > 0) {
                detail.append(detail.length() == 0 ? "" : ", ").append(v).append(' ').append(k);
            }
        });
        jdbc.update("insert into pty_merge (surviving_ref, merged_ref, merged_name, reason, records_moved, merged_by,"
                + " merged_at) values (?, ?, ?, ?, ?, ?, ?)", keep.getRef(), gone.getRef(), gone.getName(),
                cut(request.get("reason") + " (requested by " + request.get("requestedBy") + ", approved by "
                        + steward.getUsername() + ")", 500), total, steward.getUsername(), LocalDateTime.now());
        jdbc.update("update pty_merge_request set status = 'APPROVED', decided_by = ?, decided_at = ?, decision_note = ?,"
                + " moved_detail = ? where record_ref = ?", steward.getUsername(), LocalDateTime.now(), cut(note, 500),
                cut(detail.length() == 0 ? "No records to move" : detail.toString(), 1000), ref);
        audit.record(steward.getUsername(), "Parties merged", "Customer", keep.getRef(), gone.getRef() + " merged in ("
                + ref + ", requested by " + request.get("requestedBy") + "): " + detail);
        parties.merged(keep, gone, total, steward.getUsername());
        em.flush();
        return request(ref);
    }

    @Transactional
    public Map<String, Object> rejectMerge(String ref, JsonNode body) {
        AppUser steward = user("APPROVE_PARTY_MERGE");
        Map<String, Object> request = pendingRequest(ref);
        String note = text(body, "note");
        if (note == null || note.length() < 5) {
            throw new IllegalArgumentException("Say why the records are not merged (at least 5 characters).");
        }
        if (steward.getUsername().equalsIgnoreCase((String) request.get("requestedBy"))) {
            throw new NoAuthorizationException("You asked for this merge, so another data steward decides it.");
        }
        jdbc.update("update pty_merge_request set status = 'REJECTED', decided_by = ?, decided_at = ?, decision_note = ?"
                + " where record_ref = ?", steward.getUsername(), LocalDateTime.now(), cut(note, 500), ref);
        audit.record(steward.getUsername(), "Party merge rejected", "Customer", (String) request.get("survivingRef"),
                request.get("mergedRef") + " stays separate: " + note);
        return request(ref);
    }

    /** The other tables that hold a customer reference, moved under the merge's locks on both records. */
    private Map<String, Integer> moveReferences(CustomerAccount gone, CustomerAccount keep) {
        Map<String, Integer> moved = new LinkedHashMap<>();
        moved.put("receipts", jdbc.update("update ins_receipt set customer_ref = ?, customer_name = ? where customer_ref"
                + " = ?", keep.getRef(), keep.getName(), gone.getRef()));
        moved.put("payment transactions", jdbc.update("update payment_transaction set customer_ref = ? where"
                + " customer_ref = ?", keep.getRef(), gone.getRef()));
        moved.put("premium payments", jdbc.update("update premium_payment set customer_ref = ? where customer_ref = ?",
                keep.getRef(), gone.getRef()));
        moved.put("instalments", jdbc.update("update bil_instalment set customer_ref = ?, row_version = row_version + 1"
                + " where customer_ref = ?", keep.getRef(), gone.getRef()));
        moved.put("quotes", jdbc.update("update nb_quote set customer_ref = ?, customer_name = ? where customer_ref = ?",
                keep.getRef(), keep.getName(), gone.getRef()));
        moved.put("underwriting cases", jdbc.update("update uw_case set customer_ref = ?, customer_name = ?,"
                + " row_version = row_version + 1 where customer_ref = ?", keep.getRef(), keep.getName(), gone.getRef()));
        moved.put("credit loans", jdbc.update("update crd_loan set customer_ref = ?, row_version = row_version + 1"
                + " where customer_ref = ?", keep.getRef(), gone.getRef()));
        moved.put("service cases", jdbc.update("update svc_case set customer_ref = ?, customer_name = ? where"
                + " customer_ref = ?", keep.getRef(), keep.getName(), gone.getRef()));
        moved.put("messages", jdbc.update("update com_message set customer_ref = ? where customer_ref = ?",
                keep.getRef(), gone.getRef()));
        moved.put("inbound messages", jdbc.update("update com_inbound set customer_ref = ? where customer_ref = ?",
                keep.getRef(), gone.getRef()));
        moved.put("identity documents", jdbc.update("update pty_identity_document set customer_ref = ? where"
                + " customer_ref = ?", keep.getRef(), gone.getRef()));
        moved.put("addresses", jdbc.update("update pty_address set customer_ref = ? where customer_ref = ?",
                keep.getRef(), gone.getRef()));
        moved.put("identifiers", jdbc.update("update pty_identifier set customer_ref = ? where customer_ref = ?",
                keep.getRef(), gone.getRef()));
        moved.put("relationships", jdbc.update("update pty_relationship set party_ref = ? where party_ref = ?",
                keep.getRef(), gone.getRef()) + jdbc.update("update pty_relationship set related_ref = ? where"
                + " related_kind = 'CUSTOMER' and related_ref = ?", keep.getRef(), gone.getRef()));
        // A channel choice moves only where the survivor has none for that channel; the survivor's own choice stands.
        moved.put("channel preferences", jdbc.update("update com_preference set customer_ref = ? where customer_ref = ?"
                + " and channel not in (select p.channel from (select channel from com_preference where customer_ref = ?)"
                + " p)", keep.getRef(), gone.getRef(), keep.getRef()));
        return moved;
    }

    /** Where a customer reference leads now: itself, or the record it was merged into (AT-SH01-02). */
    @Transactional(readOnly = true)
    public Map<String, Object> resolve(String ref) {
        user("READ_CUSTOMER");
        String current = ref == null ? "" : ref.trim().toUpperCase(Locale.ROOT);
        List<String> chain = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            List<String> next = jdbc.queryForList("select surviving_ref from pty_merge where merged_ref = ?",
                    String.class, current);
            if (next.isEmpty()) {
                break;
            }
            chain.add(current);
            current = next.get(0);
        }
        CustomerAccount found = customer(current);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", ref);
        out.put("ref", found.getRef());
        out.put("name", found.getName());
        out.put("merged", !chain.isEmpty());
        out.put("mergedFrom", chain);
        return out;
    }

    // ── KYC / AML (NS-SH01-004) ──────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> kyc(String ref) {
        user("READ_CUSTOMER");
        return kycView(customer(ref));
    }

    /** Screens the customer with the (simulated) provider. A hit goes to authorised review; an error clears nothing. */
    @Transactional
    public Map<String, Object> screen(String ref) {
        AppUser user = user("KYC_CUSTOMER");
        CustomerAccount c = customer(ref);
        if (merged(c)) {
            throw new ConflictException(c.getRef() + " was merged into another record; screen that one.");
        }
        screenNow(c, user.getUsername());
        return kycView(c);
    }

    /** {decision: CLEARED | CONFIRMED, reason}: the authorised review of a screening hit, by someone else. */
    @Transactional
    public Map<String, Object> reviewHit(String ref, JsonNode body) {
        AppUser reviewer = user("EDD_CUSTOMER");
        CustomerAccount c = customer(ref);
        Map<String, Object> k = kycRow(c.getRef());
        if (k == null || !"HIT".equals(k.get("screening_status"))) {
            throw new IllegalArgumentException(c.getRef() + " has no screening hit waiting for review.");
        }
        if (reviewer.getUsername().equalsIgnoreCase((String) k.get("screened_by"))) {
            throw new NoAuthorizationException("You ran this screening, so someone else reviews its hit.");
        }
        String decision = upper(text(body, "decision"));
        if (!"CLEARED".equals(decision) && !"CONFIRMED".equals(decision)) {
            throw new IllegalArgumentException("Decision must be CLEARED (a false match) or CONFIRMED.");
        }
        String reason = text(body, "reason");
        if (reason == null || reason.length() < 10) {
            throw new IllegalArgumentException("Give the reason for the decision (at least 10 characters).");
        }
        String status = "CLEARED".equals(decision) ? "HIT_CLEARED" : "HIT_CONFIRMED";
        jdbc.update("update pty_kyc set screening_status = ?, hit_reviewed_by = ?, hit_reviewed_at = ?, hit_reason = ?,"
                + " updated_at = ? where customer_ref = ?", status, reviewer.getUsername(), LocalDateTime.now(),
                cut(reason, 500), LocalDateTime.now(), c.getRef());
        history.record(WorkflowHistory.KYC_REVIEW, c.getRef(), c.getName(), "CLEARED".equals(decision)
                ? "Screening hit cleared" : "Screening hit confirmed", "HIT", status, reason, reviewer.getUsername());
        audit.record(reviewer.getUsername(), "Screening hit reviewed", "Customer", c.getRef(), status + ": " + reason);
        if ("HIT_CONFIRMED".equals(status) && !"FAILED".equals(c.getKycStatus())) {
            String previous = c.getKycStatus();
            c.setKycStatus("FAILED");
            c.setStatus("KYC failed");
            c.setKycRejectionReason("We could not complete our checks. Please contact us.");
            parties.kycChanged(c, "KYC", previous, "FAILED", reviewer.getUsername());
        }
        return kycView(c);
    }

    /**
     * Called by the KYC review before a customer is marked verified: screening must be clear (it is run now if it
     * never was, and again after a provider error); then the verification source, reference and expiry are recorded.
     * Throws when the customer may not be verified yet.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void beforeVerified(CustomerAccount c, JsonNode body, String actor) {
        Map<String, Object> k = kycRow(c.getRef());
        String status = k == null ? null : (String) k.get("screening_status");
        if (status == null || "PROVIDER_ERROR".equals(status) || "NOT_SCREENED".equals(status)) {
            status = separately.execute(tx -> screenNow(c, actor));
        }
        switch (status) {
            case "CLEAR", "HIT_CLEARED" -> {
            }
            case "HIT" -> throw new ConflictException(c.getName() + " matched a screening list. An authorised"
                    + " reviewer must clear the match before KYC can be verified.");
            case "HIT_CONFIRMED" -> throw new ConflictException(c.getName() + "'s screening match was confirmed, so KYC"
                    + " cannot be verified.");
            default -> throw new ConflictException("Screening could not be completed (the provider did not answer)."
                    + " That is not a clearance: screen again before verifying.");
        }
        String source = upper(text(body, "source"));
        source = source == null ? "STAFF_REVIEW" : source;
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("Verification source must be one of " + String.join(", ", SOURCES) + ".");
        }
        String reference = text(body, "sourceReference");
        if (reference == null) {
            List<String> docs = jdbc.queryForList("select concat(document_type, concat(' ', file_name)) from"
                    + " pty_identity_document where customer_ref = ? order by id desc", String.class, c.getRef());
            reference = docs.isEmpty() ? null : "Identity evidence on file: " + docs.get(0);
        }
        LocalDate expires = LocalDate.now().plusYears(reviewYears);
        jdbc.update("update pty_kyc set verification_source = ?, verification_reference = ?, verified_by = ?,"
                + " verified_at = ?, expires_on = ?, review_due_at = ?, updated_at = ? where customer_ref = ?", source,
                cut(reference, 100), actor, LocalDateTime.now(), expires, expires.minusDays(30).atStartOfDay(),
                LocalDateTime.now(), c.getRef());
    }

    private String screenNow(CustomerAccount c, String actor) {
        String status;
        String detail;
        String reference = "SCR-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Map<String, Object> hit = watchlistHit(c.getName());
        if (hit != null && "UNAVAILABLE".equals(hit.get("list_name"))) {
            status = "PROVIDER_ERROR";
            detail = "The screening provider did not answer (" + hit.get("detail") + "). Not a clearance.";
            reference = null;
        } else if (hit != null) {
            status = "HIT";
            detail = hit.get("list_name") + " list match on \"" + hit.get("full_name") + "\" (" + hit.get("score")
                    + "% name match): " + hit.get("detail");
        } else {
            status = "CLEAR";
            detail = "No match on the simulated PEP and sanctions lists";
        }
        LocalDateTime now = LocalDateTime.now();
        if (kycRow(c.getRef()) == null) {
            jdbc.update("insert into pty_kyc (customer_ref, screening_status, screening_provider, screening_reference,"
                    + " screening_detail, screened_by, screened_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    c.getRef(), status, SIMULATED_PROVIDER, reference, cut(detail, 500), actor, now, now);
        } else {
            jdbc.update("update pty_kyc set screening_status = ?, screening_provider = ?, screening_reference = ?,"
                    + " screening_detail = ?, screened_by = ?, screened_at = ?, hit_reviewed_by = null,"
                    + " hit_reviewed_at = null, hit_reason = null, updated_at = ? where customer_ref = ?", status,
                    SIMULATED_PROVIDER, reference, cut(detail, 500), actor, now, now, c.getRef());
        }
        history.record(WorkflowHistory.KYC_REVIEW, c.getRef(), c.getName(), "Screened", c.getKycStatus(),
                c.getKycStatus(), status + " · " + detail, actor);
        audit.record(actor, "Customer screened", "Customer", c.getRef(), status);
        return status;
    }

    /** The best simulated-watchlist match for a name at or above the similarity threshold, or null. */
    private Map<String, Object> watchlistHit(String name) {
        String wanted = normal(name);
        Map<String, Object> best = null;
        double bestScore = 0;
        for (Map<String, Object> row : jdbc.queryForList("select full_name, list_name, detail from pty_watchlist")) {
            double score = jaroWinkler(wanted, normal((String) row.get("full_name")));
            if (score >= Math.max(nameSimilarity, 0.92) && score > bestScore) {
                bestScore = score;
                best = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    best.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                }
                best.put("score", Math.round(score * 100));
            }
        }
        return best;
    }

    private Map<String, Object> kycRow(String ref) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from pty_kyc where customer_ref = ?", ref);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        rows.get(0).forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
        return out;
    }

    private Map<String, Object> kycView(CustomerAccount c) {
        Map<String, Object> k = kycRow(c.getRef());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerRef", c.getRef());
        out.put("kycStatus", c.getKycStatus());
        out.put("eddStatus", blank(c.getEddStatus()));
        out.put("screeningStatus", k == null ? "NOT_SCREENED" : k.get("screening_status"));
        out.put("screeningProvider", k == null ? "" : blank((String) k.get("screening_provider")));
        out.put("screeningReference", k == null ? "" : blank((String) k.get("screening_reference")));
        out.put("screeningDetail", k == null ? "" : blank((String) k.get("screening_detail")));
        out.put("screenedBy", k == null ? "" : blank((String) k.get("screened_by")));
        out.put("screenedAt", k == null ? "" : time(k.get("screened_at")));
        out.put("hitReviewedBy", k == null ? "" : blank((String) k.get("hit_reviewed_by")));
        out.put("hitReason", k == null ? "" : blank((String) k.get("hit_reason")));
        out.put("verificationSource", k == null ? "" : blank((String) k.get("verification_source")));
        out.put("verificationReference", k == null ? "" : blank((String) k.get("verification_reference")));
        out.put("verifiedBy", k == null ? "" : blank((String) k.get("verified_by")));
        out.put("verifiedAt", k == null ? "" : time(k.get("verified_at")));
        Object expires = k == null ? null : k.get("expires_on");
        LocalDate expiry = expires instanceof java.sql.Date d ? d.toLocalDate() : expires instanceof LocalDate l ? l : null;
        out.put("expiresOn", expiry == null ? "" : expiry.toString());
        out.put("reviewDue", expiry != null && "VERIFIED".equals(c.getKycStatus())
                && !LocalDate.now().isBefore(expiry.minusDays(30)));
        out.put("expired", expiry != null && LocalDate.now().isAfter(expiry));
        out.put("documents", jdbc.queryForList("select id, document_type, file_name, uploaded_at from"
                + " pty_identity_document where customer_ref = ? order by id", c.getRef()).size());
        out.put("verificationSources", SOURCES.stream().sorted().toList());
        return out;
    }

    // ── addresses, identifiers, contact changes (NS-SH01-001, NS-SH01-005) ───────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> contacts(String ref) {
        user("READ_CUSTOMER");
        CustomerAccount c = customer(ref);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerRef", c.getRef());
        out.put("email", c.getEmail());
        out.put("phone", c.getPhone());
        out.put("addresses", jdbc.queryForList("select id, kind, line1, line2, city, country, postal_code, valid_from,"
                + " valid_to, verification, created_by, created_at from pty_address where customer_ref = ? order by"
                + " valid_from desc, id desc", c.getRef()).stream().map(PartyService::camel).toList());
        out.put("identifiers", identifiers(c));
        out.put("identifierTypes", IDENTIFIER_TYPES);
        out.put("changes", jdbc.queryForList("select id, record_ref, field, old_masked, new_value, reason, channel,"
                + " status, requested_by, requested_at, verified_at, decided_by, decided_at, decision_note from"
                + " pty_contact_change where customer_ref = ? order by id desc", c.getRef()).stream()
                .map(PartyService::camel).map(m -> {
                    m.put("newValue", mask(String.valueOf(m.get("newValue"))));
                    return m;
                }).toList());
        return out;
    }

    /** {kind: RESIDENTIAL | POSTAL, line1, line2, city, country, postalCode, validFrom, verification}. */
    @Transactional
    public Map<String, Object> addAddress(String ref, JsonNode body) {
        AppUser user = user("UPDATE_CUSTOMER");
        CustomerAccount c = customer(ref);
        String kind = upper(text(body, "kind"));
        kind = kind == null ? "RESIDENTIAL" : kind;
        if (!Set.of("RESIDENTIAL", "POSTAL").contains(kind)) {
            throw new IllegalArgumentException("Address kind must be RESIDENTIAL or POSTAL.");
        }
        String line1 = required(text(body, "line1"), "Give the first line of the address.", 150);
        String city = required(text(body, "city"), "Give the town or city.", 80);
        String country = upper(text(body, "country"));
        country = country == null ? "BW" : country;
        if (!country.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("Country is a two-letter code, e.g. BW.");
        }
        LocalDate from = date(text(body, "validFrom"), LocalDate.now());
        String verification = upper(text(body, "verification"));
        verification = verification == null ? "UNVERIFIED" : verification;
        if (!Set.of("UNVERIFIED", "DOCUMENT", "CUSTOMER_DECLARED").contains(verification)) {
            throw new IllegalArgumentException("Verification must be UNVERIFIED, DOCUMENT or CUSTOMER_DECLARED.");
        }
        // The address it replaces stays in the history, ended the day before.
        jdbc.update("update pty_address set valid_to = ? where customer_ref = ? and kind = ? and valid_to is null and"
                + " valid_from < ?", from.minusDays(1), c.getRef(), kind, from);
        jdbc.update("insert into pty_address (customer_ref, kind, line1, line2, city, country, postal_code, valid_from,"
                + " verification, created_by, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", c.getRef(), kind,
                line1, cut(text(body, "line2"), 150), city, country, cut(text(body, "postalCode"), 20), from,
                verification, user.getUsername(), LocalDateTime.now());
        history.record(WorkflowHistory.KYC_REVIEW, c.getRef(), c.getName(), "Address recorded", null, null,
                kind.toLowerCase(Locale.ROOT) + " address from " + from + " (" + verification.toLowerCase(Locale.ROOT)
                        + ")", user.getUsername());
        updated(c, "ADDRESS", user.getUsername());
        return contacts(c.getRef());
    }

    /** {type, number, country, evidenceDocumentId}: an alternative identifier with its country of issue. */
    @Transactional
    public Map<String, Object> addIdentifier(String ref, JsonNode body) {
        AppUser user = user("UPDATE_CUSTOMER");
        CustomerAccount c = customer(ref);
        String type = text(body, "type");
        if (type == null || !IDENTIFIER_TYPES.contains(type)) {
            throw new IllegalArgumentException("Identifier type must be one of " + String.join(", ", IDENTIFIER_TYPES)
                    + ".");
        }
        String number;
        if (PartyRegister.OMANG.equals(type) || PartyRegister.PASSPORT.equals(type)) {
            number = parties.identity(type, text(body, "number")).number();
        } else {
            number = text(body, "number") == null ? "" : text(body, "number").replaceAll("\\s+", "")
                    .toUpperCase(Locale.ROOT);
            if (!number.matches("[A-Z0-9/-]{4,30}")) {
                throw new IllegalArgumentException("A " + type.toLowerCase(Locale.ROOT)
                        + " number is 4 to 30 letters, digits, / or -.");
            }
        }
        String country = upper(text(body, "country"));
        country = country == null ? "BW" : country;
        if (!country.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("Country of issue is a two-letter code, e.g. BW.");
        }
        if (PartyRegister.OMANG.equals(type) && !"BW".equals(country)) {
            throw new IllegalArgumentException("An Omang is issued in Botswana (BW).");
        }
        Long evidence = body != null && body.hasNonNull("evidenceDocumentId") ? body.get("evidenceDocumentId").asLong()
                : null;
        if (evidence != null) {
            Integer own = jdbc.queryForObject("select count(*) from pty_identity_document where id = ? and"
                    + " customer_ref = ?", Integer.class, evidence, c.getRef());
            if (own == null || own == 0) {
                throw new IllegalArgumentException("The evidence must be one of " + c.getName()
                        + "'s identity documents.");
            }
        }
        List<String> holders = jdbc.queryForList("select customer_ref from pty_identifier where id_type = ? and"
                + " id_number = ? and country = ?", String.class, type, number, country);
        if (!holders.isEmpty()) {
            throw new ConflictException(holders.get(0) + " already holds this " + type.toLowerCase(Locale.ROOT)
                    + ". Review them as possible duplicates.");
        }
        jdbc.update("insert into pty_identifier (customer_ref, id_type, id_number, country, evidence_document_id,"
                + " created_by, created_at) values (?, ?, ?, ?, ?, ?, ?)", c.getRef(), type, number, country, evidence,
                user.getUsername(), LocalDateTime.now());
        audit.record(user.getUsername(), "Identifier added", "Customer", c.getRef(), type + " issued in " + country);
        updated(c, "IDENTIFIER", user.getUsername());
        return contacts(c.getRef());
    }

    private List<Map<String, Object>> identifiers(CustomerAccount c) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (c.getPoiNumber() != null) {
            Map<String, Object> primary = new LinkedHashMap<>();
            primary.put("type", c.getPoiType());
            primary.put("number", mask(c.getPoiNumber()));
            primary.put("country", PartyRegister.OMANG.equals(c.getPoiType()) ? "BW" : "");
            primary.put("primary", true);
            out.add(primary);
        }
        for (Map<String, Object> row : jdbc.queryForList("select id_type, id_number, country, evidence_document_id,"
                + " created_by, created_at from pty_identifier where customer_ref = ? order by id", c.getRef())) {
            Map<String, Object> m = camel(row);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("type", m.get("idType"));
            view.put("number", mask(String.valueOf(m.get("idNumber"))));
            view.put("country", m.get("country"));
            view.put("evidenceDocumentId", m.get("evidenceDocumentId"));
            view.put("primary", false);
            view.put("createdBy", m.get("createdBy"));
            out.add(view);
        }
        return out;
    }

    /**
     * {field: EMAIL | PHONE, value, reason}: staff record a customer's request to change a contact. A one-time code is
     * sent to the new contact; once it is entered the change waits for a second person.
     */
    @Transactional
    public Map<String, Object> requestContactChange(String ref, JsonNode body) {
        AppUser user = user("UPDATE_CUSTOMER");
        return newContactChange(customer(ref), body, "STAFF", user.getUsername());
    }

    /** The same, asked by the signed-in customer from the portal. */
    @Transactional
    public Map<String, Object> requestOwnContactChange(CustomerAccount me, JsonNode body) {
        return newContactChange(me, body, "PORTAL", security.authenticatedUser().getUsername());
    }

    private Map<String, Object> newContactChange(CustomerAccount c, JsonNode body, String channel, String actor) {
        String field = upper(text(body, "field"));
        if (field == null || !CONTACT_FIELDS.contains(field)) {
            throw new IllegalArgumentException("Choose EMAIL or PHONE to change.");
        }
        String value = text(body, "value");
        if (value == null) {
            throw new IllegalArgumentException("Give the new " + field.toLowerCase(Locale.ROOT) + ".");
        }
        if ("EMAIL".equals(field) && !value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        if ("PHONE".equals(field) && digits(value).length() < 7) {
            throw new IllegalArgumentException("Enter a valid mobile number.");
        }
        String current = "EMAIL".equals(field) ? c.getEmail() : c.getPhone();
        if ("EMAIL".equals(field) ? value.equalsIgnoreCase(current) : digits(value).equals(digits(current))) {
            throw new IllegalArgumentException("That is already the " + field.toLowerCase(Locale.ROOT) + " on record.");
        }
        jdbc.update("update pty_contact_change set status = 'SUPERSEDED' where customer_ref = ? and field = ? and"
                + " status in ('AWAITING_CODE', 'AWAITING_APPROVAL')", c.getRef(), field);
        String ref = nextRef("CCH-", "pty_contact_change");
        jdbc.update("insert into pty_contact_change (record_ref, customer_ref, field, old_masked, new_value, reason,"
                + " channel, status, requested_by, requested_at) values (?, ?, ?, ?, ?, ?, ?, 'AWAITING_CODE', ?, ?)", ref,
                c.getRef(), field, mask(current), cut(value, 150), cut(text(body, "reason"), 300), channel, actor,
                LocalDateTime.now());
        OneTimeCodes.Issued issued = codes.issue(OneTimeCodes.Purpose.CONTACT_CHANGE, ref, value);
        history.record(WorkflowHistory.KYC_REVIEW, c.getRef(), c.getName(), "Contact change requested", null, null,
                field.toLowerCase(Locale.ROOT) + " change " + ref + " via " + channel.toLowerCase(Locale.ROOT)
                        + "; code sent to the new contact", actor);
        Map<String, Object> out = contactChange(ref);
        out.put("challenge", issued.challenge());
        out.put("codeExpiresAt", String.valueOf(issued.expiresAt()));
        if (issued.demoCode() != null) {
            out.put("demoCode", issued.demoCode());
        }
        return out;
    }

    /** {challenge, code}: proves the new contact; the change then waits for a second person (ContactVerified). */
    @Transactional
    public Map<String, Object> verifyContactChange(String ref, JsonNode body, CustomerAccount portalCustomer) {
        AppUser user = portalCustomer == null ? user("UPDATE_CUSTOMER") : security.authenticatedUser();
        Map<String, Object> change = contactChange(ref);
        if (portalCustomer != null && !portalCustomer.getRef().equals(change.get("customerRef"))) {
            throw new NotFoundException("Contact change " + ref + " not found");
        }
        if (!"AWAITING_CODE".equals(change.get("status"))) {
            throw new IllegalArgumentException(ref + " is " + change.get("status") + ", not waiting for a code.");
        }
        codes.verify(OneTimeCodes.Purpose.CONTACT_CHANGE, text(body, "challenge"), text(body, "code"), ref);
        jdbc.update("update pty_contact_change set status = 'AWAITING_APPROVAL', verified_at = ? where record_ref = ?",
                LocalDateTime.now(), ref);
        CustomerAccount c = customer((String) change.get("customerRef"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerRef", c.getRef());
        data.put("changeRef", ref);
        data.put("field", change.get("field"));
        outbox.publish(Events.CONTACT_VERIFIED, c.getRef(), data, user.getUsername());
        history.record(WorkflowHistory.KYC_REVIEW, c.getRef(), c.getName(), "Contact verified", null, null,
                ref + ": the code sent to the new " + String.valueOf(change.get("field")).toLowerCase(Locale.ROOT)
                        + " was entered; waiting for approval", user.getUsername());
        return contactChange(ref);
    }

    /** A second person approves a verified change; it is applied to the customer record (PartyUpdated). */
    @Transactional
    public Map<String, Object> decideContactChange(String ref, boolean approve, JsonNode body) {
        AppUser checker = user("KYC_CUSTOMER");
        Map<String, Object> change = contactChange(ref);
        if (!"AWAITING_APPROVAL".equals(change.get("status"))) {
            throw new IllegalArgumentException(ref + " is " + change.get("status") + ", not waiting for approval.");
        }
        if (checker.getUsername().equalsIgnoreCase((String) change.get("requestedBy"))) {
            throw new NoAuthorizationException("You recorded this change, so another person approves it.");
        }
        String note = text(body, "note");
        if (!approve && (note == null || note.length() < 5)) {
            throw new IllegalArgumentException("Say why the change is rejected (at least 5 characters).");
        }
        CustomerAccount c = em.find(CustomerAccount.class, customer((String) change.get("customerRef")).getId(),
                LockModeType.PESSIMISTIC_WRITE);
        String value = jdbc.queryForObject("select new_value from pty_contact_change where record_ref = ?", String.class,
                ref);
        if (approve) {
            if ("EMAIL".equals(change.get("field"))) {
                c.setEmail(value);
            } else {
                c.setPhone(value);
            }
        }
        jdbc.update("update pty_contact_change set status = ?, decided_by = ?, decided_at = ?, decision_note = ? where"
                + " record_ref = ?", approve ? "APPLIED" : "REJECTED", checker.getUsername(), LocalDateTime.now(),
                cut(note, 300), ref);
        history.record(WorkflowHistory.KYC_REVIEW, c.getRef(), c.getName(), approve ? "Contact change approved"
                : "Contact change rejected", null, null, ref + (note == null ? "" : ": " + note), checker.getUsername());
        audit.record(checker.getUsername(), approve ? "Contact change approved" : "Contact change rejected", "Customer",
                c.getRef(), ref + " " + change.get("field") + " requested by " + change.get("requestedBy"));
        if (approve) {
            updated(c, String.valueOf(change.get("field")), checker.getUsername());
        }
        em.flush();
        return contactChange(ref);
    }

    private Map<String, Object> contactChange(String ref) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, record_ref, customer_ref, field, old_masked,"
                + " new_value, reason, channel, status, requested_by, requested_at, verified_at, decided_by, decided_at,"
                + " decision_note from pty_contact_change where record_ref = ?", ref == null ? "" : ref.toUpperCase(Locale.ROOT));
        if (rows.isEmpty()) {
            throw new NotFoundException("Contact change " + ref + " not found");
        }
        Map<String, Object> m = camel(rows.get(0));
        m.put("newValue", mask(String.valueOf(m.get("newValue"))));
        return m;
    }

    // ── relationships and delegation (NS-SH01-003, AT-SH01-03) ───────────────────────────────────────

    /** The party's roles: derived from transactions, and recorded relationships with dates and authority. */
    @Transactional(readOnly = true)
    public Map<String, Object> relationships(String ref) {
        user("READ_CUSTOMER");
        CustomerAccount c = customer(ref);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerRef", c.getRef());
        List<Map<String, Object>> derived = new ArrayList<>();
        for (Map<String, Object> p : jdbc.queryForList("select record_ref, product, status from ins_policy where"
                + " customer_ref = ? order by id", c.getRef())) {
            derived.add(role("OWNER", "POLICY", (String) p.get("record_ref"), p.get("product") + " · " + p.get("status")));
        }
        for (Map<String, Object> cl : jdbc.queryForList("select record_ref, policy_ref, status from ins_claim where"
                + " customer_ref = ? order by id", c.getRef())) {
            derived.add(role("CLAIMANT", "CLAIM", (String) cl.get("record_ref"), cl.get("policy_ref") + " · "
                    + cl.get("status")));
        }
        for (Map<String, Object> l : jdbc.queryForList("select record_ref, lender_ref, status from crd_loan where"
                + " customer_ref = ? order by id", c.getRef())) {
            derived.add(role("LENDER", "LOAN", (String) l.get("record_ref"), "Borrower of lender " + l.get("lender_ref")
                    + " · " + l.get("status")));
        }
        for (Map<String, Object> a : jdbc.queryForList("select a.policy_ref, i.record_ref, i.name, i.status from"
                + " dist_policy_assignment a join dist_intermediary i on i.id = a.intermediary_id join ins_policy p on"
                + " p.record_ref = a.policy_ref where p.customer_ref = ? and a.ended_on is null", c.getRef())) {
            derived.add(role("INTERMEDIARY", "POLICY", (String) a.get("policy_ref"), "Serviced by " + a.get("record_ref")
                    + " " + a.get("name") + " (" + a.get("status") + ")"));
        }
        out.put("derived", derived);
        out.put("recorded", recorded("party_ref = ? or (related_kind = 'CUSTOMER' and related_ref = ?)", c.getRef(),
                c.getRef()));
        out.put("roles", ROLES);
        out.put("authorities", AUTHORITIES);
        return out;
    }

    /**
     * {role, relatedKind: POLICY | CUSTOMER | SCHEME | LOAN, relatedRef, authority, validFrom, validTo, note}: a
     * recorded role or relationship of this party. A DELEGATE relationship lets this party act on the related
     * customer's policies (or one policy) with that authority until validTo; the related customer must own the policy.
     */
    @Transactional
    public Map<String, Object> addRelationship(String ref, JsonNode body) {
        AppUser user = user("UPDATE_CUSTOMER");
        CustomerAccount c = customer(ref);
        String role = upper(text(body, "role"));
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("Role must be one of " + String.join(", ", ROLES) + ".");
        }
        String kind = upper(text(body, "relatedKind"));
        if (kind == null || !Set.of("POLICY", "CUSTOMER", "SCHEME", "LOAN").contains(kind)) {
            throw new IllegalArgumentException("Related record must be a POLICY, CUSTOMER, SCHEME or LOAN.");
        }
        String related = upper(text(body, "relatedRef"));
        if (related == null) {
            throw new IllegalArgumentException("Give the related record's reference.");
        }
        Integer exists = switch (kind) {
            case "POLICY" -> jdbc.queryForObject("select count(*) from ins_policy where record_ref = ?", Integer.class,
                    related);
            case "CUSTOMER" -> jdbc.queryForObject("select count(*) from ins_customer where record_ref = ?",
                    Integer.class, related);
            case "SCHEME" -> jdbc.queryForObject("select count(*) from ins_scheme where record_ref = ?", Integer.class,
                    related);
            default -> jdbc.queryForObject("select count(*) from crd_loan where record_ref = ?", Integer.class, related);
        };
        if (exists == null || exists == 0) {
            throw new NotFoundException(kind.charAt(0) + kind.substring(1).toLowerCase(Locale.ROOT) + " " + related
                    + " not found");
        }
        if ("CUSTOMER".equals(kind) && related.equals(c.getRef())) {
            throw new IllegalArgumentException("A party cannot be related to itself.");
        }
        if ("DELEGATE".equals(role) && !Set.of("CUSTOMER", "POLICY").contains(kind)) {
            throw new IllegalArgumentException("A delegation is to act for a customer, or on one policy.");
        }
        String authority = upper(text(body, "authority"));
        authority = authority == null ? "VIEW" : authority;
        if (!AUTHORITIES.contains(authority)) {
            throw new IllegalArgumentException("Authority must be one of " + String.join(", ", AUTHORITIES) + ".");
        }
        LocalDate from = date(text(body, "validFrom"), LocalDate.now());
        LocalDate to = date(text(body, "validTo"), null);
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("The end date is before the start date.");
        }
        if ("DELEGATE".equals(role) && to == null) {
            throw new IllegalArgumentException("A delegation needs an end date (delegations expire).");
        }
        String note = text(body, "note");
        String relRef = nextRef("REL-", "pty_relationship");
        jdbc.update("insert into pty_relationship (record_ref, party_ref, role, related_kind, related_ref, authority,"
                + " valid_from, valid_to, note, created_by, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", relRef,
                c.getRef(), role, kind, related, authority, from, to, cut(note, 300), user.getUsername(),
                LocalDateTime.now());
        audit.record(user.getUsername(), "Party relationship recorded", "Customer", c.getRef(), relRef + " " + role
                + " of " + kind + " " + related + " (" + authority + ", " + from + " to " + (to == null ? "open" : to)
                + ")");
        updated(c, "RELATIONSHIP", user.getUsername());
        return relationships(c.getRef());
    }

    /** {reason}: ends a recorded relationship today; it stays in the history. */
    @Transactional
    public Map<String, Object> endRelationship(String relRef, JsonNode body) {
        AppUser user = user("UPDATE_CUSTOMER");
        List<Map<String, Object>> rows = jdbc.queryForList("select party_ref, valid_from from pty_relationship where"
                + " record_ref = ?", relRef);
        if (rows.isEmpty()) {
            throw new NotFoundException("Relationship " + relRef + " not found");
        }
        String reason = text(body, "reason");
        if (reason == null || reason.length() < 5) {
            throw new IllegalArgumentException("Say why the relationship ends (at least 5 characters).");
        }
        LocalDate today = LocalDate.now();
        jdbc.update("update pty_relationship set valid_to = ?, ended_by = ?, ended_at = ? where record_ref = ? and"
                + " (valid_to is null or valid_to > ?)", today.minusDays(1), user.getUsername(), LocalDateTime.now(),
                relRef, today.minusDays(1));
        String party = (String) camel(rows.get(0)).get("partyRef");
        audit.record(user.getUsername(), "Party relationship ended", "Customer", party, relRef + ": " + reason);
        updated(customer(party), "RELATIONSHIP", user.getUsername());
        return relationships(party);
    }

    /**
     * The policies the signed-in customer may act on for someone else today, by valid delegation (AT-SH01-03): an
     * expired or ended delegation gives nothing, whatever relationship history remains.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> delegatedTo(CustomerAccount me) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : recorded("party_ref = ? and role = 'DELEGATE' and valid_from <= ? and (valid_to is"
                + " null or valid_to >= ?)", me.getRef(), today, today)) {
            String kind = (String) d.get("relatedKind");
            String related = (String) d.get("relatedRef");
            List<Map<String, Object>> policies = "POLICY".equals(kind)
                    ? jdbc.queryForList("select record_ref, customer_ref, customer_name, product, status, amount, premium"
                            + " from ins_policy where record_ref = ?", related)
                    : jdbc.queryForList("select record_ref, customer_ref, customer_name, product, status, amount, premium"
                            + " from ins_policy where customer_ref = ? order by id", related);
            for (Map<String, Object> p : policies) {
                Map<String, Object> m = camel(p);
                m.put("authority", d.get("authority"));
                m.put("validTo", d.get("validTo"));
                m.put("relationshipRef", d.get("ref"));
                out.add(m);
            }
        }
        return out;
    }

    /** One policy the signed-in customer may see by delegation; 404 when no delegation covers it today. */
    @Transactional(readOnly = true)
    public Map<String, Object> delegatedPolicy(CustomerAccount me, String policyRef) {
        return delegatedTo(me).stream().filter(p -> p.get("recordRef").equals(policyRef)).findFirst()
                .orElseThrow(() -> new NotFoundException("Policy " + policyRef + " not found"));
    }

    /** The delegation authority the customer holds today over a policy, or null. */
    @Transactional(readOnly = true)
    public String delegatedAuthority(CustomerAccount me, String policyRef) {
        return delegatedTo(me).stream().filter(p -> p.get("recordRef").equals(policyRef)).map(p -> (String)
                p.get("authority")).findFirst().orElse(null);
    }

    private List<Map<String, Object>> recorded(String where, Object... args) {
        LocalDate today = LocalDate.now();
        return jdbc.queryForList("select * from pty_relationship where " + where + " order by valid_from desc, id desc",
                args).stream().map(r -> {
                    Map<String, Object> m = camel(r);
                    m.put("ref", m.remove("recordRef"));
                    LocalDate end = m.get("validTo") == null ? null : LocalDate.parse(String.valueOf(m.get("validTo")));
                    LocalDate start = m.get("validFrom") == null ? null
                            : LocalDate.parse(String.valueOf(m.get("validFrom")));
                    m.put("validFrom", start == null ? "" : start.toString());
                    m.put("validTo", end == null ? "" : end.toString());
                    m.put("current", (start == null || !start.isAfter(today)) && (end == null || !end.isBefore(today)));
                    return m;
                }).toList();
    }

    private static Map<String, Object> role(String role, String kind, String ref, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("relatedKind", kind);
        m.put("relatedRef", ref);
        m.put("detail", detail);
        return m;
    }

    // ── the customer view (NS-SH01-006) ──────────────────────────────────────────────────────────────

    /**
     * One customer's view, filtered by what the reader may see: each section is present only with the permission its
     * own page needs (policies READ_POLICY, premiums READ_PAYMENT, claims READ_CLAIM, messages READ_COMMUNICATION,
     * service cases and complaints READ_SERVICE_CASE). Identity numbers are masked. Records are linked by reference.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> view(String ref) {
        AppUser user = user("READ_CUSTOMER");
        CustomerAccount c = customer(ref);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("party", party(c));
        out.put("kyc", kycView(c));
        Map<String, Boolean> visible = new LinkedHashMap<>();
        visible.put("policies", user.hasPermissionTo("READ_POLICY"));
        visible.put("premiums", user.hasPermissionTo("READ_PAYMENT"));
        visible.put("claims", user.hasPermissionTo("READ_CLAIM"));
        visible.put("messages", user.hasPermissionTo("READ_COMMUNICATION"));
        visible.put("cases", user.hasPermissionTo("READ_SERVICE_CASE"));
        out.put("visible", visible);
        if (visible.get("policies")) {
            out.put("policies", jdbc.queryForList("select record_ref, product, status, amount, premium, record_date"
                    + " from ins_policy where customer_ref = ? order by id desc", c.getRef()).stream()
                    .map(PartyService::camel).toList());
            out.put("documents", jdbc.queryForList("select d.id, p.record_ref as policy_ref, d.document_type,"
                    + " d.file_name, d.status, d.uploaded_at from ins_policy_document d join ins_policy p on p.id ="
                    + " d.policy_id where p.customer_ref = ? order by d.id desc", c.getRef()).stream()
                    .map(PartyService::camel).toList());
        }
        if (visible.get("premiums")) {
            out.put("premiums", jdbc.queryForList("select record_ref, product, amount, status, record_date from"
                    + " ins_receipt where customer_ref = ? order by id desc", c.getRef()).stream()
                    .map(PartyService::camel).toList());
        }
        if (visible.get("claims")) {
            out.put("claims", jdbc.queryForList("select record_ref, policy_ref, product, amount, status, lodged_at from"
                    + " ins_claim where customer_ref = ? order by id desc", c.getRef()).stream()
                    .map(PartyService::camel).toList());
        }
        if (visible.get("messages")) {
            out.put("messages", jdbc.queryForList("select record_ref, channel, event_type, business_ref, status,"
                    + " created_at from com_message where customer_ref = ? order by id desc", c.getRef()).stream()
                    .limit(50).map(PartyService::camel).toList());
        }
        if (visible.get("cases")) {
            out.put("cases", jdbc.queryForList("select record_ref, case_type, category, subject, status, opened_at from"
                    + " svc_case where customer_ref = ? order by id desc", c.getRef()).stream()
                    .map(PartyService::camel).toList());
        }
        // Marketing choices are kept apart from service-message purposes, with their consent evidence.
        out.put("marketing", jdbc.queryForList("select channel, status, consent_source, consent_evidence, updated_by,"
                + " updated_at from com_purpose_preference where customer_ref = ? and purpose = 'MARKETING'",
                c.getRef()).stream().map(PartyService::camel).toList());
        out.put("mergedFrom", jdbc.queryForList("select merged_ref from pty_merge where surviving_ref = ?", String.class,
                c.getRef()));
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private void updated(CustomerAccount c, String what, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerRef", c.getRef());
        data.put("changed", what);
        outbox.publish(Events.PARTY_UPDATED, c.getRef(), data, actor);
    }

    private Map<String, Object> request(String ref) {
        return mergeRequests().stream().filter(r -> ref.equals(r.get("ref"))).findFirst()
                .orElseThrow(() -> new NotFoundException("Merge request " + ref + " not found"));
    }

    private Map<String, Object> pendingRequest(String ref) {
        Map<String, Object> request = request(ref == null ? "" : ref.toUpperCase(Locale.ROOT));
        if (!"PENDING".equals(request.get("status"))) {
            throw new IllegalArgumentException(ref + " is " + request.get("status") + ", not waiting for a decision.");
        }
        return request;
    }

    private String nextRef(String prefix, String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        int next = (n == null ? 0 : n) + 1;
        String ref;
        do {
            ref = String.format("%s%04d", prefix, next++);
        } while (jdbc.queryForObject("select count(*) from " + table + " where record_ref = ?", Integer.class, ref) > 0);
        return ref;
    }

    private CustomerAccount customer(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Give the customer reference, e.g. CUS-1001.");
        }
        return em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", ref.trim().toUpperCase(Locale.ROOT)).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + ref + " not found"));
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    static Map<String, Object> party(CustomerAccount c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", c.getRef());
        m.put("name", c.getName());
        m.put("partyType", "PERSON");
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone());
        m.put("poiType", c.getPoiType());
        m.put("poiNumber", mask(c.getPoiNumber()));
        m.put("dateOfBirth", c.getDateOfBirth() == null ? null : c.getDateOfBirth().toString());
        m.put("status", c.getStatus());
        m.put("kycStatus", c.getKycStatus());
        // The names the duplicate list has always used (Data & assistants > Parties).
        m.put("identityType", c.getPoiType());
        m.put("identityNumber", mask(c.getPoiNumber()));
        m.put("policies", c.getPolicyCount());
        return m;
    }

    private static boolean merged(CustomerAccount c) {
        return c.getStatus() != null && c.getStatus().startsWith("Merged");
    }

    private static String pair(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /** Jaro-Winkler similarity of two normalised names, 0..1. */
    static double jaroWinkler(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        int range = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] ma = new boolean[a.length()];
        boolean[] mb = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            for (int j = Math.max(0, i - range); j < Math.min(b.length(), i + range + 1); j++) {
                if (!mb[j] && a.charAt(i) == b.charAt(j)) {
                    ma[i] = true;
                    mb[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int t = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (ma[i]) {
                while (!mb[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    t++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / a.length() + m / b.length() + (m - t / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(a.length(), b.length())) && a.charAt(i) == b.charAt(i); i++) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    static String mask(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return "";
        }
        int at = value.indexOf('@');
        if (at > 0) {
            return value.substring(0, Math.min(2, at)) + "***" + value.substring(at);
        }
        String compact = value.replaceAll("\\s+", "");
        return compact.length() <= 3 ? "***" : "*".repeat(compact.length() - 3) + compact.substring(compact.length() - 3);
    }

    private static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            StringBuilder name = new StringBuilder();
            boolean up = false;
            for (char ch : k.toLowerCase(Locale.ROOT).toCharArray()) {
                if (ch == '_') {
                    up = true;
                } else {
                    name.append(up ? Character.toUpperCase(ch) : ch);
                    up = false;
                }
            }
            Object value = v instanceof java.sql.Timestamp ts ? ts.toLocalDateTime().toString()
                    : v instanceof java.sql.Date d ? d.toLocalDate().toString() : v;
            out.put(name.toString(), value);
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return value instanceof List<?> l ? (List<String>) l : List.of();
    }

    private static boolean same(String a, String b) {
        return a != null && !a.isBlank() && a.equals(b);
    }

    private static String compact(String value) {
        return value == null ? null : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    static String normal(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ")
                .trim();
    }

    private static String time(Object value) {
        return value instanceof java.sql.Timestamp ts ? ts.toLocalDateTime().toString()
                : value instanceof LocalDateTime l ? l.toString() : "";
    }

    private static LocalDate date(String value, LocalDate fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates look like 2026-12-31.");
        }
    }

    private static String required(String value, String message, int max) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        if (value.length() > max) {
            throw new IllegalArgumentException("Keep it to " + max + " characters.");
        }
        return value;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    static String text(JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}

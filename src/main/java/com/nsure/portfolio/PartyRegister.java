package com.nsure.portfolio;

import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Party identity rules (Nsure Life NS-SH01-001/002).
 * <ul>
 *   <li>An identity number is validated by its document type. The formats are configuration
 *       ({@code nsure.identity.omang-pattern}, {@code nsure.identity.passport-pattern}); the defaults are the
 *       conservative reading until the insurer confirms them: an Omang is 9 digits, a passport 6–20 letters and
 *       digits. Spaces are ignored and letters are stored in capitals.</li>
 *   <li>An identity number another active customer already holds is a likely duplicate. A customer signing up is
 *       refused. Staff are refused too unless they record why this is a different person; the record is then created
 *       referred for duplicate review, listed in the data steward's duplicate queue with that note.</li>
 *   <li>PartyCreated, KycStatusChanged and PartyMerged go through the outbox with the change.</li>
 * </ul>
 */
@Component
public class PartyRegister {

    public static final String OMANG = "Omang";
    public static final String PASSPORT = "Passport";
    static final String DEFAULT_OMANG = "[0-9]{9}";
    static final String DEFAULT_PASSPORT = "[A-Z0-9]{6,20}";
    /** What a customer signing up is told; it never names the other record. */
    static final String SIGNUP_DUPLICATE = "An account already exists for this identity number. Sign in to it, or"
            + " contact us if you did not create it.";

    private final EntityManager em;
    private final EvidenceChain audit;
    private final Outbox outbox;
    private final Pattern omang;
    private final Pattern passport;
    private final boolean defaultOmang;
    private final boolean defaultPassport;

    public PartyRegister(EntityManager em, EvidenceChain audit, Outbox outbox,
            @Value("${nsure.identity.omang-pattern:}") String omangPattern,
            @Value("${nsure.identity.passport-pattern:}") String passportPattern) {
        this.em = em;
        this.audit = audit;
        this.outbox = outbox;
        this.defaultOmang = omangPattern == null || omangPattern.isBlank();
        this.defaultPassport = passportPattern == null || passportPattern.isBlank();
        this.omang = Pattern.compile(defaultOmang ? DEFAULT_OMANG : omangPattern.trim());
        this.passport = Pattern.compile(defaultPassport ? DEFAULT_PASSPORT : passportPattern.trim());
    }

    /** A validated identity document: its type and the number as stored. */
    public record Identity(String type, String number) {
    }

    /**
     * Checks an identity number against its document type. Returns null when neither is given; a number needs its
     * type, and a type alone is kept without a number (older clients).
     */
    public Identity identity(String type, String number) {
        String t = type == null || type.isBlank() ? null : type.trim();
        String n = number == null ? "" : number.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (t != null && !OMANG.equals(t) && !PASSPORT.equals(t)) {
            throw new IllegalArgumentException("Proof of identity must be Omang or Passport.");
        }
        if (n.isEmpty()) {
            return t == null ? null : new Identity(t, null);
        }
        if (t == null) {
            throw new IllegalArgumentException("Choose the proof of identity type (Omang or Passport) for this number.");
        }
        if (OMANG.equals(t) && !omang.matcher(n).matches()) {
            throw new IllegalArgumentException(defaultOmang ? "An Omang number is 9 digits."
                    : "This Omang number does not have the configured format.");
        }
        if (PASSPORT.equals(t) && !passport.matcher(n).matches()) {
            throw new IllegalArgumentException(defaultPassport ? "A passport number is 6 to 20 letters and digits."
                    : "This passport number does not have the configured format.");
        }
        return new Identity(t, n);
    }

    /** Active customers (not merged away) who already hold this identity number. */
    public List<CustomerAccount> holders(String number) {
        if (number == null || number.isBlank()) {
            return List.of();
        }
        return em.createQuery("select c from CustomerAccount c where upper(replace(c.poiNumber, ' ', '')) = :number"
                        + " and (c.status is null or c.status not like 'Merged%') order by c.ref", CustomerAccount.class)
                .setParameter("number", number.replaceAll("\\s+", "").toUpperCase(Locale.ROOT))
                .getResultList();
    }

    /** Self sign-up: a known identity number is refused, before any code is sent. */
    public void refuseKnownIdentity(Identity identity) {
        if (identity != null && !holders(identity.number()).isEmpty()) {
            throw new ConflictException(SIGNUP_DUPLICATE);
        }
    }

    /**
     * Staff create: a known identity number is refused with the record that holds it, unless the body says why this
     * is a different person (at least 10 characters). Returns the duplicate-review note to keep on the new record,
     * or null when the number is new.
     */
    public String referral(Identity identity, String reason) {
        List<CustomerAccount> same = identity == null ? List.of() : holders(identity.number());
        if (same.isEmpty()) {
            return null;
        }
        String refs = same.stream().map(c -> c.getRef() + " (" + c.getName() + ")").collect(Collectors.joining(", "));
        if (reason == null || reason.isBlank()) {
            throw new ConflictException(refs + " already holds " + identity.type() + " " + identity.number()
                    + ". Open that record instead, or record why this is a different person: the new record is then"
                    + " referred for duplicate review.");
        }
        if (reason.trim().length() < 10) {
            throw new IllegalArgumentException("Say why this is a different person (at least 10 characters).");
        }
        String note = "Same identity number as " + same.stream().map(CustomerAccount::getRef)
                .collect(Collectors.joining(", ")) + ": " + reason.trim();
        return note.length() <= 500 ? note : note.substring(0, 500);
    }

    /** PartyCreated, and an audit entry when the new record was referred as a possible duplicate. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void created(CustomerAccount row, String actor, String channel) {
        if (row.getDuplicateReview() != null) {
            audit.record(actor, "Possible duplicate referred", "Customer", row.getRef(), row.getDuplicateReview());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerRef", row.getRef());
        data.put("partyType", "PERSON");
        data.put("channel", channel);
        data.put("kycStatus", row.getKycStatus());
        data.put("duplicateReview", row.getDuplicateReview() != null);
        outbox.publish(Events.PARTY_CREATED, row.getRef(), data, actor);
    }

    /** KycStatusChanged for a KYC or EDD review outcome. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void kycChanged(CustomerAccount row, String review, String previous, String decision, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerRef", row.getRef());
        data.put("review", review);
        data.put("previousStatus", previous == null ? "" : previous);
        data.put("status", decision);
        outbox.publish(Events.KYC_STATUS_CHANGED, row.getRef(), data, actor);
    }

    /**
     * Moves every application, policy and claim of a merged customer to the surviving one (AT-SH01-02). The records
     * are changed one by one as entities, so their own row versions apply and a record someone else is changing at
     * the same moment makes the merge fail rather than be overwritten (NS-ARC-002, NS-ARC-005). Returns how many
     * records moved.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int moveRecords(CustomerAccount gone, CustomerAccount keep) {
        int moved = 0;
        for (Class<? extends CoverRecord> type : List.of(InsuranceApplication.class, InsurancePolicy.class,
                InsuranceClaim.class)) {
            for (CoverRecord row : em.createQuery("select e from " + type.getSimpleName()
                    + " e where e.customerRef = :gone", type).setParameter("gone", gone.getRef()).getResultList()) {
                row.setCustomerRef(keep.getRef());
                row.setCustomerName(keep.getName());
                moved++;
            }
        }
        return moved;
    }

    /** PartyMerged: the surviving and merged references; the merged one stays resolvable in the merge register. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void merged(CustomerAccount keep, CustomerAccount gone, int moved, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("survivingRef", keep.getRef());
        data.put("mergedRef", gone.getRef());
        data.put("recordsMoved", moved);
        outbox.publish(Events.PARTY_MERGED, keep.getRef(), data, actor);
    }
}

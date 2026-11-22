package com.nsure.communication;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/communications")
public class CommunicationController {

    private final CommunicationService communications;
    private final MessagingWebhooks webhooks;
    private final PremiumReminders reminders;

    public CommunicationController(CommunicationService communications, MessagingWebhooks webhooks,
            PremiumReminders reminders) {
        this.webhooks = webhooks;
        this.reminders = reminders;
        this.communications = communications;
    }

    @GetMapping("/templates")
    public List<CommunicationViews.TemplateView> templates() {
        return communications.templates();
    }

    /** {code, eventType, channel, audience, subject, body}: a DRAFT; an existing code gets its next version. */
    @PostMapping("/templates")
    public CommunicationViews.TemplateView createTemplate(@RequestBody JsonNode body) {
        return communications.createTemplate(body);
    }

    @PostMapping("/templates/{id}/approve")
    public CommunicationViews.TemplateView approveTemplate(@PathVariable Long id) {
        return communications.approveTemplate(id);
    }

    /** {eventType, audience, subject, body}: rendered with sample values; nothing is saved. */
    @PostMapping("/templates/preview")
    public CommunicationViews.TemplatePreview previewTemplate(@RequestBody(required = false) JsonNode body) {
        return communications.preview(body);
    }

    /** {reason}: asks to withdraw an approved version without a replacement; another user confirms (D-22). */
    @PostMapping("/templates/{id}/withdraw")
    public CommunicationViews.TemplateView requestWithdrawal(@PathVariable Long id,
            @RequestBody(required = false) JsonNode body) {
        return communications.requestWithdrawal(id, body);
    }

    @PostMapping("/templates/{id}/withdraw/approve")
    public CommunicationViews.TemplateView approveWithdrawal(@PathVariable Long id) {
        return communications.approveWithdrawal(id);
    }

    @PostMapping("/templates/{id}/withdraw/decline")
    public CommunicationViews.TemplateView declineWithdrawal(@PathVariable Long id) {
        return communications.declineWithdrawal(id);
    }

    @GetMapping("/messages")
    public List<CommunicationViews.MessageView> messages(@RequestParam(required = false) String status) {
        return communications.messages(status);
    }

    /** One message with its trail: states, provider reports (raw) and staff actions. */
    @GetMapping("/messages/{id}")
    public CommunicationViews.MessageDetailView message(@PathVariable Long id) {
        return communications.message(id);
    }

    /** Body {@code {}} takes a failed message to work it; {@code {"release": true}} gives it back to the queue. */
    @PostMapping("/messages/{id}/owner")
    public CommunicationViews.MessageView own(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return communications.own(id, body);
    }

    /** Messages raised per channel and per day over the last {@code days} days (default 30). */
    @GetMapping("/metrics")
    public CommunicationViews.MetricsView metrics(@RequestParam(required = false) Integer days) {
        return communications.metrics(days);
    }

    /** A customer's conversation: messages sent and replies received, oldest first, with their service cases. */
    @GetMapping("/conversations/{customerRef}")
    public List<CommunicationViews.ConversationItem> conversation(@PathVariable String customerRef) {
        return communications.conversation(customerRef);
    }

    /** {customerRef, channel, subject, body, caseRef?}: a manual service message, subject to the customer's preferences. */
    @PostMapping("/messages")
    public CommunicationViews.MessageView send(@RequestBody JsonNode body) {
        return communications.sendManual(body);
    }

    @PostMapping("/messages/{id}/retry")
    public CommunicationViews.MessageView retry(@PathVariable Long id) {
        return communications.retry(id);
    }

    @PostMapping("/dispatch")
    public CommunicationViews.DispatchView dispatch() {
        return communications.dispatchNow();
    }

    @GetMapping("/preferences/{customerRef}")
    public CommunicationViews.CustomerPreferencesView preferences(@PathVariable String customerRef) {
        return communications.preferences(customerRef);
    }

    /** {channel, status (OPTED_IN|OPTED_OUT), contact}. */
    @PutMapping("/preferences/{customerRef}")
    public CommunicationViews.CustomerPreferencesView setPreference(@PathVariable String customerRef,
            @RequestBody JsonNode body) {
        return communications.setPreference(customerRef, body);
    }

    // ── inbound correspondence and triage (Blueprint §5) ──

    @PutMapping("/preferences/{customerRef}/purposes")
    public CommunicationViews.CustomerPreferencesView choosePurpose(@PathVariable String customerRef,
            @RequestBody JsonNode body) {
        return communications.choosePurpose(customerRef, body);
    }

    @GetMapping("/preferences/{customerRef}/history")
    public java.util.List<java.util.Map<String, Object>> preferenceHistory(@PathVariable String customerRef) {
        return communications.preferenceHistory(customerRef);
    }

    @GetMapping("/rules")
    public java.util.Map<String, Object> rules() {
        return communications.deliveryRules();
    }

    @PutMapping("/rules/purposes/{purpose}")
    public DeliveryRules.PurposeRule savePurposeRule(@PathVariable String purpose, @RequestBody JsonNode body) {
        return communications.savePurposeRule(purpose, body);
    }

    @PutMapping("/rules/recipients")
    public DeliveryRules.RecipientRule saveRecipientRule(@RequestBody JsonNode body) {
        return communications.saveRecipientRule(body);
    }

    @PostMapping("/templates/{id}/provider-submission")
    public CommunicationViews.TemplateView submitToProvider(@PathVariable Long id) {
        return communications.submitToProvider(id);
    }

    @GetMapping("/inbound")
    public List<CommunicationViews.InboundView> inbound(@RequestParam(required = false) String status) {
        return webhooks.list(status);
    }

    /** How a sender may be verified before a triage message is linked (AT-SH09B-03). */
    @GetMapping("/inbound/verification-methods")
    public List<String> verificationMethods() {
        return MessagingWebhooks.VERIFICATION_METHODS;
    }

    /** Body {@code {}} takes the verification task; {@code {"release": true}} gives it back. */
    @PostMapping("/inbound/{id}/owner")
    public CommunicationViews.InboundView ownInbound(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return webhooks.own(id, body);
    }

    /** Body: {@code {"customerRef": "CUS-1001", "method": "...", "note": "..."}}: who sent it, and how that was verified. */
    @PostMapping("/inbound/{id}/assign")
    public CommunicationViews.InboundView assign(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return webhooks.assign(id, body);
    }

    // ── bulk operational notices (maker-checker) ──

    @GetMapping("/bulk-notices")
    public List<CommunicationViews.BulkNoticeView> bulkNotices() {
        return communications.bulkNotices();
    }

    /** Body: {@code {"templateId", "policyStatus", "product", "reason"}}. */
    @PostMapping("/bulk-notices")
    public CommunicationViews.BulkNoticeView requestBulkNotice(@RequestBody(required = false) JsonNode body) {
        return communications.requestBulkNotice(body);
    }

    @PostMapping("/bulk-notices/{ref}/approve")
    public CommunicationViews.BulkNoticeView approveBulkNotice(@PathVariable String ref) {
        return communications.approveBulkNotice(ref);
    }

    @PostMapping("/bulk-notices/{ref}/reject")
    public CommunicationViews.BulkNoticeView rejectBulkNotice(@PathVariable String ref) {
        return communications.rejectBulkNotice(ref);
    }

    /** Runs the premium-reminder sweep now; it also runs every morning. */
    @PostMapping("/reminders/run")
    public java.util.Map<String, Integer> runReminders() {
        return java.util.Map.of("published", reminders.runNow());
    }
}

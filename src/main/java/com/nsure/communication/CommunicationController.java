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

    @GetMapping("/messages")
    public List<CommunicationViews.MessageView> messages(@RequestParam(required = false) String status) {
        return communications.messages(status);
    }

    /** {customerRef, channel, subject, body}: a manual service message, subject to the customer's preferences. */
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

    @GetMapping("/inbound")
    public List<CommunicationViews.InboundView> inbound(@RequestParam(required = false) String status) {
        return webhooks.list(status);
    }

    /** Body: {@code {"customerRef": "CUS-1001"}}: who an unmatched message is from. */
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

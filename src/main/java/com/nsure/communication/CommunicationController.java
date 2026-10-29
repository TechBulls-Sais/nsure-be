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

    public CommunicationController(CommunicationService communications) {
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
}

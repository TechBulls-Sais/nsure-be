package com.nsure.communication;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Callbacks from the Email/WhatsApp provider. No login: each request is authenticated by its
 * {@code X-Nsure-Signature} (HMAC-SHA256 of the body with this insurer's webhook secret), checked before anything
 * is read or changed.
 */
@RestController
@RequestMapping("/integration/messaging")
public class MessagingWebhookController {

    private final MessagingWebhooks webhooks;

    public MessagingWebhookController(MessagingWebhooks webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/status")
    public CommunicationViews.CallbackResult status(@RequestBody(required = false) String body,
            @RequestHeader(value = "X-Nsure-Signature", required = false) String signature) {
        return webhooks.status(body, signature);
    }

    @PostMapping("/inbound")
    public CommunicationViews.CallbackResult inbound(@RequestBody(required = false) String body,
            @RequestHeader(value = "X-Nsure-Signature", required = false) String signature) {
        return webhooks.inbound(body, signature);
    }
}

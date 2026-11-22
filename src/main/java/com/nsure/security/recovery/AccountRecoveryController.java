package com.nsure.security.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer account recovery from the public sign-in page; no sign-in needed (see ApiAccessRules). */
@RestController
@RequestMapping("/public/password-reset")
public class AccountRecoveryController {

    private final AccountRecoveryService recovery;

    public AccountRecoveryController(AccountRecoveryService recovery) {
        this.recovery = recovery;
    }

    @PostMapping("/otp")
    public Map<String, Object> requestCode(@RequestBody JsonNode body) {
        return recovery.requestCode(body);
    }

    @PostMapping
    public Map<String, Object> reset(@RequestBody JsonNode body) {
        return recovery.reset(body);
    }
}

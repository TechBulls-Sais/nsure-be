package com.nsure.carecash;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Care Cash hospital benefits: assess, record, approve and pay events against the policy's day limits. */
@RestController
@RequestMapping("/care-cash")
public class CareCashController {

    private final CareCashService careCash;
    private final CareCashRules rules;

    public CareCashController(CareCashService careCash, CareCashRules rules) {
        this.careCash = careCash;
        this.rules = rules;
    }

    /** The Care Cash rule set versions (section 07): values, effective dates, approval. */
    @GetMapping("/rules")
    public List<CareCashRules.RuleSetView> rules() {
        return rules.versions();
    }

    /** {effectiveFrom, reason, label?, rules: {key: value}}: proposes the next version for a second person. */
    @PostMapping("/rules")
    public CareCashRules.RuleSetView proposeRules(@RequestBody JsonNode body) {
        return rules.propose(body);
    }

    @PostMapping("/rules/{version}/{decision:approve|reject}")
    public CareCashRules.RuleSetView decideRules(@PathVariable int version, @PathVariable String decision,
            @RequestBody(required = false) JsonNode body) {
        return rules.decide(version, "approve".equals(decision),
                body == null || !body.hasNonNull("reason") ? null : body.get("reason").asText());
    }

    /** {lifeName?, lifetimeDays, yearDays, asOf?, note}: migrated opening day consumption for a life. */
    @PostMapping("/policies/{ref}/opening-days")
    public CareCashService.PolicyView openingDays(@PathVariable String ref, @RequestBody JsonNode body) {
        return careCash.openingDays(ref, body);
    }

    /** {reason}: reverses an approved, unpaid event (days released, premium waiver undone). */
    @PostMapping("/events/{ref}/reverse")
    public CareCashService.EventView reverse(@PathVariable String ref, @RequestBody JsonNode body) {
        return careCash.reverse(ref, body);
    }

    @GetMapping("/policies")
    public List<Map<String, Object>> policies() {
        return careCash.policies();
    }

    @GetMapping("/policies/{ref}")
    public CareCashService.PolicyView policy(@PathVariable String ref) {
        return careCash.policy(ref);
    }

    @PostMapping("/policies/{ref}/assessment")
    public CareCashService.EventView assess(@PathVariable String ref, @RequestBody JsonNode body) {
        return careCash.assess(ref, body);
    }

    @PostMapping("/policies/{ref}/events")
    public CareCashService.EventView record(@PathVariable String ref, @RequestBody JsonNode body) {
        return careCash.record(ref, body);
    }

    @GetMapping("/events")
    public List<CareCashService.EventView> events() {
        return careCash.allEvents();
    }

    @PostMapping("/events/{ref}/decision")
    public CareCashService.EventView decide(@PathVariable String ref, @RequestBody JsonNode body) {
        return careCash.decide(ref, body);
    }

    /** {payeeName?, payoutMethod, accountRef}: sends the payment instruction; it is in flight until confirmed. */
    @PostMapping("/events/{ref}/payment")
    public CareCashService.EventView pay(@PathVariable String ref, @RequestBody JsonNode body) {
        return careCash.pay(ref, body);
    }

    /** {providerReference?}: the provider confirmed the payment settled. */
    @PostMapping("/events/{ref}/payment/confirm")
    public CareCashService.EventView confirmPayment(@PathVariable String ref,
            @RequestBody(required = false) JsonNode body) {
        return careCash.confirmPayment(ref, body);
    }

    /** {reason}: the provider failed or returned the payment. */
    @PostMapping("/events/{ref}/payment/failed")
    public CareCashService.EventView paymentFailed(@PathVariable String ref, @RequestBody JsonNode body) {
        return careCash.paymentFailed(ref, body);
    }
}

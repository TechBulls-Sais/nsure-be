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

    public CareCashController(CareCashService careCash) {
        this.careCash = careCash;
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

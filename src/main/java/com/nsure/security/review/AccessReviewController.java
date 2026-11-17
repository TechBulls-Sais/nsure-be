package com.nsure.security.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Access recertification: start a review, decide each login, close it (NS-SH11-007). */
@RestController
@RequestMapping("/admin/access-reviews")
public class AccessReviewController {

    private final AccessReviewService reviews;

    public AccessReviewController(AccessReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return reviews.list();
    }

    @PostMapping
    public Map<String, Object> start() {
        return reviews.start();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return reviews.get(id);
    }

    @PostMapping("/{id}/items/{itemId}/decision")
    public Map<String, Object> decide(@PathVariable Long id, @PathVariable Long itemId,
            @RequestBody(required = false) JsonNode body) {
        return reviews.decide(id, itemId, body);
    }

    @PostMapping("/{id}/close")
    public Map<String, Object> close(@PathVariable Long id) {
        return reviews.close(id);
    }
}

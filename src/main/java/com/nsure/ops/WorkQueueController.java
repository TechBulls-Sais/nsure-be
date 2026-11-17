package com.nsure.ops;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The work queue across the modules: list, take, release and (supervisors) assign. */
@RestController
@RequestMapping("/ops/queue")
public class WorkQueueController {

    private final WorkQueueService queue;

    public WorkQueueController(WorkQueueService queue) {
        this.queue = queue;
    }

    @GetMapping
    public List<Map<String, Object>> queue() {
        return queue.queue();
    }

    @PostMapping("/claim")
    public Map<String, Object> claim(@RequestParam String key) {
        return queue.claim(key);
    }

    @DeleteMapping("/claim")
    public Map<String, Object> release(@RequestParam String key) {
        return queue.release(key);
    }

    @PostMapping("/assign")
    public Map<String, Object> assign(@RequestParam String key, @RequestParam String to) {
        return queue.assignTo(key, to);
    }

    @GetMapping("/assignees")
    public List<String> assignees(@RequestParam String key) {
        return queue.assignees(key);
    }
}

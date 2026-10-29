package com.nsure.servicedesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import jakarta.persistence.OptimisticLockException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/service/cases")
public class ServiceDeskController {

    private final ServiceDeskService desk;

    public ServiceDeskController(ServiceDeskService desk) {
        this.desk = desk;
    }

    @GetMapping
    public List<ServiceDeskViews.CaseView> cases(@RequestParam(required = false) String status,
            @RequestParam(required = false) String type, @RequestParam(required = false) Boolean mine) {
        return desk.cases(status, type, Boolean.TRUE.equals(mine));
    }

    @GetMapping("/{ref}")
    public ServiceDeskViews.CaseDetail detail(@PathVariable String ref) {
        return desk.detail(ref);
    }

    @PostMapping
    public ServiceDeskViews.CaseDetail open(@RequestBody JsonNode body) {
        return desk.open(body);
    }

    /** action: assign | pause | resume | resolve | reopen | close. Body may carry expectedVersion. */
    @PostMapping("/{ref}/{action}")
    public ServiceDeskViews.CaseDetail act(@PathVariable String ref, @PathVariable String action,
            @RequestBody(required = false) JsonNode body) {
        return conflictOnStaleWrite(ref, () -> desk.act(ref, action, body));
    }

    /** A concurrent change detected only at commit is still a 409, not a server error. */
    static <T> T conflictOnStaleWrite(String ref, Supplier<T> write) {
        try {
            return write.get();
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException ex) {
            throw new ConflictException(ref + " was changed by someone else. Refresh and retry.");
        }
    }
}

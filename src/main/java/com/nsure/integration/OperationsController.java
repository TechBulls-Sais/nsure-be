package com.nsure.integration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Status of a long-running command accepted for background processing (spec §10). */
@RestController
public class OperationsController {

    private final Operations operations;

    public OperationsController(Operations operations) {
        this.operations = operations;
    }

    @GetMapping("/operations/{id}")
    public Operations.OperationView operation(@PathVariable String id) {
        return operations.status(id);
    }
}

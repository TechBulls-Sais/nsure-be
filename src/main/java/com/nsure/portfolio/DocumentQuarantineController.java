package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The upload quarantine queue (AT-SH09A-02): list, inspect, release or reject held uploads. */
@RestController
@RequestMapping("/portfolio/document-quarantine")
public class DocumentQuarantineController {

    private final DocumentQuarantine quarantine;

    public DocumentQuarantineController(DocumentQuarantine quarantine) {
        this.quarantine = quarantine;
    }

    /** status: PENDING (default), RELEASED, REJECTED or ALL. */
    @GetMapping
    public List<DocumentQuarantine.UploadView> list(@RequestParam(required = false) String status) {
        return quarantine.list(status);
    }

    /** The held file, for the reviewer only (RELEASE_DOCUMENT); every opening is logged. */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> inspect(@PathVariable Long id) {
        return ClaimDocumentController.file(quarantine.inspect(id));
    }

    /** Body {@code {"note": "what was checked"}}: files the upload as a claim document, recorded as not scanned. */
    @PostMapping("/{id}/release")
    public DocumentQuarantine.UploadView release(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return quarantine.release(id, body);
    }

    /** Body {@code {"reason": "..."}}: refuses the upload; the uploader sees the reason. */
    @PostMapping("/{id}/reject")
    public DocumentQuarantine.UploadView reject(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return quarantine.reject(id, body);
    }
}

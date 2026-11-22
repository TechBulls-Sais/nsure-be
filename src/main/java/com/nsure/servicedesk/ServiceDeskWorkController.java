package com.nsure.servicedesk;

import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service desk reference data, the escalation check and case evidence (SH10 with SH09A): staff attach and open case
 * documents, and the customer attaches evidence to their own case from the portal. Uploads wait in quarantine.
 */
@RestController
public class ServiceDeskWorkController {

    private final ServiceDeskService desk;

    public ServiceDeskWorkController(ServiceDeskService desk) {
        this.desk = desk;
    }

    @GetMapping("/service/reference")
    public Map<String, Object> reference() {
        return desk.reference();
    }

    @PostMapping("/service/escalations/run")
    public Map<String, Object> runEscalations() {
        return desk.runEscalations();
    }

    @GetMapping("/service/cases/{ref}/documents")
    public Map<String, Object> documents(@PathVariable String ref) {
        return desk.documents(ref);
    }

    @PostMapping(path = "/service/cases/{ref}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> attach(@PathVariable String ref, @RequestPart("file") MultipartFile file,
            @RequestParam(value = "type", required = false) String type) {
        return desk.attach(ref, file, type);
    }

    @GetMapping("/service/cases/{ref}/documents/{id}")
    public ResponseEntity<Resource> document(@PathVariable String ref, @PathVariable Long id) {
        com.nsure.portfolio.ClaimDocumentService.StoredFile stored = desk.document(ref, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .contentLength(stored.size())
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline()
                                .filename(stored.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(stored.resource());
    }

    @GetMapping("/portfolio/shop/cases/{ref}/documents")
    public Map<String, Object> customerDocuments(@PathVariable String ref) {
        return desk.customerDocuments(ref);
    }

    @PostMapping(path = "/portfolio/shop/cases/{ref}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> customerAttach(@PathVariable String ref, @RequestPart("file") MultipartFile file,
            @RequestParam(value = "type", required = false) String type) {
        return desk.customerAttach(ref, file, type);
    }
}

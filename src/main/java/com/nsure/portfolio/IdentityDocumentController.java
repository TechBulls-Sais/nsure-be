package com.nsure.portfolio;

import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Identity evidence for KYC: the customer's own uploads in the portal, and the staff view on the customer record. */
@RestController
@RequestMapping("/portfolio")
public class IdentityDocumentController {

    private final IdentityDocumentService documents;

    public IdentityDocumentController(IdentityDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/shop/identity-documents")
    public List<IdentityDocumentService.IdentityDocumentView> mine() {
        return documents.mine();
    }

    @PostMapping(path = "/shop/identity-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<IdentityDocumentService.IdentityDocumentView> upload(@RequestPart("file") MultipartFile file,
            @RequestParam(value = "type", required = false) String type) {
        return documents.upload(file, type);
    }

    @GetMapping("/customers/{ref}/identity-documents")
    public List<IdentityDocumentService.IdentityDocumentView> forCustomer(@PathVariable String ref) {
        return documents.forCustomer(ref);
    }

    @GetMapping("/customers/{ref}/identity-documents/{id}")
    public ResponseEntity<Resource> download(@PathVariable String ref, @PathVariable Long id) {
        return ClaimDocumentController.file(documents.download(ref, id));
    }
}

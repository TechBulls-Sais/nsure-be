package com.nsure.portfolio;

import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Policy documents: staff under /portfolio/policies, the signed-in customer under /portfolio/shop/policies. */
@RestController
@RequestMapping("/portfolio")
public class PolicyDocumentController {

    private final PolicyDocumentService documents;

    public PolicyDocumentController(PolicyDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/policies/{ref}/documents")
    public List<PortfolioViews.DocumentView> list(@PathVariable String ref) {
        return documents.list(ref);
    }

    /** Generates a policy schedule PDF and files it with the policy's documents. */
    @PostMapping("/policies/{ref}/documents/schedule")
    public List<PortfolioViews.DocumentView> generateSchedule(@PathVariable String ref) {
        return documents.generateSchedule(ref);
    }

    @GetMapping("/policies/{ref}/documents/{id}")
    public ResponseEntity<Resource> download(@PathVariable String ref, @PathVariable Long id) {
        return ClaimDocumentController.file(documents.download(ref, id));
    }

    @PostMapping("/shop/policies/{ref}/documents/schedule")
    public PortfolioViews.ShopView shopGenerateSchedule(@PathVariable String ref) {
        return documents.shopGenerateSchedule(ref);
    }

    @GetMapping("/shop/policies/{ref}/documents/{id}")
    public ResponseEntity<Resource> shopDownload(@PathVariable String ref, @PathVariable Long id) {
        return ClaimDocumentController.file(documents.shopDownload(ref, id));
    }
}

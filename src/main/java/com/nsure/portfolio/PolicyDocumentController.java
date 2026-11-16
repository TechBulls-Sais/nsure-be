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

    /** A controlled replacement of the current schedule, with a reason; the old one is kept as superseded. */
    @PostMapping("/policies/{ref}/documents/{id}/reissue")
    public List<PortfolioViews.DocumentView> reissue(@PathVariable String ref, @PathVariable Long id,
            @RequestBody(required = false) JsonNode body) {
        return documents.reissue(ref, id, body == null || !body.hasNonNull("reason") ? null : body.get("reason").asText());
    }

    /** Who downloaded this policy's documents (READ_AUDIT). */
    @GetMapping("/policies/{ref}/document-access")
    public List<DocumentCustody.AccessView> accessLog(@PathVariable String ref) {
        return documents.accessLog(ref);
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

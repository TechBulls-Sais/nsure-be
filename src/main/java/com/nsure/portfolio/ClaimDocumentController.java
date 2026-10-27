package com.nsure.portfolio;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

/** Claim evidence: staff under /portfolio/claims, the signed-in customer under /portfolio/shop/claims. */
@RestController
@RequestMapping("/portfolio")
public class ClaimDocumentController {

    private final ClaimDocumentService documents;

    public ClaimDocumentController(ClaimDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/claims/{ref}/documents")
    public List<PortfolioViews.DocumentView> list(@PathVariable String ref) {
        return documents.list(ref);
    }

    @PostMapping(path = "/claims/{ref}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<PortfolioViews.DocumentView> upload(@PathVariable String ref, @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String type) {
        return documents.upload(ref, file, type);
    }

    /** Generates a claim summary PDF and files it with the claim's documents. */
    @PostMapping("/claims/{ref}/documents/summary")
    public List<PortfolioViews.DocumentView> generateSummary(@PathVariable String ref) {
        return documents.generateSummary(ref);
    }

    @GetMapping("/claims/{ref}/documents/{id}")
    public ResponseEntity<Resource> download(@PathVariable String ref, @PathVariable Long id) {
        return file(documents.download(ref, id));
    }

    @PostMapping(path = "/shop/claims/{ref}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PortfolioViews.ShopView shopUpload(@PathVariable String ref, @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String type) {
        return documents.shopUpload(ref, file, type);
    }

    @GetMapping("/shop/claims/{ref}/documents/{id}")
    public ResponseEntity<Resource> shopDownload(@PathVariable String ref, @PathVariable Long id) {
        return file(documents.shopDownload(ref, id));
    }

    private static ResponseEntity<Resource> file(ClaimDocumentService.StoredFile stored) {
        ClaimDocument document = stored.document();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .contentLength(document.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(document.getFileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(stored.resource());
    }
}

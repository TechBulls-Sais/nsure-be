package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Short-lived authorised document links (NS-SH09A-006): created by someone who may open the document, used once. */
@RestController
@RequestMapping("/portfolio/document-links")
public class DocumentLinkController {

    private final DocumentLinks links;

    public DocumentLinkController(DocumentLinks links) {
        this.links = links;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody JsonNode body) {
        return links.create(body);
    }

    @GetMapping("/{token}")
    public ResponseEntity<Resource> open(@PathVariable String token) {
        ClaimDocumentService.StoredFile stored = links.open(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .contentLength(stored.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(stored.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(stored.resource());
    }
}

package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Document generation templates (NS-SH09A-002): design, sample preview, second-person publication, generation. */
@RestController
@RequestMapping("/portfolio")
public class DocumentTemplateController {

    private final DocumentTemplates templates;

    public DocumentTemplateController(DocumentTemplates templates) {
        this.templates = templates;
    }

    @GetMapping("/document-templates")
    public Map<String, Object> list() {
        return templates.list();
    }

    @PostMapping("/document-templates")
    public Map<String, Object> save(@RequestBody JsonNode body) {
        return templates.save(body);
    }

    @PostMapping("/document-templates/preview")
    public ResponseEntity<Resource> preview(@RequestBody JsonNode body) {
        ClaimDocumentService.StoredFile sample = templates.preview(body);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(sample.size())
                .body(sample.resource());
    }

    @PostMapping("/document-templates/{code}/versions/{version}/approve")
    public Map<String, Object> approve(@PathVariable String code, @PathVariable int version) {
        return templates.approve(code.toUpperCase(java.util.Locale.ROOT), version);
    }

    @PostMapping("/policies/{ref}/documents/generate")
    public List<PortfolioViews.DocumentView> generate(@PathVariable String ref, @RequestBody JsonNode body) {
        return templates.generateForPolicy(ref, body);
    }
}

package com.edupulse.ingestion.web;

import com.edupulse.common.security.TenantContext;
import com.edupulse.ingestion.domain.DocumentCategory;
import com.edupulse.ingestion.domain.DocumentRecord;
import com.edupulse.ingestion.repository.DocumentRepository;
import com.edupulse.ingestion.service.DocumentService;
import com.edupulse.ingestion.service.DocumentService.UploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;
    private final DocumentRepository documents;

    /** Upload – only ADMIN and COMPLIANCE_OFFICER (FR "Granular RBAC"). ≤ 10 MB → 201, &gt; 10 MB → 202 (background). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<Map<String, Object>> upload(@RequestPart("file") MultipartFile file,
                                                      @RequestParam DocumentCategory category,
                                                      @RequestParam(required = false) String docType,
                                                      @RequestParam(required = false) Integer clearanceLevel,
                                                      @RequestParam(required = false) String title) throws IOException {
        UploadResult r = service.upload(file, category, docType, clearanceLevel, title, TenantContext.current());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", r.document().id());
        body.put("async", r.async());
        body.put("statusUrl", "/api/documents/" + r.document().id() + "/status");
        body.put("document", DocumentView.of(r.document()));
        return ResponseEntity.status(r.async() ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(body);
    }

    /** Documents the user may see (same rule as the RAG tenant filter). */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) DocumentCategory category,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        TenantContext t = TenantContext.current();
        int s = Math.clamp(size, 1, 200);
        int p = Math.max(0, page);
        List<DocumentView> items = documents.findVisible(t, category, s, p * s).stream().map(DocumentView::of).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", documents.countVisible(t, category));
        return body;
    }

    @GetMapping("/{id}")
    public DocumentView get(@PathVariable UUID id) {
        return DocumentView.of(service.visibleOrThrow(id, TenantContext.current()));
    }

    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable UUID id) {
        DocumentRecord d = service.visibleOrThrow(id, TenantContext.current());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", d.id());
        body.put("status", d.status());
        body.put("progressPct", d.progressPct());
        body.put("chunkCount", d.chunkCount());
        body.put("error", d.errorMessage());
        return body;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id, TenantContext.current());
    }
}

package com.edupulse.ingestion.web;

import com.edupulse.common.crypto.AesGcmCipher;
import com.edupulse.common.web.ApiException;
import com.edupulse.ingestion.domain.DocumentStatus;
import com.edupulse.ingestion.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Service-to-service API (requires a JWT with {@code scope=internal}, see ResourceServerSecurityConfig).
 * Never routed by the gateway. Used by the compliance service's gap-analysis job.
 */
@RestController
@RequestMapping("/internal/documents")
@RequiredArgsConstructor
public class InternalDocumentController {

    private final DocumentRepository documents;
    private final AesGcmCipher cipher;

    public record ParentDto(UUID parentId, int parentIndex, Integer pageNumber, String sectionRef, String content) {
    }

    @GetMapping("/{id}")
    public DocumentView document(@PathVariable UUID id) {
        return documents.findById(id).filter(d -> d.status() != DocumentStatus.DELETED)
                .map(DocumentView::of).orElseThrow(() -> ApiException.notFound("Document"));
    }

    @GetMapping("/{id}/parents")
    public List<ParentDto> parents(@PathVariable UUID id) {
        documents.findById(id).filter(d -> d.status() == DocumentStatus.INDEXED)
                .orElseThrow(() -> ApiException.notFound("Indexed document"));
        return documents.parents(id).stream()
                .map(p -> new ParentDto(p.id(), p.parentIndex(), p.pageNumber(), p.sectionRef(),
                        cipher.decryptString(p.contentEnc())))
                .toList();
    }
}

package com.edupulse.compliance.web;

import com.edupulse.common.security.TenantContext;
import com.edupulse.compliance.qa.AskRequest;
import com.edupulse.compliance.qa.AskResponse;
import com.edupulse.compliance.qa.ComplianceQaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Q&amp;A over the internal documents (course: InternalQAController, POST /api/internal-qa/ask). */
@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
public class ComplianceQaController {

    private final ComplianceQaService qa;

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return qa.ask(request, TenantContext.current());
    }
}

package com.edupulse.compliance;

import com.edupulse.common.crypto.CryptoConfig;
import com.edupulse.common.security.ResourceServerSecurityConfig;
import com.edupulse.common.web.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/** Advanced RAG Q&amp;A with citations and guardrails, audit trail, conversation history and gap analysis. */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import({ResourceServerSecurityConfig.class, CryptoConfig.class, ApiExceptionHandler.class})
public class ComplianceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceServiceApplication.class, args);
    }
}

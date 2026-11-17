package com.edupulse.ingestion;

import com.edupulse.common.crypto.CryptoConfig;
import com.edupulse.common.security.ResourceServerSecurityConfig;
import com.edupulse.common.web.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/** Upload, validation, encrypted storage, parsing, hierarchical chunking, embedding and indexing. */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import({ResourceServerSecurityConfig.class, CryptoConfig.class, ApiExceptionHandler.class})
public class IngestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}

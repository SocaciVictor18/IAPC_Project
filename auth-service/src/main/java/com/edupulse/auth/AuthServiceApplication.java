package com.edupulse.auth;

import com.edupulse.common.crypto.CryptoConfig;
import com.edupulse.common.web.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Issues RS256 JWTs, publishes the JWKS and manages users, org units, roles and clearance. */
@SpringBootApplication
@Import({CryptoConfig.class, ApiExceptionHandler.class})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}

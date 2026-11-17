package com.edupulse.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the shared AES-256-GCM cipher. The key comes from the environment ({@code DATA_ENCRYPTION_KEY}). */
@Configuration(proxyBeanMethods = false)
public class CryptoConfig {

    @Bean
    AesGcmCipher aesGcmCipher(@Value("${edupulse.crypto.data-key}") String base64Key) {
        return AesGcmCipher.fromBase64(base64Key);
    }
}

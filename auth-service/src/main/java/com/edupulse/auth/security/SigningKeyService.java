package com.edupulse.auth.security;

import com.edupulse.common.crypto.AesGcmCipher;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the RSA key pair used to sign access tokens. On first start a 2048-bit key is generated
 * and persisted (private part encrypted with AES-256-GCM); later starts and other replicas
 * load the same key, so tokens validate no matter which auth instance issued them.
 */
@Slf4j
@Service
public class SigningKeyService {

    private final RSAKey rsaKey;

    public SigningKeyService(JdbcClient jdbc, AesGcmCipher cipher) {
        this.rsaKey = loadOrCreate(jdbc, cipher);
        log.info("JWT signing key loaded (kid={})", rsaKey.getKeyID());
    }

    public RSAKey rsaKey() {
        return rsaKey;
    }

    public RSAPublicKey publicKey() {
        try {
            return rsaKey.toRSAPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> publicJwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }

    private static RSAKey loadOrCreate(JdbcClient jdbc, AesGcmCipher cipher) {
        return load(jdbc, cipher).orElseGet(() -> {
            try {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048);
                KeyPair kp = gen.generateKeyPair();
                String kid = UUID.randomUUID().toString();
                jdbc.sql("""
                                insert into auth.signing_key (kid, public_key_der, private_key_enc, active)
                                select ?, ?, ?, true
                                where not exists (select 1 from auth.signing_key where active)
                                """)
                        .params(kid, kp.getPublic().getEncoded(), cipher.encrypt(kp.getPrivate().getEncoded()))
                        .update();
                // Another replica may have won the race: always re-read the active key.
                return load(jdbc, cipher).orElseThrow();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot create signing key", e);
            }
        });
    }

    private static Optional<RSAKey> load(JdbcClient jdbc, AesGcmCipher cipher) {
        return jdbc.sql("select kid, public_key_der, private_key_enc from auth.signing_key where active order by created_at limit 1")
                .query((rs, n) -> {
                    try {
                        KeyFactory kf = KeyFactory.getInstance("RSA");
                        RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(rs.getBytes("public_key_der")));
                        RSAPrivateKey priv = (RSAPrivateKey) kf.generatePrivate(
                                new PKCS8EncodedKeySpec(cipher.decrypt(rs.getBytes("private_key_enc"))));
                        return new RSAKey.Builder(pub)
                                .privateKey(priv)
                                .keyID(rs.getString("kid"))
                                .keyUse(KeyUse.SIGNATURE)
                                .algorithm(JWSAlgorithm.RS256)
                                .build();
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot load signing key", e);
                    }
                })
                .optional();
    }
}

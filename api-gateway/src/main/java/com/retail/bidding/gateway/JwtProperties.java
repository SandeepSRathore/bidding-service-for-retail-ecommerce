package com.retail.bidding.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param secret HMAC-SHA256 signing key, at least 32 bytes
 * @param tokenTtl lifetime of tokens issued by the dev token endpoint
 */
@ConfigurationProperties("gateway.jwt")
public record JwtProperties(String secret, @DefaultValue("PT8H") Duration tokenTtl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("gateway.jwt.secret must be at least 32 bytes");
        }
    }

    SecretKey key() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}

package com.ecommerce.payment.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fingerprint of a payment request; equal amounts hash equally whatever their scale (10.0 and 10.00). */
final class RequestHash {

    private RequestHash() {
    }

    static String of(String orderId, BigDecimal amount) {
        String canonical = orderId + '|' + amount.stripTrailingZeros().toPlainString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}

package com.aieducenter.aiplatform.backoffice;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 后台机机面五头签名计算（#152 seam 测试件，与 SignatureVerificationFilter
 * 同构的客户端实现）：SHA-256 body 摘要 + HMAC-SHA256，stringToSign＝
 * apiKey/bodyDigest/nonce/timestamp＋query 参数按键名字典序 {@code k=v&…}。
 * 正例走 {@link #signed}（{@link BackofficeSignatureTestConfig} 固定凭据）；
 * 负例走 {@link #headers} 指定错 secret / 过期时间戳。
 */
public final class BackofficeSignatures {

    private BackofficeSignatures() {
    }

    /** 按五头协议为请求盖章（时间戳/nonce 现生成；body 为 {@code null} 即 GET 无体）。 */
    public static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder builder,
                                                       String pathWithQuery, String body) {
        return apply(builder, headers(pathWithQuery, body == null ? "" : body,
                BackofficeSignatureTestConfig.API_SECRET));
    }

    /** 负例入口：自定义 secret（错签出 401 Signature mismatch）。 */
    public static Map<String, String> headers(String pathWithQuery, String body, String secret) {
        return headers(pathWithQuery, body, secret, System.currentTimeMillis() / 1000);
    }

    /** 负例入口：自定义时间戳（容差窗口外出 401 Timestamp expired）。 */
    public static Map<String, String> headers(String pathWithQuery, String body, String secret,
                                              long timestampSeconds) {
        String nonce = UUID.randomUUID().toString();
        String bodyDigest = sha256Hex(body);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("apiKey", BackofficeSignatureTestConfig.API_KEY);
        params.put("bodyDigest", bodyDigest);
        params.put("nonce", nonce);
        params.put("timestamp", Long.toString(timestampSeconds));
        int queryStart = pathWithQuery.indexOf('?');
        if (queryStart >= 0) {
            for (String pair : pathWithQuery.substring(queryStart + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0], kv[1]);
                }
            }
        }
        StringBuilder stringToSign = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!stringToSign.isEmpty()) {
                stringToSign.append('&');
            }
            stringToSign.append(e.getKey()).append('=').append(e.getValue());
        }
        return Map.of(
                "X-Api-Key", BackofficeSignatureTestConfig.API_KEY,
                "X-Timestamp", Long.toString(timestampSeconds),
                "X-Nonce", nonce,
                "X-Body-Digest", bodyDigest,
                "X-Sign", hmacSha256Hex(secret, stringToSign.toString()));
    }

    public static MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder builder,
                                                      Map<String, String> headers) {
        headers.forEach(builder::header);
        return builder;
    }

    private static String sha256Hex(String body) {
        return hex(digest("SHA-256", body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] digest(String algorithm, byte[] input) {
        try {
            return java.security.MessageDigest.getInstance(algorithm).digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

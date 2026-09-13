package com.aieducenter.aiplatform.backoffice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.cartisan.openapi.nonce.InMemoryNonceRepository;
import com.cartisan.openapi.nonce.NonceRepository;
import com.cartisan.openapi.provider.ApiKeyInfo;
import com.cartisan.openapi.provider.ApiKeyProvider;

/**
 * 后台机机面签名链测试件（#152 seam 底座，后续十五片域票直接复用，不重搭）：
 * 固定凭据源替换远端 app-registry 取数、内存 nonce 存储替换共享存储——签名
 * 验证 Filter/Interceptor 与控制器全真，仅凭据与防重放两处外部依赖为测试件
 * （两个 bean 均顶替 {@code @ConditionalOnMissingBean} 的自动装配缺省）。
 *
 * <p>配对使用 {@link BackofficeSignatures}（签名头计算，与
 * SignatureVerificationFilter 同构）；测试类挂 {@link BackofficeSeamTest}
 * 组合注解即装本配置。</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class BackofficeSignatureTestConfig {

    public static final String API_KEY = "backoffice-test";
    public static final String API_SECRET = "test-secret";

    @Bean
    public ApiKeyProvider apiKeyProvider() {
        return apiKey -> new ApiKeyInfo(API_KEY, "backoffice-test-app", API_SECRET);
    }

    @Bean
    public NonceRepository nonceRepository() {
        return new InMemoryNonceRepository();
    }
}

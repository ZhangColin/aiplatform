package com.aieducenter.aiplatform.backoffice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import com.aieducenter.aiplatform.IntegrationTest;

/**
 * 后台机机面测试 seam（#152 底座）：全上下文真库集成（{@link IntegrationTest}，
 * aiplatform_test）＋ MockMvc 穿完整过滤链（{@link AutoConfigureMockMvc}）＋
 * 真签名测试件（{@link BackofficeSignatureTestConfig} 固定凭据＋内存 nonce）。
 * 后续十五片域票的后台接口测试一律挂本注解，不在票内重搭 seam。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@IntegrationTest
@AutoConfigureMockMvc
@Import(BackofficeSignatureTestConfig.class)
public @interface BackofficeSeamTest {
}

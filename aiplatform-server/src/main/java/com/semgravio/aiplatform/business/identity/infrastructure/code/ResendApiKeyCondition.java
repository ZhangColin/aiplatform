package com.semgravio.aiplatform.business.identity.infrastructure.code;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Resend key 已配置条件（#376）：属性 {@code app.verification-code.resend.api-key}
 * （yml 占位 {@code ${RESEND_API_KEY:}}）非空即真。@ConditionalOnProperty 分不出
 * 「占位符缺省的空串」与「已配置」（空串会被当存在），故自写条件——空 key 必须
 * 等价未配置，回落日志桩（本地开发零配置）。
 */
class ResendApiKeyCondition implements Condition {

    static final String API_KEY_PROPERTY = "app.verification-code.resend.api-key";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StringUtils.hasText(context.getEnvironment().getProperty(API_KEY_PROPERTY));
    }
}

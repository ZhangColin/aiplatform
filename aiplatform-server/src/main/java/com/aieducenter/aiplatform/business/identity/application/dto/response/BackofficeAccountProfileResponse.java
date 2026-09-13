package com.aieducenter.aiplatform.business.identity.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;

/**
 * 后台账号极简档案（#154 账号域唯一读口，/api/backoffice/accounts/{externalId}）：
 * 我方留存字段原样，无 identity 富化——联系方式/封禁等身份属性归 identity/admin
 * 侧，不代理不透传，因此无脱敏议题。
 *
 * @param id          账号标识（内部 TSID 十进制字符串——REST Long 序列化口径）
 * @param externalId  外部身份标识（OIDC sub＝identity 账户 Id，对外正身）
 * @param displayName 显示名
 * @param createdAt   建档时间
 */
public record BackofficeAccountProfileResponse(
        String id,
        String externalId,
        String displayName,
        LocalDateTime createdAt
) {

    /** 聚合 → 后台极简档案。 */
    public static BackofficeAccountProfileResponse of(Account account) {
        return new BackofficeAccountProfileResponse(
                account.getId().toString(),
                account.getExternalId(),
                account.getDisplayName(),
                account.getCreatedAt());
    }
}

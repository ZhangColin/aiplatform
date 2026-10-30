package com.semgravio.aiplatform.business.identity.application.dto.response;

import java.time.LocalDateTime;

import com.semgravio.aiplatform.business.identity.domain.aggregate.Account;

/**
 * 后台账号极简档案（#154 账号域唯一读口，/api/backoffice/accounts/{externalId}）：
 * 我方留存字段原样，无 identity 富化——联系方式/封禁等身份属性归 identity/admin
 * 侧，不代理不透传，因此无脱敏议题。#327 绑定表拆分：externalId 语义扩为
 * 「对外正身＝主绑定标识」（存量 OIDC sub 零变化；验证码登录人群的邮箱/手机号
 * 同面可寻址），取数经 AccountAppService。
 *
 * @param id          账号标识（内部 TSID 十进制字符串——REST Long 序列化口径）
 * @param externalId  外部身份标识（主绑定标识：OIDC sub / 邮箱 / 手机号，对外正身）
 * @param displayName 显示名
 * @param createdAt   建档时间
 */
public record BackofficeAccountProfileResponse(
        String id,
        String externalId,
        String displayName,
        LocalDateTime createdAt
) {

    /** 聚合 ＋ 主绑定标识 → 后台极简档案。 */
    public static BackofficeAccountProfileResponse of(Account account, String externalId) {
        return new BackofficeAccountProfileResponse(
                account.getId().toString(),
                externalId,
                account.getDisplayName(),
                account.getCreatedAt());
    }
}

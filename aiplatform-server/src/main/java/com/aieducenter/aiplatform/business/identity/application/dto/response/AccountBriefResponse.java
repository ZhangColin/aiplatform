package com.aieducenter.aiplatform.business.identity.application.dto.response;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;

/**
 * 账号摘要（订单/项目后台读面嵌入，#243）：行内的下单/归属账号事实——
 * externalId＝对外正身（OIDC sub，账号档案读口 /api/backoffice/accounts/
 * {externalId} 的寻址键）＋显示名。跨 BC 软引用取档，缺档/无主由嵌入面
 * null 呈现（容缺不放大成错误）。
 *
 * @param externalId  对外正身（OIDC sub；账号档案读口的键）
 * @param displayName 显示名
 */
public record AccountBriefResponse(
        String externalId,
        String displayName
) {

    /** 聚合 → 摘要。 */
    public static AccountBriefResponse of(Account account) {
        return new AccountBriefResponse(account.getExternalId(), account.getDisplayName());
    }
}

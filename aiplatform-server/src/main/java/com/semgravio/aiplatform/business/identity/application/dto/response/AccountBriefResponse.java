package com.semgravio.aiplatform.business.identity.application.dto.response;

/**
 * 账号摘要（订单/项目后台读面嵌入，#243；#327 取数改道）：行内的下单/归属账号
 * 事实——externalId＝对外正身（绑定表主绑定标识：存量 OIDC sub / 邮箱 / 手机号，
 * 账号档案读口 /api/backoffice/accounts/{externalId} 的寻址键）＋显示名。跨 BC
 * 软引用取档，缺档/无主由嵌入面 null 呈现（容缺不放大成错误）。
 *
 * @param externalId  对外正身（主绑定标识；档案读口的键）
 * @param displayName 显示名
 */
public record AccountBriefResponse(
        String externalId,
        String displayName
) {
}

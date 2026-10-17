package com.aieducenter.aiplatform.business.order.application.dto.command;

/**
 * 范围文本录入命令（#336 后台机机面）：三段（交付清单/验收方式/工期口径）
 * 整组替换——不收半组，每次全量提交。字段合法性由聚合守卫裁决
 * （ORD_029/030/033），不在命令层重复校验。
 *
 * @param scopeDeliverables 交付清单（必填，至多 2000 字）
 * @param scopeAcceptance   验收方式（必填，至多 2000 字）
 * @param scopeTimeline     工期口径（必填，至多 2000 字）
 */
public record EnterScopeCommand(
        String scopeDeliverables,
        String scopeAcceptance,
        String scopeTimeline
) {
}

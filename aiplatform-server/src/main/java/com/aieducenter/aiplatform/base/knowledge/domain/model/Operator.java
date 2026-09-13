package com.aieducenter.aiplatform.base.knowledge.domain.model;

/**
 * 后台管理动作操作者（#151 口径）：admin 侧管理员标识（TSID＋昵称），
 * <b>非平台用户</b>——与 accountId/externalId 无关联语义，底座只透传落痕不校验
 * 真实性（签名面担保调用应用、操作者头明示信任）。
 *
 * <p>Id 供关联、Name 供直读（照 #151 口径：单价表 operator 两列同款设计，
 * 该表落地在后续票；存量/未治理行为空）。</p>
 *
 * @param id   操作者标识（admin 侧 TSID）
 * @param name 操作者昵称（直读展示）
 */
public record Operator(String id, String name) {
}

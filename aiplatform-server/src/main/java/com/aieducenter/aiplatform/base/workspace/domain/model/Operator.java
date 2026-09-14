package com.aieducenter.aiplatform.base.workspace.domain.model;

/**
 * 后台沙箱动作操作者（#174 口径同 #151/#155）：admin 侧管理员标识（TSID＋昵称），
 * <b>非平台用户</b>——与 accountId/externalId 无关联语义，服务端只透传落痕不校验
 * 真实性（签名面担保调用应用，操作者头明示信任）。Id 供关联、Name 供直读
 * （账号删除后仍可读）。
 *
 * <p>与 {@code base.knowledge}/{@code base.metering}/{@code business.order} 的
 * Operator 同款不同身：口径同源（各 BC 自有概念，不跨上下文共享类型）。缺席语义
 * 照 #155 落空口径：缺头/空白归一为 {@code null} 落库，不设必填校验。</p>
 *
 * @param id   操作者标识（admin 侧 TSID 十进制字符串）
 * @param name 操作者昵称（直读展示）
 */
public record Operator(String id, String name) {

    /** 空白归一：缺头/空串/纯空白统一为 null（落空口径），首尾空白去除，两肢独立。 */
    public Operator {
        id = blankToNull(id);
        name = blankToNull(name);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

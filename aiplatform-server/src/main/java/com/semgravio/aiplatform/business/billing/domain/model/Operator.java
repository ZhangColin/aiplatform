package com.semgravio.aiplatform.business.billing.domain.model;

/**
 * 后台计费写动作操作者（#151 口径）：admin 侧管理员标识（TSID＋昵称），
 * <b>非平台用户</b>——服务端只透传落痕不校验真实性（签名面担保调用应用，操作者
 * 头明示信任）。Id 供关联、Name 供直读（账号删除后仍可读）。
 *
 * <p>与 {@code business.order.domain.model.Operator} 同款不同身：口径同源 #151
 * （各 BC 自有概念，不跨上下文共享类型）。本 BC 写口（入账/调整/参数）照
 * {@code business.order} #155 落空口径：缺头/空白归一为 {@code null} 落库。</p>
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

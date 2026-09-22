package com.aieducenter.aiplatform.base.skills.domain.model;

/**
 * 后台技能库管理动作操作者（#248 口径同 #151/#166）：admin 侧管理员标识
 * （TSID＋昵称），<b>非平台用户</b>——与 accountId/externalId 无关联语义，
 * 服务端只透传落痕不校验真实性（签名面担保调用应用，操作者头明示信任）。
 * Id 供关联、Name 供直读（账号删除后仍可读）。
 *
 * <p>与 {@code base.workspace}/{@code base.knowledge}/{@code business.order} 的
 * Operator 同款不同身：口径同源（各 BC 自有概念，不跨上下文共享类型）。缺头
 * 即缺操作者——安装/启停必留痕（SKL_009 域面守卫拦截，知识治理同款；缺头无
 * 落空通道），卸载无行可留不留痕。</p>
 *
 * @param id   操作者标识（admin 侧 TSID 十进制字符串）
 * @param name 操作者昵称（直读展示）
 */
public record Operator(String id, String name) {

    /** 空白归一：缺头/空串/纯空白统一为 null，首尾空白去除，两肢独立。 */
    public Operator {
        id = blankToNull(id);
        name = blankToNull(name);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

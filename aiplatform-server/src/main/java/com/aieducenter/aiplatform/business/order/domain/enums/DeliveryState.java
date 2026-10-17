package com.aieducenter.aiplatform.business.order.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 前台交付六态（#338，spec #320 实施序④「生产承接」）：订单＋合同＋付款＋
 * 承接四源的<b>纯推导视图、不存储</b>——推导单点在 {@code DeliveryStatusAppService}
 * （读口 {@code GET /api/projects/{projectId}/delivery-status}），无任何落库
 * 列与迁移。优先级：需用户补充（项目级标志盖帽）＞ 生产两态 ＞ 款项核验中 ＞
 * 待付款 ＞ 待签约；订单终态（已归档/已取消）不进六态（订单面按终态呈现），
 * 待报价不覆盖（订单面「待确认报价」占位态照旧）。
 *
 * <p>REST 以 Integer code 传递（BaseEnum 约定）。非持久化枚举——不设 JPA
 * Converter（无列可转换）。</p>
 */
public enum DeliveryState implements BaseEnum<DeliveryState> {

    /** 等签约：报价已出、合同未签署完成（无合同/草稿/签署中/旁落终态待重建）。 */
    AWAITING_CONTRACT(1, "待签约"),

    /** 等付款：合同已签、开工门节点未全到账（无计划/未录节点/待支付中）。 */
    AWAITING_PAYMENT(2, "待付款"),

    /** 款项核验中：有节点处线下转账待核验（用户刚上传凭证等后台核验）。 */
    VERIFYING_PAYMENT(3, "款项核验中"),

    /** 待安排生产：开工条件满足（已支付＝双门禁过），承接待排期。 */
    AWAITING_PRODUCTION(4, "待安排生产"),

    /** 生产中：承接已推进生产（后台人工推进留痕）。 */
    IN_PRODUCTION(5, "生产中"),

    /** 需用户补充：项目级标志盖帽——需用户补资料/确认事项（处理入口在对话区）。 */
    NEEDS_USER_SUPPLEMENT(6, "需用户补充");

    private final Integer code;
    private final String name;

    DeliveryState(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }
}

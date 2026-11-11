package com.semgravio.aiplatform.base.knowledge.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 沉淀草稿状态（#460 数据飞轮采集层，照技能草稿 status 整数码先例，房规枚举落库
 * Integer code + CHECK）：1=在途（采集层人审队列待办）2=已激活（后台「数据标注」
 * 人审通过，升格知识素材——升格动作随激活链，#465）3=已拒绝（终态留档，理由必填）。
 * 终态两形均不再列在途面、不再占在途序号（部分唯一索引自然释放名额）。
 */
public enum SedimentDraftStatus implements BaseEnum<SedimentDraftStatus> {

    /** 在途：自荐留档待审——「数据标注」人审队列的待办。 */
    PENDING(1, "在途"),

    /** 已激活：人审通过，升格知识素材（与来源项目脱钩的孤儿沉淀）。 */
    ACTIVATED(2, "已激活"),

    /** 已拒绝：终态留档（理由必填），不再列活跃面，再提只能新草稿。 */
    REJECTED(3, "已拒绝");

    private final Integer code;
    private final String name;

    SedimentDraftStatus(Integer code, String name) {
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

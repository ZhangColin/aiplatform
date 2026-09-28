package com.aieducenter.aiplatform.base.skills.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 技能草稿状态（#259，ADR-0022 库制草稿）：在途＝已留档待人审（唯一活跃态，
 * 列表活跃面只收本态）；已晋升＝内容原样入技能库（终态留档，T2 写入）；已拒绝＝
 * 审核不采纳（终态留档，理由随行，再提为新草稿——拒绝不改稿不复活）。
 */
public enum SkillDraftStatus implements BaseEnum<SkillDraftStatus> {

    PENDING(1, "在途"),
    PROMOTED(2, "已晋升"),
    REJECTED(3, "已拒绝");

    private final Integer code;
    private final String name;

    SkillDraftStatus(Integer code, String name) {
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

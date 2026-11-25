package com.semgravio.aiplatform.base.knowledge.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 事后提炼处理记录状态（#641，ADR-0058；{@code knw_experience_distillations.status}）：
 * 1=已提炼（模型调用成功、窗口内有修改意见）、2=无修改意见（第一版或窗口内没有
 * 修改意见，未调用模型）、3=失败（模型失败/超时/输出不可解析等）。游标＝最近一条
 * <b>非失败</b>记录的版本号——失败不前进，下一版本自然重试失败窗口。
 */
public enum ExperienceDistillationStatus implements BaseEnum<ExperienceDistillationStatus> {

    /** 已提炼：有修改意见、模型调用成功（候选可为零条）。 */
    DISTILLED(1, "已提炼"),

    /** 无修改意见：未调用模型，游标照常前进。 */
    NO_OPINION(2, "无修改意见"),

    /** 失败：不写草稿、游标不前进，下一版本重试窗口。 */
    FAILED(3, "失败");

    private final Integer code;
    private final String name;

    ExperienceDistillationStatus(Integer code, String name) {
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

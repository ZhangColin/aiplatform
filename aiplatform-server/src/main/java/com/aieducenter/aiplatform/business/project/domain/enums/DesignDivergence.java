package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 改稿发散度三档（#294，ADR-0025 多稿语义的 chip 落地）：微调 / 探索 / 大胆
 * ——经对话或画布 chip 调、同一语义通道（改稿 prompt 携档位引导，用户自然语言
 * 「更大胆些」与 chip 同路）。档位只作用于改稿（首产无「相对上一代」的参照），
 * 是提示词语义不是代码级路由（对偶 ADR-0028「token 住提示词层」先例）。REST 面
 * 枚举类型直收（PostMessageCommand.divergence——Jackson 按名绑定，编写规范
 * §3.6.1）；code 是 domain 枚举的 BaseEnum 约定（档位序），当前无落库面。
 */
public enum DesignDivergence implements BaseEnum<DesignDivergence> {

    /** 微调：在当前方向上小幅修正——保持整体构图与风格骨架，只改用户指出的点。 */
    REFINE(1, "微调", "在当前代最优稿的方向上小幅修正——保持整体构图、配色与风格骨架，只针对用户意见指出的点改动"),

    /** 探索（缺省档）：在当前方向上尝试明显的替代方案。 */
    EXPLORE(2, "探索", "在当前方向上做实质推进——尝试明显的替代处理（换构图、调配色关系、改层次组织），但保持设计目标不变"),

    /** 大胆：打破当前方向，重新构想。 */
    REIMAGINE(3, "大胆", "打破当前代的方向重新构想——给出与既有各代差异显著的新方向，设计目标不变、手法与视觉语言可全换");

    private final Integer code;
    /** 用户面档位名（chip 文案与 prompt 引导共用单源）。 */
    private final String label;

    /** 改稿 prompt 的档位引导句（拼装归 revisionPrompt）。 */
    private final String guidance;

    DesignDivergence(Integer code, String label, String guidance) {
        this.code = code;
        this.label = label;
        this.guidance = guidance;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return label;
    }

    public String label() {
        return label;
    }

    public String guidance() {
        return guidance;
    }
}

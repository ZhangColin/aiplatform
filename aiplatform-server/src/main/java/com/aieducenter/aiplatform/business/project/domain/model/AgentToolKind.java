package com.aieducenter.aiplatform.business.project.domain.model;

import com.cartisan.core.domain.BaseEnum;

/**
 * 智能体工具的工具面类别（#252，ADR-0021 窄幅开关的判定正本）：
 * {@link #SKELETON 骨架}＝编排链路＋项目事实只读件，结构性锁死不开放关停；
 * {@link #ENHANCEMENT 增强}＝联网搜索 / 网页抓取，后台窄幅可开关；
 * {@link #HARNESS_BUILTIN harness 内建}＝框架自带编码工具（呈现口径经注册自省），
 * 平台资产之外，同样不可开关。
 */
public enum AgentToolKind implements BaseEnum<AgentToolKind> {

    SKELETON(1, "骨架（编排链路工具，结构性锁死不开放关停——ADR-0021 编排权不下放配置）"),
    ENHANCEMENT(2, "增强（窄幅可开关：关即退出槽位装配面，开即回归）"),
    HARNESS_BUILTIN(3, "harness 内建（框架自带编码工具，呈现口径——不可开关）");

    private final Integer code;
    private final String description;

    AgentToolKind(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    /** 框架约定码（BaseEnum 自动转换面；REST 呈现 kind 用枚举名字符串，code 不出面）。 */
    @Override
    public Integer getCode() {
        return code;
    }

    /** 展示名（BaseEnum 约定面；即枚举名，REST kind 呈现同值）。 */
    @Override
    public String getName() {
        return name();
    }

    public String description() {
        return description;
    }
}

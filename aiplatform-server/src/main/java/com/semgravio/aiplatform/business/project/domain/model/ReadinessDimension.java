package com.semgravio.aiplatform.business.project.domain.model;

import java.util.Optional;

import com.cartisan.core.domain.BaseEnum;

/**
 * 收敛度五维词表（#331，spec #320 实施序③「收敛度呈现」）：维度清单＝生成条件
 * 五要素（目标与角色/主流程/功能与边界/数据与约束/验收），与缺项分级共用同一套
 * 维度判定——「验收与待确认」章条目的【维度】标注即本词表（判定语义归主智能体
 * 提示词：prd-writing 技能钉条目前导【维度】写法，词表与本文逐字同源——跨层
 * 同源对先例＝DesignChecklist 与前端 feature-list.ts，#285）。REST 投影用
 * {@link #label()}（呈现词表、封闭五值，前端零映射直渲染）。BaseEnum 实现是
 * 仓规架构约束（domain 枚举自动 Integer 转换），本词表本身不落库不经码传输。
 */
public enum ReadinessDimension implements BaseEnum<ReadinessDimension> {

    GOAL_ROLE(1, "目标与角色"),
    MAIN_FLOW(2, "主流程"),
    FEATURE_BOUNDARY(3, "功能与边界"),
    DATA_CONSTRAINT(4, "数据与约束"),
    ACCEPTANCE(5, "验收");

    private final Integer code;
    private final String label;

    ReadinessDimension(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 框架约定码（domain 枚举架构规则；本词表不经 REST 不经落库，码为登记制）。 */
    @Override
    public Integer getCode() {
        return code;
    }

    /** 框架约定名（同 {@link #label()}——BaseEnum 双方法形制）。 */
    @Override
    public String getName() {
        return label;
    }

    /** 呈现词（中文五要素名——提示词侧【维度】标注与 REST 投影共用正本）。 */
    public String label() {
        return label;
    }

    /** 按呈现词精确解析（条目前导【…】标注内容 trim 后匹配；不匹配返回空——有限宽容）。 */
    public static Optional<ReadinessDimension> byLabel(String label) {
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        String normalized = label.trim();
        for (ReadinessDimension dimension : values()) {
            if (dimension.label.equals(normalized)) {
                return Optional.of(dimension);
            }
        }
        return Optional.empty();
    }
}

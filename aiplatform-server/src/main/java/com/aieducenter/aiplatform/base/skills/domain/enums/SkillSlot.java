package com.aieducenter.aiplatform.base.skills.domain.enums;

import java.util.Locale;
import java.util.Optional;

import com.cartisan.core.domain.BaseEnum;

/**
 * 职能槽位（#249 指派模型，ADR-0021）：技能指派的全局配置单元——平台智能体
 * 的职能面（主智能体 / run 执行体 / 子智能体），各自独立多选、不分项目。槽位
 * 是<b>键不是身份</b>：智能体身份仍以 business 侧 {@code AgentProfile} 枚举为
 * 正本（ADR-0006 身份与配置分治），本枚举只固化指派面的三把稳定键——business
 * 装配缝按配置键对齐到此（main/executor 与 AgentProfile 键同串、subagent 是
 * 子智能体槽的键，self-test 是其当前唯一职能实例）。code 是框架约定码
 * （BaseEnum 自动转换面；槽位落库与 REST 寻址均用字符串键，code 不经任何面）。
 *
 * <p>新增职能槽位＝新增枚举值＋迁移扩键面（REST 槽位路径段即本键，未知键
 * 404 SKL_010）。</p>
 */
public enum SkillSlot implements BaseEnum<SkillSlot> {

    /** 主智能体（需求侧：访谈梳理＋PRD 撰写——技能面挂内置 PRD 写作）。 */
    MAIN(1, "main"),

    /** run 执行体（实现侧：读写工作区、跑命令——安装技能的主消费槽位）。 */
    EXECUTOR(2, "executor"),

    /** 子智能体槽（当前唯一实例 self-test——技能面独立收窄配置，不继承执行体）。 */
    SUBAGENT(3, "subagent");

    private final Integer code;
    private final String key;

    SkillSlot(Integer code, String key) {
        this.code = code;
        this.key = key;
    }

    /** 框架约定码（BaseEnum 自动转换面；不落库不经 REST——寻址用字符串键）。 */
    @Override
    public Integer getCode() {
        return code;
    }

    /** 展示名（BaseEnum 约定面；REST 读面回显槽位用字符串键，name 不出面）。 */
    @Override
    public String getName() {
        return key;
    }

    /** 槽位稳定键（REST 路径段与装配缝寻址共用）。 */
    public String key() {
        return key;
    }

    /**
     * 按稳定键解析槽位（大小写不敏感）：空键/未知键返回空，由调用方定 404 语义
     * （SKL_010）。
     */
    public static Optional<SkillSlot> byKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(SkillSlot.valueOf(key.trim().toUpperCase(Locale.ROOT)));
        }
        catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

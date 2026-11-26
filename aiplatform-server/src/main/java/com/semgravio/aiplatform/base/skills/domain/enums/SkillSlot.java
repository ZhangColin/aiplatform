package com.semgravio.aiplatform.base.skills.domain.enums;

import java.util.Locale;
import java.util.Optional;

import com.cartisan.core.domain.BaseEnum;

/**
 * 职能槽位（#249 指派模型，ADR-0021）：技能指派的全局配置单元——平台智能体的
 * 职能面（{@link #MAIN 主智能体} / {@link #EXECUTOR run 执行体}），各自独立多选、
 * 不分项目。槽位是<b>键不是身份</b>：智能体身份仍以 business 侧
 * {@code AgentProfile} 枚举为正本（ADR-0006 身份与配置分治），本枚举只固化指派面
 * 的稳定键——business 装配缝按配置键对齐到此（main/executor 与 AgentProfile 键
 * 同串）。code 是框架约定码（BaseEnum 自动转换面；槽位落库与 REST 寻址均用字符串
 * 键，code 不经任何面）。设计执行体槽（designer）随 #610 设计线整条退役；子智能体槽
 * （subagent）随 #604 委派机制退役、存量指派行由 #638 阶段六技能清场迁移删除。</p>
 *
 * <p>新增职能槽位＝新增枚举值＋迁移扩键面（REST 槽位路径段即本键，未知键
 * 404 SKL_010），并按该槽位运行时有无 shell 拍板 {@link #hasShell()} 位。</p>
 *
 * <p>scripts 资源开放面（#253，ADR-0021 内容面 c）：开放面＝<b>有 shell 的
 * 槽位</b>——{@link #hasShell()} 为真的槽位装配面才附 scripts 资源（框架
 * {@code AgentSkill.resources} 通路）；主智能体 ProjectReadOnly 禁 shell——
 * 结构性 a-only（装配出的技能 resources 恒空，load 工具枚举无 scripts 入口）。
 * executor 容器内 shell——开放。</p>
 */
public enum SkillSlot implements BaseEnum<SkillSlot> {

    /** 主智能体（需求侧：访谈梳理＋PRD 撰写——技能面挂内置 PRD 写作；禁 shell）。 */
    MAIN(1, "main", false),

    /** run 执行体（实现侧：读写工作区、跑命令——安装技能的主消费槽位；有 shell）。
     * #638 起生成内核结构性不挂技能，本槽暂不接通，保留给以后按准入标准接通。 */
    EXECUTOR(2, "executor", true);

    private final Integer code;
    private final String key;
    private final boolean shell;

    SkillSlot(Integer code, String key, boolean shell) {
        this.code = code;
        this.key = key;
        this.shell = shell;
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
     * 该槽位运行时有无 shell（#253 scripts 开放面判定正本）：真＝装配面附
     * scripts 资源；假＝结构性 a-only（resources 恒空）。
     */
    public boolean hasShell() {
        return shell;
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

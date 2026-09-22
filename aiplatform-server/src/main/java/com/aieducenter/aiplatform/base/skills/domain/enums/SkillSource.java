package com.aieducenter.aiplatform.base.skills.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 技能来源（#246，CONTEXT.md「技能」词条来源三途）：内置（classpath 合成——
 * 随平台发版，非库行）／安装（外部技能仓库装时固化入库——行即安装）。不落库
 * 列：行在 {@code skl_skills} 即安装、classpath 即内置，来源由出处分解非冗余
 * 存储；自产（#244 自学习闭环）到来时来源才需要列化区分。
 */
public enum SkillSource implements BaseEnum<SkillSource> {

    BUILTIN(1, "内置"),

    INSTALLED(2, "安装");

    private final Integer code;
    private final String name;

    SkillSource(Integer code, String name) {
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

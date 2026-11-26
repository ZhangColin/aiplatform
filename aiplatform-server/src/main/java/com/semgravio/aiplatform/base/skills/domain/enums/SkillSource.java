package com.semgravio.aiplatform.base.skills.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 技能来源（#246，CONTEXT.md「技能」词条来源三途）：内置（classpath 合成——
 * 随平台发版，非库行）／安装（外部技能仓库装时固化入库）／自产（#262 晋升
 * 写入，与安装行同库同权；#638 阶段六技能清场后草稿与晋升端点退场，本值保留为
 * 历史读值、不再产生新行）。列化（V23）：库行 {@code skl_skills}
 * .source 落 INSTALLED/SELF_PRODUCED 两值（CHECK 钉死——内置无行，BUILTIN
 * 只在清单/详情读模型合成）；存量行回填 INSTALLED。
 */
public enum SkillSource implements BaseEnum<SkillSource> {

    BUILTIN(1, "内置"),

    INSTALLED(2, "安装"),

    SELF_PRODUCED(3, "自产");

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

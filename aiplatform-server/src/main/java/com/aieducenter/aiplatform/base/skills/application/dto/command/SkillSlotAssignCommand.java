package com.aieducenter.aiplatform.base.skills.application.dto.command;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 槽位指派命令（#249，PUT 全量语义）：该槽位的完整技能清单——整包替换（清单
 * 即终态，未列入即解绑），支持整包批量勾选（admin 侧按来源包勾满后送全量）。
 * 指派目标只收安装库行 TSID 柄：内置技能不可指派（随平台发版，装配合成按配置
 * 挂载——SKL_011）。字段合法性由指派用例裁决（SKL_001 未寻址、SKL_011 内置
 * 柄），照 {@code SkillInstallCommand} 形制。
 *
 * @param skillIds 技能柄清单（安装行 TSID 十进制串；空清单＝清空该槽位）
 */
public record SkillSlotAssignCommand(
        @Schema(description = "指派技能柄全量清单（安装行 TSID 十进制串，整包替换语义——"
                + "清单即终态，未列入即解绑；空清单＝清空该槽位；内置 builtin: 柄不可"
                + "指派 400 SKL_011）",
                example = "[\"7600000000001\", \"7600000000003\"]")
        List<String> skillIds) {
}

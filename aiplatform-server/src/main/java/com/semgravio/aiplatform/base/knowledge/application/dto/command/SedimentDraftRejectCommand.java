package com.semgravio.aiplatform.base.knowledge.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 沉淀草稿拒绝命令（#464「数据标注」三键之一）：理由必填（终态留档拒绝须有据，
 * 库级 CHECK 锁死）。字段合法性由拒绝用例裁决（KNW_010 空），不在命令层重复
 * 校验——照 {@code SkillDraftRejectCommand} 形制。
 *
 * @param reason 拒绝理由（随终态草稿留档，审核面可查；trim 后非空）
 */
public record SedimentDraftRejectCommand(
        @Schema(description = "拒绝理由（终态留档可查——拒绝须有据；空白即 400 KNW_010）",
                example = "与已激活知识「排课冲突案例」内容重叠，无增量价值")
        String reason) {
}

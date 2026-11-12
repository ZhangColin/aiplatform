package com.semgravio.aiplatform.base.knowledge.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 沉淀草稿改标签命令（#464「数据标注」三键之一）：调整类别（一期合法取值
 * CASE/MODULE/RULE——写入校验同口径）。字段合法性由改标签用例裁决（KNW_011
 * 非法类别），不在命令层重复校验——后台命令 DTO 统一形制（空壳记录＋Schema
 * 注解，校验归用例）。
 *
 * @param kind 目标类别键（大小写不敏感，归一到大写稳定键；同类别幂等 no-op）
 */
public record SedimentDraftRelabelCommand(
        @Schema(description = "目标沉淀物类别（CASE=需求案例 MODULE=功能模块 RULE=业务规则；"
                + "大小写不敏感；非法取值 400 KNW_011）", example = "MODULE")
        String kind) {
}

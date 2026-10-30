package com.semgravio.aiplatform.business.project.application.dto.command;

/**
 * 生产承接动作命令（#338，推进共用）：依据＝人工推进留痕的必填自由文本
 * （操作人/时间由服务端随行落）。合法性由聚合守卫裁决（PRJ_056/057/059），
 * 不在命令层重复校验。
 *
 * @param basis 依据（必填，至多 1000 字——append-only 留痕行的正身）
 */
public record ProductionHandoffActionCommand(
        String basis
) {
}

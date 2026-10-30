package com.semgravio.aiplatform.business.project.application.dto.command;

import java.time.LocalDateTime;

/**
 * 生产承接信息更新命令（#338 后台机机面，「生产承接更新」之字段面）：整组
 * 替换语义——未录入段传 null 即清空（预计开始时间清空＝回待安排呈现）。
 * 字段合法性由聚合守卫裁决（PRJ_057/058/059），不在命令层重复校验。
 *
 * @param contact          交付联系人（可空，至多 200 字）
 * @param nextStep         下一步说明（人工措辞，可空，至多 1000 字）
 * @param progressNote     进展更新（人工措辞、不上工程术语，可空，至多 2000 字）
 * @param estimatedStartAt 预计开始时间（可空＝待安排——仅录入真实值才显示）
 * @param basis            依据（必填，至多 1000 字——append-only 留痕行的正身）
 */
public record UpdateProductionHandoffCommand(
        String contact,
        String nextStep,
        String progressNote,
        LocalDateTime estimatedStartAt,
        String basis
) {
}

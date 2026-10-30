package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.Instant;

/**
 * PRD 读响应（#41；#331 增收敛度读面；#334 增差异摘要读面）：当前版 PRD——
 * markdown 正文 + 更新时间 + 「验收与待确认」章分级解析投影 + 范围级草稿停驻的
 * 差异摘要。事实源是项目 dev 工作区的 {@code docs/PRD.md}（本端点直读，读不到 =
 * 未产出 404 PRJ_015，不在此载体表达）；readiness 对正文即时解析（不落库，确认门
 * 与收敛卡同源判定——草稿随对话演进、投影随之）。
 *
 * @param projectId          项目标识（TSID 十进制字符串）
 * @param content            markdown 正文（工作区文件原样）
 * @param updatedAt          最近写出时间（工作区文件 mtime，秒精度——与正文同一事实源）
 * @param readiness          收敛度投影（#331：分级缺项＋五维维度态；章缺失 fail-open 全就绪）
 * @param draftChangeSummary 差异摘要（#334 改需求回路：范围级意见改稿时产出的 markdown
 *                           变更说明——savePrd 的 summary 终值，停驻待确认；null = 无
 *                           新草稿待确认。对话区提示与确认门摘要层的数据面）
 */
public record PrdResponse(
        String projectId,
        String content,
        Instant updatedAt,
        PrdReadinessResponse readiness,
        String draftChangeSummary
) {
}

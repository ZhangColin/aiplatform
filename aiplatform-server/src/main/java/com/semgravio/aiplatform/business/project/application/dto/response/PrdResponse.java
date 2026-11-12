package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.Instant;

/**
 * PRD 读响应（#41；#331 增收敛度读面）：当前版 PRD——markdown 正文 + 更新时间 +
 * 「验收与待确认」章解析投影。事实源是项目 dev 工作区的 {@code docs/PRD.md}
 * （本端点直读，读不到 = 未产出 404 PRJ_015，不在此载体表达）；readiness 对正文
 * 即时解析（不落库——草稿随对话演进、投影随之）。<b>#462 去确认门</b>：原
 * draftChangeSummary（#334 停驻差异摘要读面）随停驻机制整段退役（列留库不删、
 * 读面摘除）。
 *
 * @param projectId 项目标识（TSID 十进制字符串）
 * @param content   markdown 正文（工作区文件原样）
 * @param updatedAt 最近写出时间（工作区文件 mtime，秒精度——与正文同一事实源）
 * @param readiness 收敛度投影（#331：缺项清单＋五维维度态；章缺失 fail-open 全就绪）
 */
public record PrdResponse(
        String projectId,
        String content,
        Instant updatedAt,
        PrdReadinessResponse readiness
) {
}

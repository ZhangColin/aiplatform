package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

/**
 * Demo 版本清单条目（#333 轻量形——版本记录抽屉 #344 与版本 chip 的数据面）：
 * 版本号＋PRD 版本锚（「Demo vN（基于 PRD vM 快照）」式关联）＋收口 run 锚；
 * {@code confirmedAt} 仅确认标记所在版非空（标记归项目单指针，清单投影到行）。
 */
public record DemoVersionSummaryResponse(
        String projectId,
        int versionNo,
        Integer prdVersionNo,
        String runId,
        LocalDateTime createdAt,
        LocalDateTime confirmedAt) {
}

package com.semgravio.aiplatform.business.project.application.dto.response;

/**
 * 回到某版（#627 异步回滚）的受理回执：回滚是平台侧异步动作（restore → 停进程 →
 * 重新 build → 起服 → 探活 → 记新版本 vN+1），POST 立即返回本场运行标识——
 * 前端挂 SSE 的锚（进度经工作消息起止呈现，失败经 run-failed）。{@code label}
 * 是版本标签（成功成版的主题「回到 vN」；前端可作「正在回到 vN」提示）。
 */
public record VersionRollbackStartResponse(
        String runId,
        String label) {
}

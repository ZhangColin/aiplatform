package com.semgravio.aiplatform.business.project.domain.decisionhint;

import java.util.List;

/**
 * ReAct 单迭代视图（轨迹快照成员，#437）：一次「模型响应（assistant 文本＋工具
 * 调用）」的框架中立形。并行工具调用（同响应多 ToolUseBlock）在 calls 内保持
 * 同迭代——并行度信号（每迭代调用数）由此可判。
 *
 * @param assistantText 该迭代的 assistant 文本块拼接（反 mock 信号扫描面之一）
 * @param calls         该迭代发起的工具调用结果（顺序保持）
 */
public record Iteration(String assistantText, List<ToolCallOutcome> calls) {
}

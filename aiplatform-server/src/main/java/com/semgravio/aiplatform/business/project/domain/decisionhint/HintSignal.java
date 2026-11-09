package com.semgravio.aiplatform.business.project.domain.decisionhint;

/**
 * 分类信号（#437 判定缝输出）：某库条目的触发判定＋理由文本（理由进注入正文
 * 与观测日志——用户可见面与成本口径同据）。
 *
 * @param kind   命中的库条目
 * @param reason 触发理由（含量化事实，如连续失败次数／命中模式／结果体量）
 */
public record HintSignal(DecisionHintKind kind, String reason) {
}

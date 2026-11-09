package com.semgravio.aiplatform.business.project.domain.decisionhint;

import java.util.Optional;

/**
 * 注入决策（#437 选指令缝输出）：本轮要注入的一条微指令——库条目＋理由＋渲染
 * 好的正文（模板 {@code {0}} 已由理由填充）。单注入位：每迭代至多一条。
 *
 * @param kind        注入的库条目
 * @param reason      触发理由（量化事实——观测日志与决策回放同据）
 * @param instruction 注入模型的微指令正文（库模板渲染后形）
 */
public record HintDecision(DecisionHintKind kind, String reason, String instruction) {

    /** 库模板渲染：{@code {0}} 占位由理由填充（理由即量化事实文本）。 */
    static HintDecision of(HintSignal signal) {
        return new HintDecision(signal.kind(), signal.reason(),
                signal.kind().instructionTemplate().replace("{0}", signal.reason()));
    }
}

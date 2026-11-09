package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.semgravio.aiplatform.business.project.domain.decisionhint.Iteration;
import com.semgravio.aiplatform.business.project.domain.decisionhint.ToolCallOutcome;
import com.semgravio.aiplatform.business.project.domain.decisionhint.TrajectorySnapshot;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;

/**
 * 轨迹快照提取（#437）：agentscope 消息列 → 判定缝框架中立形（{@link
 * TrajectorySnapshot}）。分组口径：
 * <ul>
 * <li>assistant 消息开一个迭代——TextBlock 拼成 assistantText、ToolUseBlock
 * 按序进 calls；同一响应的并行调用自然同迭代（并行度信号的可判性前提）；</li>
 * <li>TOOL 消息的 ToolResultBlock 按 id 归位到对应调用（结果文本/字符数/失败
 * 态；state≠SUCCESS 归失败——ERROR/INTERRUPTED/DENIED 同口径）；</li>
 * <li>真实用户消息（非合成）是<b>轮界</b>：迭代与注入键都清空重计——跨段信号
 * （如上一段的失败 streak）不带进本轮判定；</li>
 * <li>决策提示合成消息（{@value #HINT_KIND_METADATA_KEY} 元数据）进注入键；
 * 其余合成消息（框架 TaskReminder 等）既不进迭代也不当轮界——与既有呈现面
 * 同口径（合成消息无用户面事件）。</li>
 * </ul>
 */
final class TrajectorySnapshots {

    /** 决策提示合成消息的库键元数据（注入时写入、提取时回读——堆叠语义的载体）。 */
    static final String HINT_KIND_METADATA_KEY = "decision_hint_kind";

    private TrajectorySnapshots() {
    }

    /** 消息列 → 轨迹快照（当前轮口径：轮界之前的迭代与注入键均不计）。 */
    static TrajectorySnapshot from(List<Msg> messages) {
        List<Iteration> iterations = new ArrayList<>();
        List<String> injectedKeys = new ArrayList<>();
        if (messages == null) {
            return new TrajectorySnapshot(iterations, injectedKeys);
        }
        Map<String, ToolResultBlock> resultsById = collectResults(messages);
        for (Msg msg : messages) {
            if (msg == null || msg.getRole() == null) {
                continue;
            }
            if (msg.getRole() == MsgRole.USER) {
                Object hintKind = msg.getMetadata() == null
                        ? null : msg.getMetadata().get(HINT_KIND_METADATA_KEY);
                if (hintKind != null) {
                    injectedKeys.add(String.valueOf(hintKind));
                }
                else if (!isSynthetic(msg)) {
                    // 真实用户消息＝轮界：跨轮迭代与注入键不带进本轮判定
                    iterations.clear();
                    injectedKeys.clear();
                }
                continue;
            }
            if (msg.getRole() == MsgRole.ASSISTANT) {
                iterations.add(toIteration(msg, resultsById));
            }
        }
        return new TrajectorySnapshot(List.copyOf(iterations), List.copyOf(injectedKeys));
    }

    /** TOOL 消息的 ToolResultBlock 按调用 id 建索引（未匹配调用 id 的结果忽略）。 */
    private static Map<String, ToolResultBlock> collectResults(List<Msg> messages) {
        Map<String, ToolResultBlock> resultsById = new HashMap<>();
        for (Msg msg : messages) {
            if (msg.getRole() != MsgRole.TOOL) {
                continue;
            }
            for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                resultsById.put(result.getId(), result);
            }
        }
        return resultsById;
    }

    /** assistant 消息 → 单迭代（文本＋调用结果归位）。 */
    private static Iteration toIteration(Msg msg, Map<String, ToolResultBlock> resultsById) {
        StringBuilder assistantText = new StringBuilder();
        List<ToolCallOutcome> calls = new ArrayList<>();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock text) {
                assistantText.append(text.getText());
            }
            else if (block instanceof ToolUseBlock use) {
                calls.add(toOutcome(use, resultsById.get(use.getId())));
            }
        }
        return new Iteration(assistantText.toString(), List.copyOf(calls));
    }

    /**
     * 工具调用＋结果（可空——结果未到达的调用按失败面处理；结果到达但 state 缺省
     * 同归失败——非 SUCCESS 即失败，与「未到达」对称）→ 调用结果事实。
     */
    private static ToolCallOutcome toOutcome(ToolUseBlock use, ToolResultBlock result) {
        if (result == null) {
            return new ToolCallOutcome(use.getName(), true, null, 0);
        }
        String outputText = textOf(result.getOutput());
        boolean failed = result.getState() != ToolResultState.SUCCESS;
        return new ToolCallOutcome(use.getName(), failed, outputText, outputText.length());
    }

    /** 输出块列的文本拼接（TextBlock 拼接，其余块类型忽略——判定只看文本面）。 */
    private static String textOf(List<ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    /** 合成消息判别：框架合成标记（TaskReminder/决策提示共用的 agentscope_synthetic）。 */
    private static boolean isSynthetic(Msg msg) {
        return msg.getMetadata() != null
                && Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC));
    }
}

package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintKind;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHints;
import com.semgravio.aiplatform.business.project.domain.decisionhint.HintPolicy;
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

    /**
     * 判定门（#453-3 降频件）：廉价预检——门断定四条规则在当前消息列上都<b>不可能</b>
     * 产出信号时，中间件跳过重评估（全量快照提取是 O(本轮消息 × 结果字节) 的重活
     * ——每迭代把全列工具结果文本重新拷贝一遍，单会话长段是平方级放大，本专项
     * 残差首嫌）。门的唯一语义是计算降频：门跳过 ⇔ {@link DecisionHints#decide}
     * 必返空（信号不可能存在），注入行为与门无关；任一规则可能命中即放行走全量
     * 评估（保守方向单向——门只许漏跳过，不许漏放行）。镜像各规则的命中条件，
     * 阈值/窗口/模式常量同源引用 {@link DecisionHints} 公开常量（口径漂移即
     * 门失效——缝测对齐）：
     * <ul>
     * <li>失败止损：尾部最近一批调用全部成功（结果在列且 state=SUCCESS）⇒ 扫描
     * 首个即被成功截断，信号不可能；任一无结果/失败即放行（纯文本迭代不截断——
     * 同规则扫描零调用迭代跳过不 break 的口径）；</li>
     * <li>反 mock：近 {@value DecisionHints#ANTI_MOCK_WINDOW} 个迭代的助手文本或
     * 成功结果文本命中模式即放行（有界扫描——只摸窗口内文本）；</li>
     * <li>上下文经济：最近迭代存在成功结果 &gt; {@value DecisionHints#LARGE_RESULT_CHARS}
     * 字符，或本轮累计成功结果 &gt; {@value DecisionHints#CUMULATIVE_RESULT_CHARS}
     * 字符即放行（累计判据 O(n) 但只量长度不拷贝文本——常数极小）；</li>
     * <li>并行工具：尾部连续恰 1 调用的迭代 streak 达 {@value DecisionHints#SINGLE_CALL_STREAK}
     * 即放行（有界扫描）。</li>
     * </ul>
     *
     * <p><b>维护约束（Divergent Change 防呆）</b>：本门镜像的是 {@link DecisionHints}
     * 当前四条规则——<b>规则族新增第五条规则时必须同步扩展本门</b>，否则新信号
     * 被门跳过＝静默行为回归；门的单向保守（只许漏跳过）在此失效方向恰恰致命。</p>
     *
     * @return true＝可能产出信号（走全量评估）；false＝判定必空（可安全跳过）
     */
    static boolean mayProduceSignal(List<Msg> messages, HintPolicy policy) {
        if (policy == null) {
            return true; // 无策略不预判（保守放行——fail-open 口径）
        }
        boolean stopLossEnabled =
                !policy.disabledKinds().contains(DecisionHintKind.FAILURE_STOP_LOSS);
        boolean antiMockEnabled = !policy.disabledKinds().contains(DecisionHintKind.ANTI_MOCK);
        boolean economyEnabled =
                !policy.disabledKinds().contains(DecisionHintKind.CONTEXT_ECONOMY);
        boolean parallelEnabled =
                !policy.disabledKinds().contains(DecisionHintKind.PARALLEL_TOOLS);
        if (!stopLossEnabled && !antiMockEnabled && !economyEnabled && !parallelEnabled) {
            return false; // 全禁用——判定必空
        }
        if (messages == null || messages.isEmpty()) {
            return false; // 空列——空快照必无信号
        }
        // 当前轮切片：最近一条真实用户消息（轮界）之后（镜像 from 的清栈口径）
        int roundStart = roundStartOf(messages);
        List<Msg> assistants = new ArrayList<>();
        Map<String, ToolResultBlock> resultsById = new HashMap<>();
        for (int i = roundStart; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            if (msg == null || msg.getRole() == null) {
                continue;
            }
            if (msg.getRole() == MsgRole.ASSISTANT) {
                assistants.add(msg);
            }
            else if (msg.getRole() == MsgRole.TOOL) {
                for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                    resultsById.put(result.getId(), result);
                }
            }
        }
        if (stopLossEnabled && stopLossPossible(assistants, resultsById)) {
            return true;
        }
        if (antiMockEnabled && antiMockPossible(assistants, resultsById)) {
            return true;
        }
        if (economyEnabled && economyPossible(assistants, resultsById)) {
            return true;
        }
        return parallelEnabled && parallelPossible(assistants);
    }

    /** 轮界定位：最近一条非合成 USER 消息之后（更早迭代与注入键不带进本轮判定）。 */
    private static int roundStartOf(List<Msg> messages) {
        int roundStart = 0;
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            if (msg != null && msg.getRole() == MsgRole.USER && !isSynthetic(msg)) {
                roundStart = i + 1;
            }
        }
        return roundStart;
    }

    /**
     * 失败止损门：尾部最近一个「有调用的迭代」的调用全部成功（结果在列且
     * state=SUCCESS）⇒ 规则扫描自尾首个调用即被成功截断，信号不可能；
     * 存在无结果/失败调用即放行（保守——同批混合成败时放行不漏）。
     */
    private static boolean stopLossPossible(List<Msg> assistants,
            Map<String, ToolResultBlock> resultsById) {
        for (int i = assistants.size() - 1; i >= 0; i--) {
            List<ToolUseBlock> calls = assistants.get(i).getContentBlocks(ToolUseBlock.class);
            if (calls.isEmpty()) {
                continue; // 纯文本迭代：规则扫描跳过（零调用不 break）
            }
            for (ToolUseBlock call : calls) {
                ToolResultBlock result = resultsById.get(call.getId());
                if (result == null || result.getState() != ToolResultState.SUCCESS) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /** 反 mock 门：近窗口迭代（助手文本、成功结果文本）命中模式即放行。 */
    private static boolean antiMockPossible(List<Msg> assistants,
            Map<String, ToolResultBlock> resultsById) {
        int from = Math.max(0, assistants.size() - DecisionHints.ANTI_MOCK_WINDOW);
        for (int i = from; i < assistants.size(); i++) {
            Msg msg = assistants.get(i);
            if (firstMockPattern(assistantTextOf(msg)) != null) {
                return true;
            }
            for (ToolUseBlock call : msg.getContentBlocks(ToolUseBlock.class)) {
                ToolResultBlock result = resultsById.get(call.getId());
                if (result != null && result.getState() == ToolResultState.SUCCESS
                        && firstMockPattern(textOf(result.getOutput())) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 上下文经济门：最近迭代单次 oversized（成功结果 &gt; {@value DecisionHints#LARGE_RESULT_CHARS}）
     * 或本轮累计成功结果超 {@value DecisionHints#CUMULATIVE_RESULT_CHARS} 即放行。
     * 累计只量长度不拷贝文本（与快照提取的字节拷贝不同——本门的降频要点）。
     */
    private static boolean economyPossible(List<Msg> assistants,
            Map<String, ToolResultBlock> resultsById) {
        long cumulativeChars = 0;
        for (Msg msg : assistants) {
            for (ToolUseBlock call : msg.getContentBlocks(ToolUseBlock.class)) {
                ToolResultBlock result = resultsById.get(call.getId());
                if (result != null && result.getState() == ToolResultState.SUCCESS) {
                    cumulativeChars += textOf(result.getOutput()).length();
                }
            }
        }
        if (cumulativeChars > DecisionHints.CUMULATIVE_RESULT_CHARS) {
            return true;
        }
        if (assistants.isEmpty()) {
            return false;
        }
        Msg last = assistants.get(assistants.size() - 1);
        for (ToolUseBlock call : last.getContentBlocks(ToolUseBlock.class)) {
            ToolResultBlock result = resultsById.get(call.getId());
            if (result != null && result.getState() == ToolResultState.SUCCESS
                    && textOf(result.getOutput()).length() > DecisionHints.LARGE_RESULT_CHARS) {
                return true;
            }
        }
        return false;
    }

    /** 并行工具门：尾部连续恰 1 调用的迭代 streak 达阈值即放行（有界扫描）。 */
    private static boolean parallelPossible(List<Msg> assistants) {
        int streak = 0;
        for (int i = assistants.size() - 1; i >= 0; i--) {
            if (assistants.get(i).getContentBlocks(ToolUseBlock.class).size() != 1) {
                break;
            }
            streak++;
            if (streak >= DecisionHints.SINGLE_CALL_STREAK) {
                return true;
            }
        }
        return false;
    }

    /** 助手消息文本（TextBlock 拼接——镜像 toIteration 的 assistantText 口径）。 */
    private static String assistantTextOf(Msg msg) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    /** 首个命中的 mock 模式（镜像 {@link DecisionHints} 反 mock 规则的小写包含匹配）。 */
    private static String firstMockPattern(String text) {
        if (text == null) {
            return null;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String pattern : DecisionHints.ANTI_MOCK_PATTERNS) {
            if (lowered.contains(pattern)) {
                return pattern;
            }
        }
        return null;
    }
}

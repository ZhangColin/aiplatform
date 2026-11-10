package com.semgravio.aiplatform.business.project.domain.decisionhint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 决策时微指令分类器＋选择器（#437，#432 Testing Decisions 预定的独立单测缝——
 * 判定/选指令/堆叠上限纯函数，无状态、零模型调用）。分类器＝确定性规则族：
 * 每条规则从轨迹快照提取信号（命中即携理由）；选择器＝在命中信号里按「未注入
 * 过＋槽位空余」取优先级最高的一条。堆叠语义全量由快照的 injectedHintKeys
 * （消息列回读）承载——不依赖进程内状态，压缩/中断后行为可重放。
 *
 * <p>为何规则分类器而非 flash 模型分类器（报告口径）：AC 要求判定纯函数化＋
 * 独立单测，且每迭代一次 LLM 调用的墙钟与成本恰恰对抗本票 ≤60min 目标；Replit
 * 实测增益来自「决策时」而非「模型化」，规则判定以零墙钟零 token 拿到同点位。
 * 模型分类器留待规则族收益证伪后再评（SPI 面即本类入参的轨迹快照）。</p>
 */
public final class DecisionHints {

    /** 失败错误签名归一化后比较：首行、去空白、小写（仅大小写/尾部空白差异＝同类）。 */
    static final String STOP_LOSS_SIGNATURE_SEPARATOR = "|";

    private DecisionHints() {
    }

    /**
     * 判定：轨迹快照 → 命中信号清单（按库优先级排序；禁用集条目不产出信号）。
     * 规则族（首批四条，各自独立命中、可同轮并存——并存与否由选指令层的单注入
     * 位与堆叠上限裁决）：
     * <ul>
     * <li>失败止损——尾部连续失败调用且错误签名一致 ≥ 阈值；签名＝工具名＋
     * 错误首行归一化（换工具或换错误内容即换序列）；成功调用截断序列。</li>
     * <li>反 mock 数据——近窗口迭代的助手文本/工具结果出现占位/mock 痕迹
     * （lorem/placeholder/TODO 等封闭模式）。</li>
     * <li>上下文经济性——单次超大结果（字符数超阈）或本段累计成功结果超阈
     * （#443 补累计判据：单会话长段的膨胀主源是多次中量读取的累积）即命中。</li>
     * <li>并行工具调用——尾部连续每迭代恰 1 个调用的迭代数超阈即命中。</li>
     * </ul>
     */
    public static List<HintSignal> classify(TrajectorySnapshot snapshot, HintPolicy policy) {
        List<HintSignal> signals = new ArrayList<>();
        if (snapshot == null || snapshot.iterations() == null || policy == null) {
            return signals;
        }
        addStopLossSignal(snapshot, policy, signals);
        addAntiMockSignal(snapshot, policy, signals);
        addContextEconomySignal(snapshot, policy, signals);
        addParallelToolsSignal(snapshot, policy, signals);
        signals.sort(Comparator.comparingInt(signal -> signal.kind().priority()));
        return signals;
    }

    /**
     * 选指令：命中信号 → 至多一条注入决策。堆叠语义（研究口径 ≤3，再堆收益
     * 递减转负——按<b>实际堆叠在上下文里的条数</b>算：注入过的提示消息不因
     * 条件清除而消失，故堆叠数＝本会话已注入条数）：
     * <ul>
     * <li>本会话已注入的键不重灌——即使条件曾清除后重新命中（防振荡，注入是
     * 轮级一次性提醒，不是常驻指令）；</li>
     * <li>已注入条数达上限即拒绝一切新注入（等下一轮/下一段的自然清栈）；</li>
     * <li>槽位空余时取优先级最高的新鲜信号（单注入位——每迭代至多一条）。</li>
     * </ul>
     */
    public static Optional<HintDecision> select(List<HintSignal> signals,
            List<String> injectedHintKeys, HintPolicy policy) {
        if (signals == null || signals.isEmpty() || policy == null) {
            return Optional.empty();
        }
        Set<String> injected = new HashSet<>(
                injectedHintKeys == null ? List.of() : injectedHintKeys);
        if (injected.size() >= policy.stackCap()) {
            return Optional.empty();
        }
        return signals.stream()
                .filter(signal -> !injected.contains(signal.kind().key()))
                .min(Comparator.comparingInt(signal -> signal.kind().priority()))
                .map(HintDecision::of);
    }

    /** 判定＋选指令一体入口（中间件唯一调用点）：快照＋策略 → 注入决策。 */
    public static Optional<HintDecision> decide(TrajectorySnapshot snapshot, HintPolicy policy) {
        return select(classify(snapshot, policy),
                snapshot == null ? null : snapshot.injectedHintKeys(), policy);
    }

    /** 反 mock 扫描窗口：近期 N 个迭代（更早痕迹不追打——避免栈位被旧账长占）。 */
    static final int ANTI_MOCK_WINDOW = 3;

    /** 反 mock 信号模式（小写包含匹配，保守集——误报代价是打断心流）。 */
    static final List<String> ANTI_MOCK_PATTERNS = List.of(
            "lorem ipsum", "lorem", "placeholder", "todo:", "fixme", "dummy", "mock data");

    /** 单次结果「过大」阈值（字符）：超过即建议窄读（约 20k 字符 ≈ 5k token 量级）。 */
    static final int LARGE_RESULT_CHARS = 20_000;

    /**
     * 累计成功结果阈值（字符，#443）：本段（当前轮）累计成功工具结果超此值即命中
     * 上下文经济信号——单会话长段（录词片 161 次调用/12.1M tokens 实测）的膨胀主源
     * 是多次中量读取的累积而非单次 oversized，单次判据够不着这种形态。约 12 万
     * 字符 ≈ 3 万 token 量级：重段约前 1/3 触发、常规轻段（数万字符）不误伤。
     */
    static final int CUMULATIVE_RESULT_CHARS = 120_000;

    /** 并行工具触发线：尾部连续每迭代恰 1 个调用的迭代数 ≥ 此值 → 建议并行。 */
    static final int SINGLE_CALL_STREAK = 4;

    /** 失败止损规则：尾部同签名失败 streak ≥ 阈值 → 信号（理由带次数与错误首行）。 */
    private static void addStopLossSignal(TrajectorySnapshot snapshot, HintPolicy policy,
            List<HintSignal> signals) {
        if (policy.disabledKinds().contains(DecisionHintKind.FAILURE_STOP_LOSS)) {
            return;
        }
        int streak = 0;
        String streakSignature = null;
        String streakErrorLine = null;
        scan: for (int i = snapshot.iterations().size() - 1; i >= 0; i--) {
            List<ToolCallOutcome> calls = snapshot.iterations().get(i).calls();
            for (int j = calls.size() - 1; j >= 0; j--) {
                ToolCallOutcome call = calls.get(j);
                if (!call.failed()) {
                    // 成功调用截断尾部失败序列：更早的失败与本次止损无关
                    break scan;
                }
                String signature = stopLossSignature(call);
                if (streakSignature == null || !streakSignature.equals(signature)) {
                    if (streakSignature != null) {
                        streak = 0;
                    }
                    streakSignature = signature;
                    streakErrorLine = errorFirstLine(call.outputText());
                }
                streak++;
                if (streak >= policy.errorStreakThreshold()) {
                    signals.add(new HintSignal(DecisionHintKind.FAILURE_STOP_LOSS,
                            "同类错误连续失败 " + streak + " 次（" + streakErrorLine + "）"));
                    return;
                }
            }
        }
    }

    /**
     * 反 mock 规则：近期 {@value #ANTI_MOCK_WINDOW} 个迭代的 assistant 文本或成功
     * 结果文本命中占位/mock 模式 → 信号（理由带命中模式）。
     */
    private static void addAntiMockSignal(TrajectorySnapshot snapshot, HintPolicy policy,
            List<HintSignal> signals) {
        if (policy.disabledKinds().contains(DecisionHintKind.ANTI_MOCK)) {
            return;
        }
        int from = Math.max(0, snapshot.iterations().size() - ANTI_MOCK_WINDOW);
        for (int i = from; i < snapshot.iterations().size(); i++) {
            Iteration iteration = snapshot.iterations().get(i);
            String hit = firstMockPattern(iteration.assistantText());
            if (hit == null) {
                for (ToolCallOutcome call : iteration.calls()) {
                    if (!call.failed()) {
                        hit = firstMockPattern(call.outputText());
                        if (hit != null) {
                            break;
                        }
                    }
                }
            }
            if (hit != null) {
                signals.add(new HintSignal(DecisionHintKind.ANTI_MOCK, "近期产出含占位痕迹「" + hit + "」"));
                return;
            }
        }
    }

    /** 首个命中的 mock 模式（小写包含匹配），无命中返回 null。 */
    private static String firstMockPattern(String text) {
        if (text == null) {
            return null;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String pattern : ANTI_MOCK_PATTERNS) {
            if (lowered.contains(pattern)) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * 上下文经济规则（#443 加强=单次＋累计双判据，同库条目同槽位——不新增堆叠
     * 竞争者）：<b>单次判据</b>——最近一次迭代存在结果 &gt; {@value
     * #LARGE_RESULT_CHARS} 字符的成功调用；<b>累计判据</b>——本段（当前轮）累计
     * 成功结果 &gt; {@value #CUMULATIVE_RESULT_CHARS} 字符（只计成功调用：失败
     * 输出多为报错短文本，不是灌入主源；判累计用「字符数」而不用迭代数——不同
     * 工具结果体量差几个量级，迭代数判不准膨胀）。双判据同轮同命中时单次优先
     * （理由直指刚发生的 oversized 读取，下一步决策最 relevant）；累计判据命中
     * 即常驻产生信号直至注入（单轮内注入键不重灌，见 {@link #select}）。
     */
    private static void addContextEconomySignal(TrajectorySnapshot snapshot, HintPolicy policy,
            List<HintSignal> signals) {
        if (policy.disabledKinds().contains(DecisionHintKind.CONTEXT_ECONOMY)
                || snapshot.iterations().isEmpty()) {
            return;
        }
        long cumulativeChars = 0;
        for (Iteration iteration : snapshot.iterations()) {
            for (ToolCallOutcome call : iteration.calls()) {
                if (!call.failed()) {
                    cumulativeChars += call.resultChars();
                }
            }
        }
        for (ToolCallOutcome call : snapshot.iterations().get(snapshot.iterations().size() - 1)
                .calls()) {
            if (!call.failed() && call.resultChars() > LARGE_RESULT_CHARS) {
                signals.add(new HintSignal(DecisionHintKind.CONTEXT_ECONOMY,
                        "单次工具结果 " + call.resultChars() + " 字符"));
                return;
            }
        }
        if (cumulativeChars > CUMULATIVE_RESULT_CHARS) {
            signals.add(new HintSignal(DecisionHintKind.CONTEXT_ECONOMY,
                    "本段累计成功工具结果 " + cumulativeChars + " 字符"));
        }
    }

    /**
     * 并行工具规则：尾部连续每迭代恰 1 个调用的迭代数 ≥ {@value #SINGLE_CALL_STREAK}
     * → 信号。条件消失信号即不再产生——但已注入的键本轮不因此释放（防振荡，
     * 槽位占用语义见类注）。
     */
    private static void addParallelToolsSignal(TrajectorySnapshot snapshot, HintPolicy policy,
            List<HintSignal> signals) {
        if (policy.disabledKinds().contains(DecisionHintKind.PARALLEL_TOOLS)) {
            return;
        }
        int streak = 0;
        for (int i = snapshot.iterations().size() - 1; i >= 0; i--) {
            if (snapshot.iterations().get(i).calls().size() != 1) {
                break;
            }
            streak++;
        }
        if (streak >= SINGLE_CALL_STREAK) {
            signals.add(new HintSignal(DecisionHintKind.PARALLEL_TOOLS,
                    "连续 " + streak + " 步每次只发起一个工具调用"));
        }
    }

    /** 错误签名：工具名＋首行归一化（区分「同类错误」——同工具同首行才算同一序列）。 */
    private static String stopLossSignature(ToolCallOutcome call) {
        return call.toolName() + STOP_LOSS_SIGNATURE_SEPARATOR + errorFirstLine(call.outputText());
    }

    /** 错误首行归一化：null 安全、去首尾空白、小写。 */
    private static String errorFirstLine(String outputText) {
        if (outputText == null || outputText.isBlank()) {
            return "";
        }
        String trimmed = outputText.strip();
        int newline = trimmed.indexOf('\n');
        String firstLine = newline >= 0 ? trimmed.substring(0, newline) : trimmed;
        return firstLine.strip().toLowerCase(Locale.ROOT);
    }
}

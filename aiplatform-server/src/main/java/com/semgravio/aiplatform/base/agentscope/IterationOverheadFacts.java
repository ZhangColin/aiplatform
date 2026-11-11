package com.semgravio.aiplatform.base.agentscope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每迭代平台开销事实（#453 段内残差归因的观察面，{@link StageDurationFacts}
 * 同族：事实观察，非呈现）：执行体 ReAct 循环每迭代一次的平台自有逻辑掐表
 * ——中间件动态查库（决策提示策略 / verify 自决开关的配置读腿）、微指令判定体
 * （轨迹快照提取＋规则判定＋判重回读扫描）、引擎事件平台处理链（事实观察＋
 * 事件映射＋部件映射＋用户面投影＋SSE 派发——含阻塞式 emitter.send 的真实
 * 墙钟）。实例随 PreparedTurn 生命周期（converse/resume 各一枚），经
 * {@link RuntimeContext} 属性袋下发中间件（无属性袋的一次性调用面跳过观测），
 * 收口侧随 {@link StageDurations#overheadMs()} 进收尾卡 durationBreakdown
 * 的 {@code overhead} 桶——「窗与窗之间的平台耗时」首次可逐桶归因（残差饼图）。
 *
 * <p>口径：纳秒掐表（{@link System#nanoTime()}）、累计取整毫秒、负值丢弃；
 * 全部桶缺省 0（观测面缺席不炸消费面）。{@code iterations} 非本类计数——
 * 由 {@link StageDurationFacts} 按闭合模型调用区间计数（全形态通用，含无
 * 中间件的会话），快照时注入。</p>
 */
public final class IterationOverheadFacts {

    /** 中间件动态查库桶键（配置读腿——决策提示策略＋verify 自决开关）。 */
    public static final String CONFIG_MS = "configMs";

    /** 微指令判定桶键（轨迹快照提取＋规则分类器＋判重回读扫描的纯计算）。 */
    public static final String EVAL_MS = "evalMs";

    /** 引擎事件平台处理链桶键（事实观察＋映射＋部件＋投影＋SSE 派发）。 */
    public static final String EVENT_PIPELINE_MS = "eventPipelineMs";

    /** 判定门跳过次数键（门断定无信号可产出而跳过重评估的迭代数——#453 降频件的观测面）。 */
    public static final String GATE_SKIPPED = "gateSkipped";

    /** 判定门放行次数键（门判定可能产出信号而走全量快照＋判定的迭代数）。 */
    public static final String GATE_EVALUATED = "gateEvaluated";

    /** 迭代数键（闭合模型调用区间计数——每迭代恰一次模型调用）。 */
    public static final String ITERATIONS = "iterations";

    private long configNanos;
    private long evalNanos;
    private long eventPipelineNanos;
    private int gateSkipped;
    private int gateEvaluated;

    /** 中间件配置读腿掐表（纳秒累计；负值丢弃）。 */
    public void addConfigNanos(long nanos) {
        if (nanos > 0) {
            configNanos += nanos;
        }
    }

    /** 微指令判定体掐表（纳秒累计；负值丢弃）。 */
    public void addEvalNanos(long nanos) {
        if (nanos > 0) {
            evalNanos += nanos;
        }
    }

    /** 引擎事件平台处理链掐表（纳秒累计；负值丢弃）。 */
    public void addEventPipelineNanos(long nanos) {
        if (nanos > 0) {
            eventPipelineNanos += nanos;
        }
    }

    /** 判定门跳过计数（门跳过一回记一回）。 */
    public void noteGateSkipped() {
        gateSkipped++;
    }

    /** 判定门放行计数（全量评估一回记一回）。 */
    public void noteGateEvaluated() {
        gateEvaluated++;
    }

    /**
     * 不定形快照（毫秒取整＋迭代数注入）：六键齐备（缺席为 0——观测面可全零，
     * 消费面不炸）。{@code iterations} 由调用方（runTurn）从
     * {@link StageDurationFacts#modelCalls()} 取闭合区间计数注入。
     */
    public Map<String, Long> snapshot(long iterations) {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put(CONFIG_MS, configNanos / 1_000_000);
        snapshot.put(EVAL_MS, evalNanos / 1_000_000);
        snapshot.put(EVENT_PIPELINE_MS, eventPipelineNanos / 1_000_000);
        snapshot.put(GATE_SKIPPED, (long) gateSkipped);
        snapshot.put(GATE_EVALUATED, (long) gateEvaluated);
        snapshot.put(ITERATIONS, iterations);
        return snapshot;
    }
}

package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 验证事实登记（#352 独立验证子智能体的平台观测面）：verify 子智能体对系统的
 * 三面验证结果（浏览器点流程／查库／看日志）从 report_verification 工具调用事实
 * 观测（不解析自由文本）——工具执行体落事实于此，run 尝试环收口
 * （{@link CoderRunAttempts}）读取拼装收尾卡验证统计。进程内事实（run 无表口径）：
 * 重启即清，与轨道在途标记同取舍；key = 工作区（#351 切片并行下 fork 副本与主
 * 工作区各跑各的验证，按键隔离不串账）。
 *
 * <p><b>生命周期</b>（对偶 {@link FinishEditFacts}）：run 起跑清残留（上一轨的
 * 验证事实不进本轨统计）→ 尝试中工具登记（修复—复验环逐轮登记：末轮快照后写
 * 胜出＝收口时刻的验证状态，轮次累加＝复验过程如实）→ 收口消费（取走即清）。
 * 尝试间不清——验证属轨道事实：首试登记后流中断、重试正常收口时统计仍连续。</p>
 */
@Component
public class VerificationFacts {

    /**
     * 三面封闭集与呈现序（#352 票的硬约定：browser=浏览器点流程、db=查库、
     * log=看日志）——工具入参校验（未知面报错引导改正）与收尾统计三面分布
     * 装配共此一源，顺序即 closing.verification.faces 的稳定键序。
     */
    public static final List<String> FACES = List.of("browser", "db", "log");

    /**
     * 单项验证结果（report_verification 的登记单元）：name 项名（用户语言，如
     * 「留言提交」）/ face 三面之一（{@link #FACES}——封闭集在工具入参校验，
     * 本服务不解释）/ ok 过没过 / detail 证据或失败现场（可空——通过项可不附）。
     */
    public record Check(String name, String face, boolean ok, String detail) {
    }

    /**
     * 一场 run 的验证事实：末轮快照（收口时刻状态——修复后复验通过即全过）+
     * 累计轮次（修复—复验环的如实呈现，1 = 一轮过）。
     */
    public record Facts(List<Check> lastRound, int rounds) {
    }

    private final Map<String, Facts> facts = new ConcurrentHashMap<>();

    /** 工具执行侧登记（report_verification 调用即事实）：末轮快照后写胜出、轮次累加。 */
    public void record(String workspaceId, List<Check> checks) {
        facts.merge(workspaceId, new Facts(List.copyOf(checks), 1),
                (previous, next) -> new Facts(next.lastRound(), previous.rounds() + 1));
    }

    /** 收口消费（取走即清）：null = 本轨验证子智能体未登记（未跑/未报——如实缺省）。 */
    public Facts consume(String workspaceId) {
        return facts.remove(workspaceId);
    }

    /** run 起跑清残留：本轨验证统计从零起算。 */
    public void clear(String workspaceId) {
        facts.remove(workspaceId);
    }
}

package com.semgravio.aiplatform.business.project.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * savePrd 快道事实登记（#542 访谈②，ADR-0048/0049；2026-11-17 用户裁决收紧试用
 * 轨提前收口口径）：快道（<b>试用态＝用户明确收敛令</b>〔「不用再问了/直接出 PRD/
 * 直接开始做」——未问满五问时唯一合法的提前收口形〕；<b>付费态＝零必答首轮直产／
 * 「直接开始做」视同确认</b>，五问额度不约束付费态）是<b>只有模型可观测</b>的收口形态——平台从工具
 * 调用事实观测（判定契约铁律：不新增自报面，与 {@link PrdRevisionFacts} 的
 * summary 同族）：savePrd 带 {@code fastLane=true} 即登记于此，轮收口
 * （{@link MainAgentAppService#dispatchOnTurnClose}）消费分岔——在场＝快道直派
 * （不出卡不确认），缺席＝自然收口（试用态出岔路口卡／付费态确认门在场，不派
 * 发——全域无无条件自动路径）。进程内事实（run 无表口径，同修订事实取舍）：
 * 重启即清（丢事实＝按自然收口处理，卡在场用户点火——降级方向安全）；key =
 * 工作区（一项目一主智能体会话，事实即项目当下最新一次收口形态）。
 *
 * <p><b>生命周期</b>（与 {@code PrdRevisionFacts} 同步）：轮起跑清残留（上一轮
 * 滞留的快道事实不冒充本轮形态）→ 轮内 savePrd 成功且带标记即登记（一轮多次
 * 调用后写胜出）→ 收口消费（取走即清）。协议面（{@code AgentProfile#MAIN}
 * 第 1/5/6 条＋「访谈协议·试用轨」段）约定模型何时上报：付费态零必答首轮直产、
 * 任意付费状态下用户收敛令收口传 true；试用态问满五问后的自然收口与判定明确
 * 收口不传——岔路口只拦后者（试用态未问满且无收敛令的 savePrd 在工具闸即被拒，
 * 到不了本事实登记）。</p>
 */
@Component
public class FastLaneFacts {

    private final Set<String> fastLanes = ConcurrentHashMap.newKeySet();

    /** 工具执行侧登记（savePrd 成功调用且 fastLane=true）。 */
    public void record(String workspaceId) {
        fastLanes.add(workspaceId);
    }

    /** 收口消费（取走即清）：false = 本轮无快道事实（自然收口）。 */
    public boolean consume(String workspaceId) {
        return fastLanes.remove(workspaceId);
    }

    /** 轮起跑清残留：本轮收口形态从零起算。 */
    public void clear(String workspaceId) {
        fastLanes.remove(workspaceId);
    }
}

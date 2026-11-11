package com.semgravio.aiplatform.business.project.application;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.skills.domain.enums.SkillSlot;

/**
 * propose_sediment 调用事实登记（#460 数据飞轮采集层——轮收口「该提未提」轻兜底
 * 盘问的判定依据）：主智能体对「这轮提没提沉淀」的判定从工具调用事实观测（判定
 * 契约铁律：不新增自报面）——工具执行侧（{@code SedimentProposalAdapter}）把每
 * 次自荐调用按「工作区 → 槽位集合」落事实于此；主智能体轮收口
 * （{@code MainAgentAppService}）消费——本轮 main 槽有调用事实即不置兜底盘问，
 * 否则记账（次轮 prompt 尾附轻提醒）。进程内事实（run 无表口径）：重启即清，
 * 与 savePrd 事实 {@link PrdRevisionFacts} 同取舍；key = 工作区（一个项目一个
 * 主智能体会话）。
 *
 * <p><b>生命周期</b>（与修订事实同口径）：意见轮起跑清残留 → 轮内自荐调用即
 * 登记（一轮多次调用按槽位归并）→ 轮收口消费（取走即清；平台轮消费不记账）。
 * 执行体/子智能体槽的调用也登记（采集埋点的事实底），但兜底盘问只看 main 槽
 * ——执行体 run 的调用不替主智能体豁免。</p>
 */
@Component
public class SedimentProposalFacts {

    private final Map<String, Set<String>> slotsByWorkspace = new ConcurrentHashMap<>();

    /** 工具执行侧登记（每次自荐调用即事实；一轮多次按槽位归并进集合）。 */
    public void record(String workspaceId, String slotKey) {
        slotsByWorkspace.computeIfAbsent(workspaceId, k -> ConcurrentHashMap.newKeySet())
                .add(slotKey);
    }

    /**
     * 收口消费（取走即清）：true＝本轮主智能体槽有自荐调用事实（不提兜底盘问）。
     */
    public boolean consumeMainProposed(String workspaceId) {
        Set<String> slots = slotsByWorkspace.remove(workspaceId);
        return slots != null && slots.contains(SkillSlot.MAIN.key());
    }

    /** 意见轮起跑清残留：本轮判定从零起算。 */
    public void clear(String workspaceId) {
        slotsByWorkspace.remove(workspaceId);
    }
}

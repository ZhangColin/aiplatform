package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * saveBuildPlan 切片计划事实登记（ADR 0009 交接物来源）：主智能体对「系统拆成
 * 哪些纵向切片、什么顺序」的产出，从工具调用事实观测（判定契约铁律：不解析自由
 * 文本）——saveBuildPlan 工具把切片清单落事实于此，意见轮收口
 * （{@link MainAgentAppService}）消费入生成交接物。进程内事实（run 无表口径）：
 * 重启即清，与意见锚同取舍；key = 工作区（一个项目一个主智能体会话，事实即项目
 * 当下最新一次切片计划）。
 *
 * <p><b>生命周期</b>（与意见锚 {@code opinionExchanges} / PRD 修订事实
 * {@link PrdRevisionFacts} 同步）：意见轮起跑清残留（上一轮/访谈期的旧计划不进
 * 本轮交接物）→ 轮内 saveBuildPlan 成功即登记（一轮多次调用后写胜出——交接物
 * 取终值）→ 收口派发即消费（取走即清；未生成/归档止于对话时同锚一并清）。</p>
 */
@Component
public class BuildPlanFacts {

    private final Map<String, BuildPlan> plans = new ConcurrentHashMap<>();

    /** 工具执行侧登记（saveBuildPlan 成功调用即事实；一轮多次调用后写胜出）。 */
    public void record(String workspaceId, BuildPlan plan) {
        plans.put(workspaceId, plan);
    }

    /** 收口消费（取走即清）：null = 本轮无 saveBuildPlan 调用事实（无切片计划）。 */
    public BuildPlan consume(String workspaceId) {
        return plans.remove(workspaceId);
    }

    /** 意见轮起跑清残留：本轮计划从零起算。 */
    public void clear(String workspaceId) {
        plans.remove(workspaceId);
    }
}

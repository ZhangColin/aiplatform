package com.semgravio.aiplatform.business.project.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * savePrd 失败事实登记（ADR-0032 访谈期悬空收口的补救分形依据）：工具执行体在
 * PRD 落盘/业务登记失败时登记于此，主智能体轮收口（{@link MainAgentAppService}）
 * 消费——悬空补救据此点名「刚才写 PRD 一步失败了」而非泛形。进程内事实（run
 * 无表口径）：重启即清；key = 工作区（一个项目一个主智能体会话）。#625 起旧迭代
 * 受理链拆除，交接物用的修订摘要观测（summary）随票退场，本类只留失败腿。
 *
 * <p><b>生命周期</b>：意见轮起跑清残留 → 轮内 savePrd 失败即登记（同轮重试成功
 * 由 SavePrdTool 清掉失败事实）→ 回合收口消费失败事实（取走即清；未生成/归档
 * 止于对话时一并清）。</p>
 */
@Component
public class PrdRevisionFacts {

    private final Set<String> failures = ConcurrentHashMap.newKeySet();

    /** 工具执行侧失败登记（落盘/业务登记失败即事实——悬空补救点名形依据）。 */
    public void recordFailure(String workspaceId) {
        failures.add(workspaceId);
    }

    /** 成功落盘后清掉同轮失败事实（重试成功＝PRD 终究成了）。 */
    public void clearFailure(String workspaceId) {
        failures.remove(workspaceId);
    }

    /** 收口消费失败事实（取走即清）：false = 本轮无 savePrd 失败事实。 */
    public boolean consumeFailure(String workspaceId) {
        return failures.remove(workspaceId);
    }

    /** 意见轮起跑清残留：本轮失败事实从零起算。 */
    public void clear(String workspaceId) {
        failures.remove(workspaceId);
    }
}

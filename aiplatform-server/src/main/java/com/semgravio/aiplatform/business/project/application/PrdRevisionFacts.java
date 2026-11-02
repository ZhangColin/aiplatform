package com.semgravio.aiplatform.business.project.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * savePrd 调用事实登记（#52 交接物补齐；ADR-0032 补失败腿）：主智能体对「PRD 改没
 * 改、改了什么」的判定从工具调用事实观测（判定契约铁律：不新增自报面）——工具执行
 * 体把必传的 summary 落事实于此，意见轮收口（{@link MainAgentAppService}）消费入
 * 交接物；落盘/业务登记失败同面登记失败事实（访谈期悬空收口的补救分形依据——
 * 「刚才写 PRD 一步失败了」点名形）。进程内事实（run 无表口径）：重启即清，与
 * 意见锚同取舍；key = 工作区（一个项目一个主智能体会话，事实即项目当下最新一次
 * 修订说明）。
 *
 * <p><b>生命周期</b>（与意见锚 {@code opinionExchanges} 同步）：意见轮起跑清残留
 * （上一轮/访谈期的旧事实不进本轮交接物）→ 轮内 savePrd 成功即登记（一轮多次
 * 调用后写胜出——交接物取终值；成功即覆盖失败事实）→ 失败即落失败事实（同轮
 * 重试成功被覆盖）→ 回合收口派发即消费（取走即清；未生成/归档止于对话时同锚
 * 一并清）。</p>
 */
@Component
public class PrdRevisionFacts {

    private final Map<String, String> summaries = new ConcurrentHashMap<>();
    private final Map<String, Boolean> failures = new ConcurrentHashMap<>();

    /** 工具执行侧登记（savePrd 成功调用即事实；一轮多次调用后写胜出——成功即
     *  覆盖同轮早先的失败事实，PRD 终究成了）。 */
    public void record(String workspaceId, String summary) {
        summaries.put(workspaceId, summary);
        failures.remove(workspaceId);
    }

    /** 工具执行侧失败登记（落盘/业务登记失败即事实——悬空补救点名形依据）。 */
    public void recordFailure(String workspaceId) {
        failures.put(workspaceId, Boolean.TRUE);
    }

    /** 收口消费（取走即清）：null = 本轮无 savePrd 调用事实（PRD 未修订）。 */
    public String consume(String workspaceId) {
        return summaries.remove(workspaceId);
    }

    /** 收口消费失败事实（取走即清）：false = 本轮无 savePrd 失败事实。 */
    public boolean consumeFailure(String workspaceId) {
        return failures.remove(workspaceId) != null;
    }

    /** 意见轮起跑清残留：本轮判定从零起算。 */
    public void clear(String workspaceId) {
        summaries.remove(workspaceId);
        failures.remove(workspaceId);
    }
}

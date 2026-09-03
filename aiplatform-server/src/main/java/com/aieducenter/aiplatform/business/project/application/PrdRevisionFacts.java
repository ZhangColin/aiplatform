package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * savePrd 修订事实登记（#52 交接物补齐）：BA 对「PRD 改没改、改了什么」的判定
 * 从工具调用事实观测（判定契约铁律：不新增自报面）——工具执行体把必传的 summary
 * 落事实于此，BA 回合收口（{@link BaInterviewAppService}）消费入交接物。进程内
 * 事实（run 无表口径）：重启即清，与意见锚同取舍；key = 工作区（一个项目一个
 * BA 会话，事实即项目当下最新一次修订说明）。
 *
 * <p><b>生命周期</b>（与意见锚 {@code opinionExchanges} 同步）：BA 轮起跑清残留
 * （上一轮/访谈期的旧事实不进本轮交接物）→ 轮内 savePrd 成功即登记（一轮多次
 * 调用后写胜出——交接物取终值）→ 回合收口派发即消费（取走即清；未生成/归档
 * 止于 BA 时同锚一并清）。</p>
 */
@Component
public class PrdRevisionFacts {

    private final Map<String, String> summaries = new ConcurrentHashMap<>();

    /** 工具执行侧登记（savePrd 成功调用即事实；一轮多次调用后写胜出）。 */
    public void record(String workspaceId, String summary) {
        summaries.put(workspaceId, summary);
    }

    /** 收口消费（取走即清）：null = 本轮无 savePrd 调用事实（PRD 未修订）。 */
    public String consume(String workspaceId) {
        return summaries.remove(workspaceId);
    }

    /** BA 轮起跑清残留：本轮判定从零起算。 */
    public void clear(String workspaceId) {
        summaries.remove(workspaceId);
    }
}

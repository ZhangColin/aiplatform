package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * finish_fix 结束工具的调用事实登记（#46）：编码智能体对「要不要动系统」的判定
 * 从工具调用事实观测（不解析自由文本）——工具执行体落事实于此，修正轨道收口
 * （{@link IterationAppService}）读取判定。进程内事实（run 无表口径）：重启即清，
 * 与轨道在途标记同取舍；key = 工作区（一个项目一个编码会话，事实即项目当下最新
 * 一次收口判定）。
 *
 * <p><b>生命周期</b>：轨道起跑清残留（上一轨/生成 run 的遗留不进本轨判定）→
 * 尝试中工具登记（重复调用后写胜出）→ 正常收口消费（取走即清）；尝试间不清——
 * 判定属轨道：首试登记后被中断、重试正常返回时判定仍有效，不因流中断丢失。</p>
 */
@Component
public class FinishFixFacts {

    /** 一次收口判定：动没动系统 + 说明（changed=true 改了什么 / false 为什么不需要）。 */
    public record Fact(boolean changed, String text) {
    }

    private final Map<String, Fact> facts = new ConcurrentHashMap<>();

    /** 工具执行侧登记（finish_fix 调用即事实；重复调用后写胜出）。 */
    public void record(String workspaceId, boolean changed, String text) {
        facts.put(workspaceId, new Fact(changed, text));
    }

    /** 收口消费（取走即清）：null = 本轨从未调用 finish_fix（未正常收口）。 */
    public Fact consume(String workspaceId) {
        return facts.remove(workspaceId);
    }

    /** 轨道起跑清残留：本轨判定从零起算。 */
    public void clear(String workspaceId) {
        facts.remove(workspaceId);
    }
}

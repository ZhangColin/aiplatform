package com.semgravio.aiplatform.business.project.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * finish_edit 结束工具的调用事实登记（#46；结束工具已随 #604 退役）：曾承载执行
 * 体对「要不要动系统」的工具调用事实（不解析自由文本）——工具执行体落事实于此，
 * 修正轨道收口（{@link IterationAppService}）读取判定。进程内事实（run 无表
 * 口径）：重启即清，与轨道在途标记同取舍；key = 工作区。
 *
 * <p><b>退役中（#604）</b>：结束工具与判定消费已随 run 尝试体旧机制拆除，本类
 * 暂留至 #605 审查与收口组清场（当前仅轨道起跑清残留调用）。</p>
 */
@Component
public class FinishEditFacts {

    /** 一次收口判定：动没动系统 + 说明（changed=true 改了什么 / false 为什么不需要）。 */
    public record Fact(boolean changed, String text) {
    }

    private final Map<String, Fact> facts = new ConcurrentHashMap<>();

    /** 工具执行侧登记（finish_edit 调用即事实；重复调用后写胜出）。 */
    public void record(String workspaceId, boolean changed, String text) {
        facts.put(workspaceId, new Fact(changed, text));
    }

    /** 收口消费（取走即清）：null = 本轨从未调用 finish_edit（未正常收口）。 */
    public Fact consume(String workspaceId) {
        return facts.remove(workspaceId);
    }

    /** 轨道起跑清残留：本轨判定从零起算。 */
    public void clear(String workspaceId) {
        facts.remove(workspaceId);
    }
}

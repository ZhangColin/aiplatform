package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.function.Function;

import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.skill.runtime.HarnessSkillEntry;
import io.agentscope.harness.agent.skill.runtime.SkillCatalog;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 技能加载计数中间件（#261 后台观测面，ADR-0022 使用计数）：拦 {@code
 * load_skill_through_path} 工具调用——skillId 命中本轮装配 catalog（框架技能
 * 中间件经 {@code SkillRuntime.install} 装进 RuntimeContext 的快照）即 best-effort
 * 计数落库（加载次数＋最近加载）。
 *
 * <p>口径＝<b>load 实际发生</b>，非技能目录出现：每轮清单重建（{@code
 * getAllSkills()} 调用与 {@code <available_skills>} 渲染）不产生任何工具调用、
 * 自然不计数；模型发起 load 且技能经装配视图可见（catalog 命中——乱拼/
 * 越权 skillId 必 miss）才计。计数锚＝AgentSkill 的 (name, source)，即装配
 * 视图 {@link SlotLibrarySkillRepository} 发放时写入的 {@code name + 来源包}
 * ——与 {@code skl_skills} 唯一键同形，跨包同名精确分行；内置技能无库行，
 * 落库 UPDATE 零行命中即静默（观测面对全库一致，内置结构性不适用）。</p>
 *
 * <p>best-effort 语义（知识沉淀容忍口径）：计数失败降级记日志、绝不阻断加载
 * ——异常只包住计数写，工具执行流 {@code next} 照常。挂全形态（主智能体/run
 * 执行体/子智能体——三槽位技能同出库，计数不分槽位），与框架 curator 的
 * {@code SkillUsageMiddleware} 同位同型但口径不同（那个只计 agent 自产、
 * 调用即计不管命中——ADR-0022 已拒接入，此为平台自建腿）。</p>
 */
@Slf4j
public class SkillLoadCountMiddleware implements MiddlewareBase {

    private final SkillStore skillStore;

    public SkillLoadCountMiddleware(SkillStore skillStore) {
        this.skillStore = skillStore;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input != null && input.toolCalls() != null) {
            for (ToolUseBlock call : input.toolCalls()) {
                countLoadCall(ctx, call);
            }
        }
        return next.apply(input);
    }

    /**
     * 单次 load 调用计数：非 load 工具直出；skillId 取工具入参 canonical 键
     * （与 {@code SkillLoadTool.callAsync} 同键）；本轮装配 catalog 命中才计
     * ——{@code AgentSkill.getName() + getSource()} 即库行唯一键。计数写单包
     * try：异常吞掉记日志，绝不外溢（工具流在 next，不受影响）。
     */
    private void countLoadCall(RuntimeContext ctx, ToolUseBlock call) {
        if (!SkillLoadTool.TOOL_NAME.equals(call.getName())) {
            return;
        }
        Object skillId = call.getInput() == null ? null : call.getInput().get("skillId");
        SkillCatalog catalog = ctx == null ? null : ctx.get(SkillCatalog.class);
        HarnessSkillEntry entry = skillId == null || catalog == null
                ? null
                : catalog.get(String.valueOf(skillId));
        if (entry == null) {
            return;
        }
        try {
            skillStore.recordLoad(entry.skill().getName(), entry.skill().getSource());
        }
        catch (RuntimeException e) {
            // best-effort（AC）：失败降级记日志，绝不阻断加载
            log.warn("[skill-usage] 加载计数未成（降级跳过）：{} ({})",
                    skillId, e.toString());
        }
    }
}

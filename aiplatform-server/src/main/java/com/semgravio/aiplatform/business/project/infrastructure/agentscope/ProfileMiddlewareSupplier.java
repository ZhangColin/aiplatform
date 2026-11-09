package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.agentscope.AgentMiddlewareSupplier;
import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.skills.domain.repository.SkillStore;
import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.core.middleware.MiddlewareBase;

/**
 * 平台中间件装配（#261 使用计数首件）：全形态（主智能体/run 执行体/子智能体
 * ——三槽位技能同出库）挂 {@link SkillLoadCountMiddleware 技能加载计数}——
 * 观测面对全库一致、不分槽位不分配置键；无状态中间件单实例长生命周期（依赖
 * 单例 {@link SkillStore}，多 agent 共享安全）。
 *
 * <p>决策时微指令中间件（#437）只挂 <b>run 执行体</b>装配面（{@link
 * AgentProfile#EXECUTOR}——主智能体对话轮与设计执行体不消费；子智能体键
 * 非 executor 自然不带）：策略经 {@link AgentConfigAppService#decisionHintConfigOf}
 * 每迭代动态查库——改档即生效、不发版、进行中 run 不定格，<b>装配不进 agent
 * 实例缓存键</b>（与 #366 边缘点同逻辑）。每构建新实例（无状态，随构建发放）。</p>
 *
 * <p>本类归 business 装配缝（组合 base.skills 存取口与 agentscope 框架类型是
 * 业务侧装配的事，base 两 BC 不互相挂依赖）。</p>
 */
@Component
public class ProfileMiddlewareSupplier implements AgentMiddlewareSupplier {

    private final SkillLoadCountMiddleware loadCountMiddleware;
    private final AgentConfigAppService agentConfigs;

    public ProfileMiddlewareSupplier(SkillStore skillStore, AgentConfigAppService agentConfigs) {
        this.loadCountMiddleware = new SkillLoadCountMiddleware(skillStore);
        this.agentConfigs = agentConfigs;
    }

    @Override
    public List<MiddlewareBase> middlewaresFor(String agentKey, AgentWorkspace workspace) {
        List<MiddlewareBase> middlewares = new ArrayList<>();
        middlewares.add(loadCountMiddleware);
        // #437：决策时注入只挂 run 执行体（agentKey 判别——主智能体/设计执行体/
        // 子智能体键不消费；工作区形态不判别：执行体 ProjectDev 与 fork 面同挂）
        if (AgentProfile.EXECUTOR.key().equals(agentKey)) {
            // 键透传（门控已保 executor——byKey 回落仅防御未知键，语义不偷换）
            middlewares.add(new DecisionHintMiddleware(agentKey,
                    key -> agentConfigs.decisionHintConfigOf(
                            AgentProfile.byKey(key).orElse(AgentProfile.EXECUTOR))));
        }
        return middlewares;
    }
}

package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentMiddlewareSupplier;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;

import io.agentscope.core.middleware.MiddlewareBase;

/**
 * 平台中间件装配（#261 使用计数首件）：全形态（主智能体/run 执行体/子智能体
 * ——三槽位技能同出库）挂 {@link SkillLoadCountMiddleware 技能加载计数}——
 * 观测面对全库一致、不分槽位不分配置键；无状态中间件单实例长生命周期（依赖
 * 单例 {@link SkillStore}，多 agent 共享安全）。后续平台级中间件在此扩展。
 *
 * <p>本类归 business 装配缝（组合 base.skills 存取口与 agentscope 框架类型是
 * 业务侧装配的事，base 两 BC 不互相挂依赖）。</p>
 */
@Component
public class ProfileMiddlewareSupplier implements AgentMiddlewareSupplier {

    private final SkillLoadCountMiddleware loadCountMiddleware;

    public ProfileMiddlewareSupplier(SkillStore skillStore) {
        this.loadCountMiddleware = new SkillLoadCountMiddleware(skillStore);
    }

    @Override
    public List<MiddlewareBase> middlewaresFor(String agentKey, AgentWorkspace workspace) {
        return List.of(loadCountMiddleware);
    }
}

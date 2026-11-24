package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.agentscope.AgentMiddlewareSupplier;
import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.skills.domain.repository.SkillStore;

import io.agentscope.core.middleware.MiddlewareBase;

/**
 * 平台中间件装配（#261 使用计数首件）：全形态（主智能体／生成内核／设计执行体
 * ——三槽位技能同出库）挂 {@link SkillLoadCountMiddleware 技能加载计数}——
 * 观测面对全库一致、不分槽位不分配置键；无状态中间件单实例长生命周期（依赖
 * 单例 {@link SkillStore}，多 agent 共享安全）。
 *
 * <p><b>#604 起不按配置键分叉</b>：原只挂 run 执行体（executor 键）装配面的两个
 * 旧栈中间件——决策时微指令（#437）与 verify 自决开关（#442）——随 run 尝试体
 * 旧机制退役：生成与更新 run 全部经内核装配形态派发（装配键＝
 * {@code generation-kernel}，不带 executor 键，#601），两个中间件已无装配面可挂，
 * 连同开关列（决策提示四列、verify 自决列——V83 迁移拆除）一并删除。</p>
 *
 * <p>本类归 business 装配缝（组合 base.skills 存取口与 agentscope 框架类型是
 * 业务侧装配的事，base 两 BC 不互相挂依赖）。</p>
 */
@Component
public class ProfileMiddlewareSupplier implements AgentMiddlewareSupplier {

    private final SkillLoadCountMiddleware loadCountMiddleware;

    @Autowired
    public ProfileMiddlewareSupplier(SkillStore skillStore) {
        this.loadCountMiddleware = new SkillLoadCountMiddleware(skillStore);
    }

    @Override
    public List<MiddlewareBase> middlewaresFor(String agentKey, AgentWorkspace workspace) {
        List<MiddlewareBase> middlewares = new ArrayList<>();
        middlewares.add(loadCountMiddleware);
        return middlewares;
    }
}

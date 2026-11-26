package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.skills.domain.repository.SkillStore;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.core.middleware.MiddlewareBase;

/**
 * 平台中间件装配缝（#261 全形态共享观测件；#604 起不按配置键分叉）：任一装配键
 * （主智能体／run 执行体／设计执行体／生成内核）都只挂技能加载计数一件——原只挂
 * run 执行体（executor 键）装配面的两个旧栈中间件（决策时微指令 #437、verify
 * 自决 #442）随 run 尝试体旧机制退役：生成与更新 run 全部经内核装配形态派发
 * （装配键＝generation-kernel，#601），executor 键已无派发路径，中间件连同开关列
 * 一并删除。本缝钉「executor 键也不再分叉挂件」——防旧中间件按键挂回。
 */
class ProfileMiddlewareSupplierTest {

    @Test
    void given_any_agent_key_when_assemble_then_only_shared_skill_load_count() {
        ProfileMiddlewareSupplier supplier = new ProfileMiddlewareSupplier(mock(SkillStore.class));

        for (String agentKey : List.of(AgentProfile.MAIN.key(), AgentProfile.EXECUTOR.key(),
                "generation-kernel")) {
            List<MiddlewareBase> middlewares = supplier.middlewaresFor(
                    agentKey, new AgentWorkspace.Local(Path.of("ws")));

            // 全形态只有技能加载计数一件（#261）——决策/verify 旧中间件不再挂任何键
            assertThat(middlewares).as("装配键 %s 的中间件集合", agentKey).hasSize(1);
            assertThat(middlewares.get(0)).isInstanceOf(SkillLoadCountMiddleware.class);
        }
    }

    @Test
    void given_same_supplier_when_assemble_twice_then_shared_middleware_instance_reused() {
        // 无状态中间件单实例长生命周期（多 agent 共享安全）——多次装配取同一实例
        ProfileMiddlewareSupplier supplier = new ProfileMiddlewareSupplier(mock(SkillStore.class));

        MiddlewareBase first = supplier.middlewaresFor(
                AgentProfile.MAIN.key(), new AgentWorkspace.Local(Path.of("ws"))).get(0);
        MiddlewareBase second = supplier.middlewaresFor(
                "generation-kernel", new AgentWorkspace.Local(Path.of("ws"))).get(0);

        assertThat(second).isSameAs(first);
    }
}

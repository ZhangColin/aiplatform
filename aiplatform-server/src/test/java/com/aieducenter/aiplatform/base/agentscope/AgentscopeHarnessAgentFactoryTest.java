package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentscopeHarnessAgentFactory} 实例缓存与生命周期：HarnessAgent 无状态可
 * 复用（per-session 靠 RuntimeContext，同规格构建恰一次）；工作区身份入规格键——
 * 不同容器不复用、同容器不同形态不复用；stateStore 注入构建缝（生产为 PG 版，
 * 测试用内存替身）；工具集经 {@link AgentToolkitSupplier} 注入（业务侧资产，
 * 此处空集替身——装配测试归 business 侧）。
 */
class AgentscopeHarnessAgentFactoryTest {

    /** 工具集空桩（工厂不解释工具内容——装配归 RoleToolkitSupplier 测试）。 */
    private static final AgentToolkitSupplier TOOLKITS = (agentKey, workspace) -> new Toolkit();

    /** 技能仓库空桩（#94 技能位——无技能挂载即框架不注入 <available_skills>）。 */
    private static final AgentSkillRepositorySupplier SKILL_REPOS =
            (agentKey, workspace) -> List.of();

    /** 子智能体空桩（#95 委派位——无声明挂载即框架不注入 <available_subagents>）。 */
    private static final AgentSubagentSupplier SUBAGENTS = (agentKey, workspace) -> List.of();

    private AgentscopeHarnessAgentFactory factoryWith(List<HarnessAgent> created) {
        return factoryWith(created, new InMemoryAgentStateStore());
    }

    private AgentscopeHarnessAgentFactory factoryWith(List<HarnessAgent> created,
                                                      AgentStateStore stateStore) {
        return new AgentscopeHarnessAgentFactory(stateStore, TOOLKITS,
                (name, sysPrompt, modelString, workspace, agentKey) -> {
                    HarnessAgent agent = mock(HarnessAgent.class);
                    created.add(agent);
                    return agent;
                });
    }

    @Test
    void given_same_spec_when_obtain_twice_then_built_once_and_reused() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        HarnessAgent first = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null);
        HarnessAgent second = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null);

        assertThat(second).isSameAs(first);
        assertThat(created).hasSize(1);
    }

    @Test
    void given_different_spec_when_obtain_then_new_instance_per_spec() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null);
        factory.obtain("platform-agent", "另一个 sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-chat",
                new AgentWorkspace.Local(null), null);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(java.nio.file.Path.of("/tmp/other-workspace")), null);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("1", "ws-1-dev"), null);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("2", "ws-2-dev"), null);

        assertThat(created).hasSize(6);
    }

    @Test
    void given_workspace_identity_when_obtain_then_keyed_by_container() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        HarnessAgent first = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null);
        // 同 workspaceId 同容器 = 同规格（复用）；同 id 不同容器名 = 不同规格
        HarnessAgent same = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null);

        assertThat(same).isSameAs(first);
        assertThat(created).hasSize(1);
    }

    @Test
    void given_local_vs_project_dev_when_obtain_then_not_shared() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null);

        assertThat(created).hasSize(2);
    }

    @Test
    void given_same_spec_different_role_when_obtain_then_not_shared() {
        // 角色入规格键（#43 工具面按角色发放）：同人格同模型同工作区、不同角色 =
        // 不同工具面，不静默复用
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        HarnessAgent ba = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "main");
        HarnessAgent coder = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "CODER");

        assertThat(coder).isNotSameAs(ba);
        assertThat(created).hasSize(2);
    }

    @Test
    void given_same_container_read_only_vs_dev_when_obtain_then_not_shared() {
        // #47 只读面形态入规格键：同容器同角色、读写/只读两形态 = 不同 agent 实例
        // （内核工具面不同），不静默复用
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        HarnessAgent dev = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "X");
        HarnessAgent readOnly = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "X");
        HarnessAgent readOnlyAgain = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "X");

        assertThat(readOnly).isNotSameAs(dev);
        assertThat(readOnlyAgain).isSameAs(readOnly); // 同规格只读面自身复用
        assertThat(created).hasSize(2);
    }

    @Test
    void given_cached_agents_when_destroy_then_all_closed_and_cache_cleared() throws IOException {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null);
        factory.obtain("platform-agent", "sys2", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null);

        factory.destroy();

        for (HarnessAgent agent : created) {
            verify(agent, times(1)).close();
        }
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null);
        assertThat(created).hasSize(3);
    }

    @Test
    void given_state_store_when_built_then_wired_into_agent() {
        // 真构建路径（不走替身 builder）：会话恢复的落点——agent 持有的是注入的
        // store（生产为 PG 版），非框架缺省的本地 JSON 文件实现。
        // builder().model() 即解析模型串（需 API key），无 key 环境跳过（冒烟同款口径）
        assumeTrue(System.getenv("DEEPSEEK_API_KEY") != null,
                "无 DEEPSEEK_API_KEY，跳过真构建断言");
        AgentStateStore stateStore = new InMemoryAgentStateStore();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                stateStore, TOOLKITS, SKILL_REPOS, SUBAGENTS, new AgentscopeProperties());

        HarnessAgent agent = factory.obtain("platform-agent-t", "sys",
                "deepseek:deepseek-v4-flash", new AgentWorkspace.Local(null), null);

        assertThat(agent.getStateStore()).isSameAs(stateStore);
        // 压缩接线守护（#108）：工厂显式配 compactionConfig → 压缩中间件非空（非 disableCompaction）
        assertThat(agent.getCompactionHook()).isNotNull();
    }

    /**
     * 委派位结构守护（#95）：ProjectDev（run 执行体）开子智能体（工厂不再 disableSubagents，
     * 框架装 subagent 中间件——含通用子智能体），ProjectReadOnly（主智能体对话姿态）关
     * 子智能体（委派是 run 内机制，主智能体永不委派）。真构建路径（需 API key 建模型，
     * 无 key 跳过——同 {@link #given_state_store_when_built_then_wired_into_agent} 口径）。
     */
    @Test
    void given_project_dev_when_built_then_subagents_enabled() {
        assumeTrue(System.getenv("DEEPSEEK_API_KEY") != null,
                "无 DEEPSEEK_API_KEY，跳过真构建断言");
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), TOOLKITS, SKILL_REPOS, SUBAGENTS,
                new AgentscopeProperties());

        HarnessAgent dev = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "executor");

        assertThat(dev.getSubagentAgentManager()).isNotNull();
    }

    @Test
    void given_project_read_only_when_built_then_subagents_disabled() {
        assumeTrue(System.getenv("DEEPSEEK_API_KEY") != null,
                "无 DEEPSEEK_API_KEY，跳过真构建断言");
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), TOOLKITS, SKILL_REPOS, SUBAGENTS,
                new AgentscopeProperties());

        HarnessAgent readOnly = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "main");

        assertThat(readOnly.getSubagentAgentManager()).isNull();
    }

    /**
     * 压缩显式配置（#108 / ADR-0012）：触发阈值 400K + flash 档（取值与依据见
     * AgentscopeProperties）。模型解析需 API key（ModelRegistry 建 deepseek 模型读
     * DEEPSEEK_API_KEY），无 key 跳过。
     */
    @Test
    void given_default_properties_when_compaction_config_then_explicit_threshold_and_flash_model() {
        assumeTrue(System.getenv("DEEPSEEK_API_KEY") != null,
                "无 DEEPSEEK_API_KEY，跳过模型解析断言");

        CompactionConfig config = AgentscopeHarnessAgentFactory.compactionConfig(
                new AgentscopeProperties());

        assertThat(config.getTriggerTokens()).isEqualTo(400_000);
        assertThat(config.getModel()).isNotNull();
        assertThat(config.getModel().getModelName()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void given_blank_compaction_model_when_compaction_config_then_no_model_override() {
        // 压缩模型档为空串 = 回框架缺省（用主模型）；触发阈值照常显式——无 API key 也可断言
        AgentscopeProperties properties = new AgentscopeProperties();
        properties.setCompactionModel("");

        CompactionConfig config = AgentscopeHarnessAgentFactory.compactionConfig(properties);

        assertThat(config.getTriggerTokens()).isEqualTo(400_000);
        assertThat(config.getModel()).isNull();
    }
}

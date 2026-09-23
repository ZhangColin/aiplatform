package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private static final AgentToolkitSupplier TOOLKITS =
            (agentKey, workspace, toolSpec) -> new Toolkit();

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
                (name, sysPrompt, modelString, workspace, agentKey, toolSpec) -> {
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
                new AgentWorkspace.Local(null), null, null);
        HarnessAgent second = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null, null);

        // 同规格恰建一次（技能集不入键——供应商非工厂字段、obtain 无从取技能态；
        // #249/ADR-0021 指派变更靠装配视图动态查库，不触发实例重建）
        assertThat(second).isSameAs(first);
        assertThat(created).hasSize(1);
    }

    @Test
    void given_different_spec_when_obtain_then_new_instance_per_spec() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null, null);
        factory.obtain("platform-agent", "另一个 sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null, null);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-chat",
                new AgentWorkspace.Local(null), null, null);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(java.nio.file.Path.of("/tmp/other-workspace")), null, null);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("1", "ws-1-dev"), null, null);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("2", "ws-2-dev"), null, null);

        assertThat(created).hasSize(6);
    }

    @Test
    void given_workspace_identity_when_obtain_then_keyed_by_container() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        HarnessAgent first = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null, null);
        // 同 workspaceId 同容器 = 同规格（复用）；同 id 不同容器名 = 不同规格
        HarnessAgent same = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null, null);

        assertThat(same).isSameAs(first);
        assertThat(created).hasSize(1);
    }

    @Test
    void given_local_vs_project_dev_when_obtain_then_not_shared() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null, null);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null, null);

        assertThat(created).hasSize(2);
    }

    @Test
    void given_same_spec_different_role_when_obtain_then_not_shared() {
        // 角色入规格键（#43 工具面按角色发放）：同人格同模型同工作区、不同角色 =
        // 不同工具面，不静默复用
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        HarnessAgent ba = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "main", null);
        HarnessAgent coder = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "CODER", null);

        assertThat(coder).isNotSameAs(ba);
        assertThat(created).hasSize(2);
    }

    @Test
    void given_same_spec_different_tool_spec_when_obtain_then_not_shared() {
        // #252 工具面规格入缓存键：工具集构建时固化（Toolkit 静态注册、无技能线的
        // 动态视图缝），规格变（开关变更）＝实例变——下一轮命令构建即新装配，进行中
        // run 不定格；同规格（含同 toolSpec）自身复用
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        HarnessAgent open = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "main", "ws=true,fu=true");
        HarnessAgent closed = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "main", "ws=false,fu=true");
        HarnessAgent openAgain = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "main", "ws=true,fu=true");

        assertThat(closed).isNotSameAs(open);
        assertThat(openAgain).isSameAs(open);
        assertThat(created).hasSize(2);
    }

    @Test
    void given_same_container_read_only_vs_dev_when_obtain_then_not_shared() {
        // #47 只读面形态入规格键：同容器同角色、读写/只读两形态 = 不同 agent 实例
        // （内核工具面不同），不静默复用
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);

        HarnessAgent dev = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "X", null);
        HarnessAgent readOnly = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "X", null);
        HarnessAgent readOnlyAgain = factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "X", null);

        assertThat(readOnly).isNotSameAs(dev);
        assertThat(readOnlyAgain).isSameAs(readOnly); // 同规格只读面自身复用
        assertThat(created).hasSize(2);
    }

    @Test
    void given_cached_agents_when_destroy_then_all_closed_and_cache_cleared() throws IOException {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = factoryWith(created);
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null, null);
        factory.obtain("platform-agent", "sys2", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null, null);

        factory.destroy();

        for (HarnessAgent agent : created) {
            verify(agent, times(1)).close();
        }
        factory.obtain("platform-agent", "sys", "deepseek:deepseek-v4-flash",
                new AgentWorkspace.Local(null), null, null);
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
                "deepseek:deepseek-v4-flash", new AgentWorkspace.Local(null), null, null);

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
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "executor", null);

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
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "main", null);

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
        // #109 模型边界计量：压缩（摘要）模型经 MeteredModel 包装（getModelName 委托穿透）
        assertThat(config.getModel()).isInstanceOf(MeteredModel.class);
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

    // ---------- #252 构建后工具面校正（框架 WebTools 恒注入 vs 平台供数方） ----------

    /** 平台 web_search 替身（AgentTool 单件桩——getTool 按名可取，callAsync 不真被调）。 */
    private static io.agentscope.core.tool.AgentTool platformWebSearchStub() {
        return webSearchStub("平台供数方版（替身）");
    }

    /** 框架版同名替身（复刻上游恒注册件：Tavily env-key 直连版的占位形态）。 */
    private static io.agentscope.core.tool.AgentTool frameworkWebSearchStub() {
        return webSearchStub("框架直连版（替身）");
    }

    private static io.agentscope.core.tool.AgentTool webSearchStub(String description) {
        return new io.agentscope.core.tool.AgentTool() {
            @Override
            public String getName() {
                return "web_search";
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public java.util.Map<String, Object> getParameters() {
                return java.util.Map.of();
            }

            @Override
            public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(
                    io.agentscope.core.tool.ToolCallParam param) {
                return reactor.core.publisher.Mono.empty();
            }
        };
    }

    /** 框架 web_fetch 替身（框架恒注册直抓件占位——与 web_search 不同名）。 */
    private static io.agentscope.core.tool.AgentTool frameworkWebFetchStub() {
        return new io.agentscope.core.tool.AgentTool() {
            @Override
            public String getName() {
                return "web_fetch";
            }

            @Override
            public String getDescription() {
                return "框架直抓版（替身）";
            }

            @Override
            public java.util.Map<String, Object> getParameters() {
                return java.util.Map.of();
            }

            @Override
            public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(
                    io.agentscope.core.tool.ToolCallParam param) {
                return reactor.core.publisher.Mono.empty();
            }
        };
    }

    @Test
    void given_framework_web_tools_overwrite_when_realign_then_platform_version_wins() {
        // 复刻框架遮蔽现场（上游 main 已恒注册 WebTools、2.0.1 尚未带——替身占位）：
        // 平台版先注册、框架版（同名 web_search）后注册即覆盖（ToolRegistry 后写胜）
        // ——校正后平台版生效、框架版出局
        Toolkit platform = new Toolkit();
        platform.registerAgentTool(platformWebSearchStub());
        Toolkit effective = new Toolkit();
        effective.registerAgentTool(platformWebSearchStub());
        effective.registerAgentTool(frameworkWebSearchStub());
        effective.registerAgentTool(frameworkWebFetchStub());
        assertThat(effective.getTool("web_search").getDescription())
                .as("前置：框架同名后注册即覆盖平台版（遮蔽现场复刻）")
                .doesNotContain("平台");

        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getToolkit()).thenReturn(effective);
        AgentscopeHarnessAgentFactory.realignBuiltinWebTools(platform, agent);

        assertThat(effective.getToolNames()).doesNotContain("web_fetch"); // 框架直抓版结构性出局
        assertThat(effective.getTool("web_search").getDescription())
                .as("平台版注册回来（后写胜反转）")
                .contains("平台");
    }

    @Test
    void given_platform_toolkit_without_web_search_when_realign_then_both_absent() {
        // 开关关（平台 toolkit 不含 web_search）：校正后 web_search/web_fetch 皆不在
        // ——「关即退出装配面」对同名框架件也成立（不残留框架直连版）
        Toolkit platform = new Toolkit(); // 空＝关态（增强件全不注册）
        Toolkit effective = new Toolkit();
        effective.registerAgentTool(frameworkWebSearchStub());
        effective.registerAgentTool(frameworkWebFetchStub());

        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getToolkit()).thenReturn(effective);
        AgentscopeHarnessAgentFactory.realignBuiltinWebTools(platform, agent);

        assertThat(effective.getToolNames())
                .doesNotContain("web_search", "web_fetch");
    }

    @Test
    void given_current_dependency_without_framework_web_tools_when_realign_then_noop() {
        // 现行依赖（agentscope 2.0.1）无框架 WebTools：校正幂等无害——平台面原样保留、
        // removeTool 对缺失名不炸（防御升级到恒注册版本时才吃上力）
        Toolkit platform = new Toolkit();
        platform.registerAgentTool(platformWebSearchStub());
        Toolkit effective = new Toolkit();
        effective.registerAgentTool(platformWebSearchStub());

        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getToolkit()).thenReturn(effective);
        AgentscopeHarnessAgentFactory.realignBuiltinWebTools(platform, agent);

        assertThat(effective.getToolNames()).containsExactly("web_search");
        assertThat(effective.getTool("web_search").getDescription()).contains("平台");
    }
}

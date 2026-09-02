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
    private static final AgentToolkitSupplier TOOLKITS = (agentRole, workspace) -> new Toolkit();

    private AgentscopeHarnessAgentFactory factoryWith(List<HarnessAgent> created) {
        return factoryWith(created, new InMemoryAgentStateStore());
    }

    private AgentscopeHarnessAgentFactory factoryWith(List<HarnessAgent> created,
                                                      AgentStateStore stateStore) {
        return new AgentscopeHarnessAgentFactory(stateStore, TOOLKITS,
                (name, sysPrompt, modelString, workspace, agentRole) -> {
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
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "BA");
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
                stateStore, TOOLKITS, new AgentscopeProperties());

        HarnessAgent agent = factory.obtain("platform-agent-t", "sys",
                "deepseek:deepseek-v4-flash", new AgentWorkspace.Local(null), null);

        assertThat(agent.getStateStore()).isSameAs(stateStore);
    }
}

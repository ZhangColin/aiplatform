package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.agentscope.AgentscopeHarnessAgentFactory;
import com.semgravio.aiplatform.base.agentscope.AgentscopeProperties;
import com.semgravio.aiplatform.base.agentscope.EdgeModelSupplier;
import com.semgravio.aiplatform.base.agentscope.KernelLoopSemantics;
import com.semgravio.aiplatform.base.skills.domain.repository.SkillStore;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;

/**
 * 内核形态中间件装配钉守（#601 接线验收，装配层单位测试）：真装配缝——真
 * {@link ProfileMiddlewareSupplier}（业务装配缝）＋真 {@link AgentscopeHarnessAgentFactory}——
 * 断言两形态中间件集合的分野：
 *
 * <ul>
 * <li><b>内核形态</b>（{@code obtainKernel}，装配键 generation-kernel）：循环语义
 * 中间件 {@link KernelLoopSemantics} 在集合内（#596 结构性挂载）；平台侧只挂
 * 全形态共享的技能加载计数（{@link SkillLoadCountMiddleware}，#261）；</li>
 * <li><b>旧执行体键形态</b>（对照腿，{@code executor} 键）：不挂循环语义、平台侧
 * 同样只有共享观测件——#604 起按 executor 键挂载的两个旧栈中间件（决策时微指令
 * #437、verify 自决 #442）已随 run 尝试体旧机制删除（executor 键不再进任何派发
 * 路径），本腿钉「executor 键也不分叉挂件」。</li>
 * </ul>
 *
 * <p>本测试归 business 测试树：组合 base 工厂与 business 中间件供应商是业务侧
 * 装配的事（base 分区不得依赖 business，ArchUnit 守护；KernelAgentAssemblyTest
 * 钉内核形态底座装配，本类钉两形态中间件分野）。</p>
 */
class KernelMiddlewareAssemblyTest {

    /** 替身模型（装配产物只验构建、不 converse——注册即可）。 */
    private static final class ProbeMwModel implements Model {

        private static final List<ProbeMwModel> PROBES = new CopyOnWriteArrayList<>();

        private final List<List<Msg>> seenMessages = new CopyOnWriteArrayList<>();
        private final List<List<ToolSchema>> seenToolSchemas = new CopyOnWriteArrayList<>();
        private final List<GenerateOptions> seenOptions = new CopyOnWriteArrayList<>();

        ProbeMwModel() {
            PROBES.add(this);
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                GenerateOptions options) {
            seenMessages.add(List.copyOf(messages));
            seenToolSchemas.add(tools == null ? List.of() : List.copyOf(tools));
            seenOptions.add(options);
            return Flux.just(new ChatResponse("resp-mw",
                    List.of(TextBlock.builder().text("装配探测回复。").build()),
                    null, Map.of(), "stop"));
        }

        @Override
        public String getModelName() {
            return "probe-mw";
        }
    }

    /** 不触网不依赖环境 key 的模型工厂注册（probe-kernel 先例换名，避免跨类互踩）。 */
    @BeforeAll
    static void registerProbeMwModelFactory() {
        ModelRegistry.registerFactory("probe-mw:.+", (modelId, context) -> new ProbeMwModel());
    }

    @BeforeEach
    void clearProbes() {
        ProbeMwModel.PROBES.clear();
    }

    private static EdgeModelSupplier probeEdgeModels() {
        EdgeModelSupplier.EdgeModel edge =
                new EdgeModelSupplier.EdgeModel("probe-mw:compaction-stub", null);
        return new EdgeModelSupplier() {
            @Override
            public EdgeModel compactionModel() {
                return edge;
            }

        };
    }

    /** 真装配缝：真 ProfileMiddlewareSupplier（SkillStore 桩）＋真工厂。 */
    private static AgentscopeHarnessAgentFactory realFactory(ProfileMiddlewareSupplier middlewares) {
        return new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(),
                (agentKey, workspace, toolSpec) -> new Toolkit(),
                (agentKey, workspace) -> List.of(),
                middlewares, probeEdgeModels(), new AgentscopeProperties(), null);
    }

    private static ProfileMiddlewareSupplier realMiddlewareSupplier() {
        return new ProfileMiddlewareSupplier(mock(SkillStore.class));
    }

    private static List<MiddlewareBase> middlewaresOf(
            HarnessAgent agent) {
        return agent.getDelegate().getMiddlewares();
    }

    @Test
    void given_kernel_form_when_obtain_kernel_then_loop_semantics_in_and_legacy_decision_middlewares_out() {
        AgentscopeHarnessAgentFactory factory = realFactory(realMiddlewareSupplier());

        HarnessAgent agent = factory.obtainKernel("probe-mw:kernel-stub", null, null,
                new AgentWorkspace.ProjectKernel("42", "ws-42-dev"), null);

        try {
            List<MiddlewareBase> middlewares = middlewaresOf(agent);
            // #596 结构性挂载：内核循环五条语义中间件在集合内
            assertThat(middlewares).anyMatch(middleware -> middleware instanceof KernelLoopSemantics);
            // 全形态共享观测件在场（#261 使用计数——装配分野不动它）；平台侧再无
            // 按配置键分叉的旧中间件（决策时微指令/verify 自决已随 #604 删除，
            // 「executor 键也不挂」见下一例与 ProfileMiddlewareSupplierTest）
            assertThat(middlewares)
                    .anyMatch(middleware -> middleware instanceof SkillLoadCountMiddleware);
        }
        finally {
            agent.close();
        }
    }

    @Test
    void given_legacy_executor_key_when_obtain_then_only_shared_observation_and_no_loop_semantics() {
        // 对照腿：旧执行体键（executor）不挂循环语义，平台侧只有共享观测件——
        // #604 起该键不再挂决策时微指令/verify 自决旧中间件（已删除，无派发路径）
        AgentscopeHarnessAgentFactory factory = realFactory(realMiddlewareSupplier());

        HarnessAgent agent = factory.obtain("run-executor", "sys", "probe-mw:kernel-stub",
                null, null, new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "executor", null);

        try {
            List<MiddlewareBase> middlewares = middlewaresOf(agent);
            assertThat(middlewares)
                    .noneMatch(middleware -> middleware instanceof KernelLoopSemantics);
            assertThat(middlewares)
                    .anyMatch(middleware -> middleware instanceof SkillLoadCountMiddleware);
            // 平台中间件供应商对该键只给一件共享观测件（业务装配面不分叉挂件——
            // 旧形态另有 harness 内建件，不在本断言面；供应商级断言见
            // ProfileMiddlewareSupplierTest）
            assertThat(realMiddlewareSupplier().middlewaresFor("executor",
                    new AgentWorkspace.ProjectDev("42", "ws-42-dev")))
                    .hasSize(1)
                    .allMatch(middleware -> middleware instanceof SkillLoadCountMiddleware);
        }
        finally {
            agent.close();
        }
    }
}

package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 生成内核装配形态（#595，ADR-0054）：装配产物可断言——提示词结构完整且住代码
 * （obtainKernel 钉权威版本，调用方不可覆盖）、工具面恰四件（{@link KernelTools}，
 * 与旧工具面/框架内置件隔离）、无技能／无子智能体委派挂载；会话状态存储、上下文
 * 压缩、pending tool 恢复、内核重试关停沿用现有装配（本票一行不改，此处只钉
 * 「照旧接线」事实；压缩的<b>触发规则</b>自 #598 起照 DeepSeek Harness 口径——不按
 * 消息条数、token 阈值按主模型窗口现算，见「压缩触发规则」一节）；替身模型
 * converse 冒烟（装配层 seam，#593 Testing Decisions 唯一新 seam）。
 */
class KernelAgentAssemblyTest {

    /** 工具面规格钉死：恰四件，名字即 #594 内核四件。 */
    private static final List<String> KERNEL_TOOL_NAMES =
            List.of("write_file", "read_file", "edit_file", "bash");

    /**
     * 冒烟模型桩（装配层 seam 的替身模型）：捕获模型可见的系统提示／工具 schema／
     * 消息序列，按脚本回吐纯文本响应（不调工具即一轮收口——循环语义是 #596 的票，
     * 本类只验装配产物与 converse 通路）。注册工厂每次解析新建实例，全部入
     * {@link #PROBES}——测试按「记录到调用」认领（不经包装层级 unwrap，包装结构
     * 是平台装配内部细节，本测试不耦合）。
     */
    private static final class ProbeKernelModel implements Model {

        private static final List<ProbeKernelModel> PROBES = new CopyOnWriteArrayList<>();

        private final List<List<Msg>> seenMessages = new CopyOnWriteArrayList<>();
        private final List<List<ToolSchema>> seenToolSchemas = new CopyOnWriteArrayList<>();
        private final List<GenerateOptions> seenOptions = new CopyOnWriteArrayList<>();
        private final Queue<ChatResponse> script;

        ProbeKernelModel(ChatResponse... responses) {
            this.script = new ConcurrentLinkedQueue<>(List.of(responses));
            PROBES.add(this);
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                GenerateOptions options) {
            seenMessages.add(List.copyOf(messages));
            seenToolSchemas.add(tools == null ? List.of() : List.copyOf(tools));
            seenOptions.add(options);
            ChatResponse next = script.poll();
            return next == null
                    ? Flux.error(new IllegalStateException("probe-kernel 脚本耗尽"))
                    : Flux.just(next);
        }

        @Override
        public String getModelName() {
            return "probe-kernel";
        }
    }

    private static ChatResponse textReply(String text) {
        return new ChatResponse("resp-kernel", List.of(TextBlock.builder().text(text).build()),
                null, Map.of(), "stop");
    }

    /** 不触网不依赖环境 key 的模型工厂注册（probe557 先例）：主模型与压缩档同桩。 */
    @BeforeAll
    static void registerProbeKernelModelFactory() {
        ModelRegistry.registerFactory("probe-kernel:.+", (modelId, context) -> new ProbeKernelModel(
                textReply("内核冒烟回复。")));
    }

    private static EdgeModelSupplier probeEdgeModels() {
        EdgeModelSupplier.EdgeModel edge = new EdgeModelSupplier.EdgeModel(
                "probe-kernel:compaction-stub", null);
        return new EdgeModelSupplier() {
            @Override
            public EdgeModel compactionModel() {
                return edge;
            }

        };
    }

    private static AgentscopeHarnessAgentFactory realFactory(AgentStateStoreProbe probe) {
        return realFactory(probe, new AgentscopeProperties());
    }

    private static AgentscopeHarnessAgentFactory realFactory(AgentStateStoreProbe probe,
            AgentscopeProperties properties) {
        return new AgentscopeHarnessAgentFactory(probe.store(), (agentKey, workspace, toolSpec) ->
                new Toolkit(),
                (agentKey, workspace) -> List.of(),
                (agentKey, workspace) -> List.of(), probeEdgeModels(),
                properties, null);
    }

    /** 状态存储探针：钉「会话状态存储沿用现有装配」——agent 持有的是注入实例。 */
    private record AgentStateStoreProbe(InMemoryAgentStateStore store) {
    }

    private static AgentStateStoreProbe stateProbe() {
        return new AgentStateStoreProbe(new InMemoryAgentStateStore());
    }

    private static AgentWorkspace.ProjectKernel kernelWorkspace() {
        return new AgentWorkspace.ProjectKernel("42", "ws-42-dev");
    }

    // ---------- 提示词权威：住代码、调用方不可覆盖 ----------

    /** 每例开局清探针列表（防跨例残留——探针按「记录到调用」认领，同 KernelAgentLoopTest 先例）。 */
    @BeforeEach
    void clearProbes() {
        ProbeKernelModel.PROBES.clear();
    }

    @Test
    void given_kernel_workspace_when_obtain_with_foreign_prompt_then_rejected() {
        // 权威版本住代码的守卫：内核形态不允许调用方自带提示词——改提示词＝改内核＝
        // 开 issue（#593 用户故事 9/11）；绕过 obtainKernel 直用内核工作区即拒
        AgentscopeHarnessAgentFactory factory = realFactory(stateProbe());

        assertThatThrownBy(() -> factory.obtain("any-name", "外来提示词",
                "probe-kernel:kernel-stub", null, null, kernelWorkspace(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KernelSystemPrompt");
    }

    @Test
    void given_kernel_workspace_when_obtain_with_authoritative_prompt_then_accepted() {
        // 对称正向：携权威版本直装配可行（obtainKernel 内部即此形态）
        AgentscopeHarnessAgentFactory factory = realFactory(stateProbe());

        HarnessAgent agent = factory.obtain("generation-kernel", KernelSystemPrompt.text(),
                "probe-kernel:kernel-stub", null, null, kernelWorkspace(), null, null);

        try {
            assertThat(agent.getDelegate().getSysPrompt()).isEqualTo(KernelSystemPrompt.text());
        } finally {
            agent.close();
        }
    }

    @Test
    void given_obtain_kernel_when_built_then_name_and_prompt_pinned_by_entry() {
        // obtainKernel 入口把名字与权威提示词钉进装配（调用方无覆盖面）
        List<String> names = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(),
                (agentKey, workspace, toolSpec) -> new Toolkit(),
                (name, sysPrompt, modelString, modelChannel, modelQueue, compaction,
                        workspace, agentKey, toolSpec) -> {
                    names.add(name);
                    prompts.add(sysPrompt);
                    return mock(HarnessAgent.class);
                },
                probeEdgeModels());

        factory.obtainKernel("probe-kernel:kernel-stub", null, null, kernelWorkspace(), null);

        assertThat(names).containsExactly("generation-kernel");
        assertThat(prompts).containsExactly(KernelSystemPrompt.text());
    }

    @Test
    void given_same_kernel_spec_when_obtain_kernel_twice_then_built_once_and_reused() {
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(),
                (agentKey, workspace, toolSpec) -> new Toolkit(),
                (name, sysPrompt, modelString, modelChannel, modelQueue, compaction,
                        workspace, agentKey, toolSpec) -> {
                    HarnessAgent agent = mock(HarnessAgent.class);
                    created.add(agent);
                    return agent;
                },
                probeEdgeModels());

        HarnessAgent first = factory.obtainKernel("probe-kernel:kernel-stub", null, null,
                kernelWorkspace(), null);
        HarnessAgent again = factory.obtainKernel("probe-kernel:kernel-stub", null, null,
                kernelWorkspace(), null);
        // 与内核形态并列的现有形态不受内核规格干扰（同容器双形态＝两实例）
        factory.obtain("platform-agent", "sys", "probe-kernel:kernel-stub", null, null,
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null, null);

        assertThat(again).isSameAs(first);
        assertThat(created).hasSize(2);
    }

    // ---------- 装配产物：工具面恰四件、无技能/委派、底座沿用 ----------

    @Test
    void given_kernel_agent_when_built_then_toolkit_exactly_kernel_four() {
        AgentStateStoreProbe probe = stateProbe();
        AgentscopeHarnessAgentFactory factory = realFactory(probe);

        HarnessAgent agent = factory.obtainKernel("probe-kernel:kernel-stub", null, null,
                kernelWorkspace(), null);

        try {
            // 恰四件：无框架内置件（execute/wait_async_results/web_*）、无旧工具面件
            assertThat(agent.getToolkit().getToolNames())
                    .containsExactlyInAnyOrderElementsOf(KERNEL_TOOL_NAMES);
            // 四件即 #594 内核件（DSH 说明文字辨认实例血统——非框架 FilesystemTool 同名件）
            assertThat(agent.getToolkit().getTool("write_file").getDescription())
                    .isEqualTo("Create or fully replace a UTF-8 text file "
                            + "(parent directories are created automatically).");
            assertThat(agent.getToolkit().getTool("read_file").getDescription())
                    .isEqualTo("Read a UTF-8 text file and return line-numbered content.");
            assertThat(agent.getToolkit().getTool("edit_file").getDescription())
                    .isEqualTo("Edit an existing UTF-8 text file by replacing literal text.");
            assertThat(agent.getToolkit().getTool("bash").getDescription())
                    .isEqualTo("在容器内 sh -c 执行命令（超时 180s）");
        } finally {
            agent.close();
        }
    }

    @Test
    void given_kernel_agent_when_built_then_no_skills_no_subagents_mounted() {
        AgentStateStoreProbe probe = stateProbe();
        AgentscopeHarnessAgentFactory factory = realFactory(probe);

        HarnessAgent agent = factory.obtainKernel("probe-kernel:kernel-stub", null, null,
                kernelWorkspace(), null);

        try {
            assertThat(agent.getSkillRepositories()).isEmpty();
            assertThat(agent.getSubagentAgentManager()).isNull();
        } finally {
            agent.close();
        }
    }

    @Test
    void given_kernel_agent_when_built_then_existing_assembly_reused_unchanged() {
        // 既有装配一行不改的钉守：状态存储实例、压缩接线、pending 恢复、重试关停
        AgentStateStoreProbe probe = stateProbe();
        AgentscopeHarnessAgentFactory factory = realFactory(probe);

        HarnessAgent agent = factory.obtainKernel("probe-kernel:kernel-stub", null, null,
                kernelWorkspace(), null);

        try {
            assertThat(agent.getStateStore()).isSameAs(probe.store());
            assertThat(agent.getCompactionHook()).isNotNull();
            assertThat(agent.getDelegate().isPendingToolRecoveryEnabled()).isTrue();
        } finally {
            agent.close();
        }
    }

    // ---------- 压缩触发规则（#598 真跑实证后照 DeepSeek Harness 口径修正） ----------

    /**
     * 内核形态不按消息条数压缩（框架缺省 50 条，对内核关停）；token 阈值照 DeepSeek
     * Harness compaction-basic 原式 min(窗口×0.8, 窗口−输出上限−65,536)——deepseek-flash
     * 窗口 1,048,576、输出上限 32,768：min(838,861, 950,272)=838,861（框架估算 token）。
     * 压缩前 offload 关停、压缩模型在位——与旧形态同一份公共段。行为面（不按条数／
     * 越 token 阈值才压）见 KernelAgentLoopTest 文末两例。
     */
    @Test
    void given_kernel_form_when_compaction_config_then_no_message_count_trigger_and_harness_threshold() {
        CompactionConfig config = AgentscopeHarnessAgentFactory.kernelCompactionConfig(
                new AgentscopeProperties(), probeEdgeModels().compactionModel(), null,
                /* contextWindow= */ 1_048_576, /* reservedOutputTokens= */ 32_768);

        assertThat(config.getTriggerMessages()).isZero();
        assertThat(config.getTriggerTokens()).isEqualTo(838_861);
        assertThat(config.isOffloadBeforeCompact()).isFalse();
        assertThat(config.getModel()).isNotNull();
    }

    /** 阈值原式逐项：预算项胜出／窗口未登记回框架动态口径／余量算尽回动态口径。 */
    @Test
    void given_kernel_compaction_threshold_when_inputs_vary_then_harness_formula() {
        // 预算项胜出：min(200,000×0.8=160,000, 200,000−32,768−65,536=101,696)
        assertThat(AgentscopeHarnessAgentFactory.kernelCompactionTriggerTokens(200_000, 32_768))
                .isEqualTo(101_696);
        assertThat(AgentscopeHarnessAgentFactory.kernelCompactionTriggerTokens(0, 32_768)).isZero();
        assertThat(AgentscopeHarnessAgentFactory.kernelCompactionTriggerTokens(100_000, 40_000)).isZero();
    }

    /** 旧形态一行不动（既有行为钉守，非认可其口径）：固定 token 阈值照配置、消息条数规则仍是框架缺省。 */
    @Test
    void given_legacy_form_when_compaction_config_then_fixed_threshold_and_framework_default_untouched() {
        CompactionConfig config = AgentscopeHarnessAgentFactory.compactionConfig(
                new AgentscopeProperties(), probeEdgeModels().compactionModel(), null);

        assertThat(config.getTriggerTokens()).isEqualTo(400_000);
        assertThat(config.getTriggerMessages()).isEqualTo(50);
    }

    // ---------- 替身模型 converse 冒烟 ----------

    @Test
    void given_stub_model_when_converse_then_round_trips_with_kernel_face_only() {
        AgentStateStoreProbe probe = stateProbe();
        AgentscopeHarnessAgentFactory factory = realFactory(probe);

        HarnessAgent agent = factory.obtainKernel("probe-kernel:kernel-stub", null, null,
                kernelWorkspace(), null);

        try {
            List<String> texts = new ArrayList<>();
            agent.streamEvents(List.of(new UserMessage("开始")), RuntimeContext.empty())
                    .doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            texts.add(delta.getDelta());
                        }
                    })
                    .blockLast(Duration.ofSeconds(10));

            assertThat(String.join("", texts)).isEqualTo("内核冒烟回复。");

            // 模型可见面：系统提示＝权威版本、工具 schema＝恰四件（按记录到调用认领本
            // 例桩——同 JVM 多例各持各的桩，恰好一个被 converse 真调过）
            List<ProbeKernelModel> called = ProbeKernelModel.PROBES.stream()
                    .filter(candidate -> !candidate.seenMessages.isEmpty())
                    .toList();
            assertThat(called).hasSize(1);
            ProbeKernelModel stub = called.get(0);
            Msg first = stub.seenMessages.get(0).get(0);
            // 权威版本逐字送达模型（框架系统提示惯例在我们正文后追加自己的上下文段
            // ——既有装配行为，全部形态同此；本断言钉「正文逐字、位置在首」）
            assertThat(textOf(first)).startsWith(KernelSystemPrompt.text());
            assertThat(stub.seenToolSchemas.get(0))
                    .extracting(ToolSchema::getName)
                    .containsExactlyInAnyOrderElementsOf(KERNEL_TOOL_NAMES);
        } finally {
            agent.close();
        }
    }

    private static String textOf(Msg message) {
        List<TextBlock> blocks = message.getContentBlocks(TextBlock.class);
        assertThat(blocks).isNotEmpty();
        return blocks.get(0).getText();
    }

    // ---------- 输出上限开关（#597，ADR-0054／#593 用户故事 10）：每次请求携带 ----------

    @Test
    void given_kernel_agent_when_converse_then_output_cap_default_32768_on_every_request() {
        AgentStateStoreProbe probe = stateProbe();
        AgentscopeHarnessAgentFactory factory = realFactory(probe);
        HarnessAgent agent = factory.obtainKernel("probe-kernel:kernel-stub", null, null,
                kernelWorkspace(), null);
        try {
            converseOnce(agent);

            ProbeKernelModel stub = calledProbe();
            // 每次模型请求都携 max_tokens＝默认 32768（装配 generateOptions→框架
            // 每次 stream 的 callOptions——「每次请求的配置开关」的兑现路径）
            assertThat(stub.seenOptions).isNotEmpty();
            assertThat(stub.seenOptions).allSatisfy(options ->
                    assertThat(options.getMaxTokens()).isEqualTo(32768));
        } finally {
            agent.close();
        }
    }

    @Test
    void given_operator_overrides_output_cap_when_converse_then_adjusted_value_on_request() {
        AgentStateStoreProbe probe = stateProbe();
        AgentscopeProperties properties = new AgentscopeProperties();
        properties.setKernelMaxOutputTokens(8192);
        AgentscopeHarnessAgentFactory factory = realFactory(probe, properties);
        HarnessAgent agent = factory.obtainKernel("probe-kernel:kernel-stub", null, null,
                kernelWorkspace(), null);
        try {
            converseOnce(agent);

            ProbeKernelModel stub = calledProbe();
            assertThat(stub.seenOptions).isNotEmpty();
            assertThat(stub.seenOptions).allSatisfy(options ->
                    assertThat(options.getMaxTokens()).isEqualTo(8192));
        } finally {
            agent.close();
        }
    }

    private static void converseOnce(HarnessAgent agent) {
        agent.streamEvents(List.of(new UserMessage("开始")), RuntimeContext.empty())
                .blockLast(Duration.ofSeconds(10));
    }

    private static ProbeKernelModel calledProbe() {
        List<ProbeKernelModel> called = ProbeKernelModel.PROBES.stream()
                .filter(candidate -> !candidate.seenMessages.isEmpty())
                .toList();
        assertThat(called).hasSize(1);
        return called.get(0);
    }
}

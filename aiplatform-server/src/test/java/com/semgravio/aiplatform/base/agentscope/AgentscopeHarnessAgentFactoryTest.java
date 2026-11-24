package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.port.UsageEventSink;

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


    /** 恒空平台中间件（中间件装配另有缝测，工厂测试只验内核接线）。 */
    private static final AgentMiddlewareSupplier NO_MIDDLEWARES =
            (agentKey, workspace) -> List.of();

    /** 计量夹具时钟（#324 UsageContext 形参——本类用例只断言语量，时戳取系统钟）。 */
    private static final Clock CLOCK = Clock.systemUTC();

    /**
     * 边缘模型固定桩（#366 压缩配置装配腿）：无通道语境的 deepseek 串——SDK 内置
     * provider 缺省路径，工厂测试不依赖库/通道注册（配置链装配断言归 business 侧；
     * 走替身 builder 的实例键测试用本桩，真构建测试用 {@link #volcEdgeModels()}）。
     */
    private static final EdgeModelSupplier EDGE_MODELS = new EdgeModelSupplier() {
        @Override
        public EdgeModel compactionModel() {
            return new EdgeModel("deepseek:deepseek-v4-flash", null);
        }

        @Override
        public EdgeModel precheckModel() {
            return new EdgeModel("deepseek:deepseek-v4-flash", null);
        }
    };

    /**
     * 主通道真构建语境（#369 退场确认：真构建断言不再依赖 DEEPSEEK_API_KEY 环境
     * 腿）：volcengine 平台工厂注册＋通道件（key 从部署环境读，与测试库通道行种子
     * 同源；库为运行期唯一正本，测试直构无库故同源 env）。无 key 环境真构建断言
     * 跳过（Assumption），实例键类桩测试不受影响。
     */
    private static ModelChannel volcChannel() {
        VolcengineModelRegistrar.register();
        return new ModelChannel("https://ark.cn-beijing.volces.com/api/v3",
                System.getenv("VOLCENGINE_API_KEY"), false);
    }

    private static void requireVolcengineKey() {
        assumeTrue(System.getenv("VOLCENGINE_API_KEY") != null
                        && !System.getenv("VOLCENGINE_API_KEY").isBlank(),
                "无 VOLCENGINE_API_KEY，跳过真构建断言（主通道 volcengine，#369）");
    }

    /** 真构建路径的边缘模型供数（压缩腿同走主通道——deepseek 桩串需 env key，真构建不用）。 */
    private static EdgeModelSupplier volcEdgeModels() {
        ModelChannel channel = volcChannel();
        EdgeModelSupplier.EdgeModel flash =
                new EdgeModelSupplier.EdgeModel("volcengine:deepseek-v4-flash-ga-260731", channel);
        return new EdgeModelSupplier() {
            @Override
            public EdgeModelSupplier.EdgeModel compactionModel() {
                return flash;
            }

            @Override
            public EdgeModelSupplier.EdgeModel precheckModel() {
                return flash;
            }
        };
    }

    @Test
    void given_state_store_when_built_then_wired_into_agent() {
        // 真构建路径（不走替身 builder）：会话恢复的落点——agent 持有的是注入的
        // store（生产为 PG 版），非框架缺省的本地 JSON 文件实现。
        // builder().model() 即解析模型串（需通道 key），无 key 环境跳过（冒烟同款口径）
        requireVolcengineKey();
        AgentStateStore stateStore = new InMemoryAgentStateStore();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                stateStore, TOOLKITS, SKILL_REPOS, NO_MIDDLEWARES, volcEdgeModels(),
                new AgentscopeProperties(), null);

        HarnessAgent agent = factory.obtain("platform-agent-t", "sys",
                "volcengine:deepseek-v4-flash-ga-260731", volcChannel(), null,
                new AgentWorkspace.Local(null), null, null);

        assertThat(agent.getStateStore()).isSameAs(stateStore);
        // 压缩接线守护（#108）：工厂显式配 compactionConfig → 压缩中间件非空（非 disableCompaction）
        assertThat(agent.getCompactionHook()).isNotNull();
    }

    /**
     * pending tool 恢复接线守护（#431）：生产实测 verify 子智能体会话反复
     * IllegalStateException: Pending tool calls exist without results——会话（PG 槽位）
     * 带着上次中断遗留的无结果 tool_use 续跑即炸、整轮尝试被吞。框架
     * enablePendingToolRecovery＝resume 时对无结果 pending 调用自动补 ERROR 结果
     * 继续走（WARN 遥测落框架日志），主智能体显式开启。真构建路径（需 API key，
     * 无 key 跳过——同 {@link #given_state_store_when_built_then_wired_into_agent} 口径）。
     */
    @Test
    void given_any_build_when_built_then_pending_tool_recovery_enabled() {
        requireVolcengineKey();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), TOOLKITS, SKILL_REPOS, NO_MIDDLEWARES,
                volcEdgeModels(), new AgentscopeProperties(), null);

        HarnessAgent agent = factory.obtain("platform-agent-t", "sys",
                "volcengine:deepseek-v4-flash-ga-260731", volcChannel(), null,
                new AgentWorkspace.Local(null), null, null);

        try {
            assertThat(agent.getDelegate().isPendingToolRecoveryEnabled()).isTrue();
        } finally {
            agent.close();
        }
    }

    /**
     * 委派位结构守护（#95）：ProjectDev（run 执行体）开子智能体（工厂不再 disableSubagents，
     * 框架装 subagent 中间件——含通用子智能体），ProjectReadOnly（主智能体对话姿态）关
     * 子智能体（委派是 run 内机制，主智能体永不委派）。真构建路径（需 API key 建模型，
     * 无 key 跳过——同 {@link #given_state_store_when_built_then_wired_into_agent} 口径）。
     */
    @Test
    void given_project_dev_when_built_then_subagents_enabled() {
        requireVolcengineKey();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), TOOLKITS, SKILL_REPOS, NO_MIDDLEWARES,
                volcEdgeModels(), new AgentscopeProperties(), null);

        HarnessAgent dev = factory.obtain("platform-agent", "sys",
                "volcengine:deepseek-v4-flash-ga-260731", volcChannel(), null,
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "executor", null);

        assertThat(dev.getSubagentAgentManager()).isNotNull();
    }

    @Test
    void given_project_read_only_when_built_then_subagents_disabled() {
        requireVolcengineKey();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), TOOLKITS, SKILL_REPOS, NO_MIDDLEWARES,
                volcEdgeModels(), new AgentscopeProperties(), null);

        HarnessAgent readOnly = factory.obtain("platform-agent", "sys",
                "volcengine:deepseek-v4-flash-ga-260731", volcChannel(), null,
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "main", null);

        assertThat(readOnly.getSubagentAgentManager()).isNull();
    }

    /**
     * 设计面结构守护（#289，ADR-0025 无 shell）：ProjectDesign（设计执行体姿态）
     * ——内核文件工具（写文件件）保留、shell 工具与委派结构性关闭。真构建路径
     * （需 API key 建模型，无 key 跳过——同上口径）。
     */
    @Test
    void given_project_design_when_built_then_file_tools_kept_shell_and_subagents_disabled() {
        requireVolcengineKey();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), TOOLKITS, SKILL_REPOS, NO_MIDDLEWARES,
                volcEdgeModels(), new AgentscopeProperties(), null);

        HarnessAgent designer = factory.obtain("platform-agent", "sys",
                "volcengine:deepseek-v4-flash-ga-260731", volcChannel(), null,
                new AgentWorkspace.ProjectDesign("42", "ws-42-dev"), "designer", null);

        // 写文件件在（界面稿 HTML 落工作区的执行面）；shell（execute）结构性不在
        assertThat(designer.getToolkit().getToolNames())
                .contains("write_file")
                .doesNotContain("execute");
        // 委派关闭（设计会话不委派——同只读面口径）
        assertThat(designer.getSubagentAgentManager()).isNull();
    }

    // ---------- #557 候选队列：入实例缓存键＋透传 builder（failover 装配面） ----------

    @Test
    void given_model_queue_when_obtain_then_passed_through_and_keyed_by_whole_queue() {
        // 整队列入实例缓存键：任一候选换 key/换模型＝指纹变＝新实例（「改配置即生效、
        // 进行中 run 不定格」对整个 failover 队列成立）；首候选同值时双字段形与队列形
        // 键一致（同规格复用，软着陆）
        List<ModelCandidateQueue> passed = new ArrayList<>();
        List<HarnessAgent> created = new ArrayList<>();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), TOOLKITS,
                (name, sysPrompt, modelString, modelChannel, modelQueue, compaction,
                        workspace, agentKey, toolSpec) -> {
                    passed.add(modelQueue);
                    HarnessAgent agent = mock(HarnessAgent.class);
                    created.add(agent);
                    return agent;
                }, EDGE_MODELS);

        ModelChannel chA = new ModelChannel("https://a.example", "sk-a", false);
        ModelChannel chB = new ModelChannel("https://b.example", "sk-b", false);
        ModelCandidateQueue queue = new ModelCandidateQueue("pro", List.of(
                new ModelCandidate("deepseek", "deepseek-v4-pro", chA),
                new ModelCandidate("deepseek", "deepseek-v4-pro", chB)));

        factory.obtain("platform-agent", "sys", queue.modelString(), chA, queue,
                new AgentWorkspace.Local(null), null, null);
        // 同队列重取＝复用
        factory.obtain("platform-agent", "sys", queue.modelString(), chA, queue,
                new AgentWorkspace.Local(null), null, null);
        assertThat(created).hasSize(1);
        assertThat(passed.get(0)).isSameAs(queue);

        // 备候选换 key＝新实例（首候选未动——failover 面对全队列成立）
        ModelCandidateQueue backupRotated = new ModelCandidateQueue("pro", List.of(
                new ModelCandidate("deepseek", "deepseek-v4-pro", chA),
                new ModelCandidate("deepseek", "deepseek-v4-pro",
                        new ModelChannel("https://b.example", "sk-b2", false))));
        factory.obtain("platform-agent", "sys", queue.modelString(), chA, backupRotated,
                new AgentWorkspace.Local(null), null, null);
        assertThat(created).hasSize(2);
    }

    /** #557 装配形态测试桩：不触网不依赖环境 key（#363/#379 probe 先例）。 */
    @org.junit.jupiter.api.BeforeAll
    static void registerProbe557ModelFactory() {
        io.agentscope.core.model.ModelRegistry.registerFactory("probe557:.+",
                (modelId, context) -> new io.agentscope.core.model.Model() {
                    @Override
                    public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(
                            java.util.List<io.agentscope.core.message.Msg> messages,
                            java.util.List<io.agentscope.core.model.ToolSchema> tools,
                            io.agentscope.core.model.GenerateOptions options) {
                        return reactor.core.publisher.Flux.error(
                                new IllegalStateException("probe557 不期待真调用"));
                    }

                    @Override
                    public String getModelName() {
                        return "probe557";
                    }
                });
    }

    @Test
    void given_single_candidate_queue_when_failover_layer_then_plain_stream_layer() {
        // 单候选队列＝无切换面：streamLayer 直装（FailoverModel 不进场——软着陆，
        // 行为与既有单候选路径一致）
        ModelCandidateQueue single = ModelCandidateQueue.single("probe557:probe-model", null);
        assertThat(AgentscopeHarnessAgentFactory.failoverLayer(single,
                new AgentscopeProperties(), null))
                .isInstanceOf(MeteredModel.class);
    }

    @Test
    void given_multi_candidate_queue_when_failover_layer_then_failover_model_assembled() {
        // 多候选队列＝FailoverModel 装配（首候选急切解析——构建即校验；备候选懒装配
        // ——probe 工厂零触网，装配形态即验证面）
        ModelCandidateQueue queue = new ModelCandidateQueue("pro", List.of(
                new ModelCandidate("probe557", "probe-main", null),
                new ModelCandidate("probe557", "probe-backup", null)));
        assertThat(AgentscopeHarnessAgentFactory.failoverLayer(queue,
                new AgentscopeProperties(), null))
                .isInstanceOf(FailoverModel.class);
    }

    // ---------- #260 subagent 槽装配一等化：声明转平台子智能体工厂（技能面按子键接视图） ----------

    /** 可变技能名桩仓库（装配缝：仓库实例内容随指派变——identity 断言＋动态重读两用）。 */
    private static final class StubSkillRepository
            implements io.agentscope.core.skill.repository.AgentSkillRepository {

        private final List<String> names;

        StubSkillRepository(String... names) {
            this.names = List.of(names);
        }

        @Override
        public io.agentscope.core.skill.AgentSkill getSkill(String name) {
            return null;
        }

        @Override
        public List<String> getAllSkillNames() {
            return names;
        }

        @Override
        public List<io.agentscope.core.skill.AgentSkill> getAllSkills() {
            return List.of();
        }

        @Override
        public boolean save(List<io.agentscope.core.skill.AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return names.contains(skillName);
        }

        @Override
        public io.agentscope.core.skill.repository.AgentSkillRepositoryInfo getRepositoryInfo() {
            return new io.agentscope.core.skill.repository.AgentSkillRepositoryInfo(
                    "stub", "stub:" + names, false);
        }

        @Override
        public String getSource() {
            return "stub";
        }

        @Override
        public void setWriteable(boolean writeable) {
        }

        @Override
        public boolean isWriteable() {
            return false;
        }
    }


    /** 命名 AgentTool 桩（allowlist 过滤断言用——callAsync 不真被调）。 */
    private static final class AgentToolStub implements io.agentscope.core.tool.AgentTool {

        private final String name;

        private AgentToolStub(String name) {
            this.name = name;
        }

        static AgentToolStub named(String name) {
            return new AgentToolStub(name);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "桩：" + name;
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
    }

    /**
     * 压缩显式配置（#108 / ADR-0012；#366 边缘点收编）：触发阈值 400K + 压缩配置
     * 解析产物（模型来自 {@link EdgeModelSupplier} 注入，非 yml——缺省 flash 档
     * 由配置面保证）。模型解析走主通道（volcengine 工厂，通道件携 key——
     * #369 退场确认后无 DEEPSEEK_API_KEY 环境腿），无 key 跳过。
     */
    @Test
    void given_resolved_compaction_model_when_compaction_config_then_explicit_threshold_and_model() {
        requireVolcengineKey();

        CompactionConfig config = AgentscopeHarnessAgentFactory.compactionConfig(
                new AgentscopeProperties(),
                new EdgeModelSupplier.EdgeModel("volcengine:deepseek-v4-flash-ga-260731",
                        volcChannel()), null);

        assertThat(config.getTriggerTokens()).isEqualTo(400_000);
        assertThat(config.getModel()).isNotNull();
        // #109 模型边界计量：压缩（摘要）模型经 MeteredModel 包装（getModelName 委托穿透）
        assertThat(config.getModel()).isInstanceOf(MeteredModel.class);
        assertThat(config.getModel().getModelName()).isEqualTo("deepseek-v4-flash-ga-260731");
    }

    @Test
    void given_pro_tier_compaction_model_when_compaction_config_then_resolved_string_lands() {
        // 模型来自解析产物（配置链半边）：EdgeModel 换 pro 串即落 pro——配置面改档
        // 经供数口直达装配，properties 无模型键可回
        requireVolcengineKey();

        CompactionConfig config = AgentscopeHarnessAgentFactory.compactionConfig(
                new AgentscopeProperties(),
                new EdgeModelSupplier.EdgeModel("volcengine:deepseek-v4-pro-ga-260813",
                        volcChannel()), null);

        assertThat(config.getTriggerTokens()).isEqualTo(400_000);
        assertThat(config.getModel().getModelName()).isEqualTo("deepseek-v4-pro-ga-260813");
    }

    @Test
    void given_same_main_spec_when_compaction_tier_changes_then_new_instance() {
        // #366 压缩配置入实例缓存键（生效配置指纹半边）：后台改 compaction 档，
        // 同主规格下一轮 obtain 即新实例——改配置即生效、进行中 run 不定格
        List<HarnessAgent> created = new ArrayList<>();
        ModelChannel compactionChannel =
                new ModelChannel("https://ark.example/api/v3", "sk-a", false);
        java.util.concurrent.atomic.AtomicReference<EdgeModelSupplier.EdgeModel> compaction =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new EdgeModelSupplier.EdgeModel("volcengine:deepseek-v4-flash-ga-260731",
                                compactionChannel));
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), TOOLKITS,
                (name, sysPrompt, modelString, modelChannel, modelQueue, compactionModel,
                        workspace, agentKey, toolSpec) -> {
                    HarnessAgent agent = mock(HarnessAgent.class);
                    created.add(agent);
                    return agent;
                },
                new EdgeModelSupplier() {
                    @Override
                    public EdgeModel compactionModel() {
                        return compaction.get();
                    }

                    @Override
                    public EdgeModel precheckModel() {
                        return new EdgeModel("deepseek:deepseek-v4-flash", null);
                    }
                });
        ModelChannel main = new ModelChannel("https://ark.example/api/v3", "sk-a", false);

        HarnessAgent first = factory.obtain("platform-agent", "sys", "volcengine:main-model",
                main, null, new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "executor", null);
        HarnessAgent sameCompaction = factory.obtain("platform-agent", "sys",
                "volcengine:main-model", main, null,
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "executor", null);
        assertThat(sameCompaction).isSameAs(first); // 同压缩档＝同规格复用

        compaction.set(new EdgeModelSupplier.EdgeModel("volcengine:deepseek-v4-pro-ga-260813",
                compactionChannel));
        HarnessAgent tierChanged = factory.obtain("platform-agent", "sys", "volcengine:main-model",
                main, null, new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "executor", null);
        assertThat(tierChanged).isNotSameAs(first); // 改压缩档＝新实例
        assertThat(created).hasSize(2);
    }

    // ---------- #321/#322 模型流层平台包装（断流重试 + 总时长上限 + 计量边界） ----------

    /**
     * 模型流层装配序（#321）：{@code MeteredModel(RetryingModel(raw))}——重试内圈
     * （429/5xx 零发射失败流层接续）、计量外圈（整流收口上报）。护栏不变式（任何
     * 发射只来自末次尝试）下重试合并为一轮上报＝与单次直调完全同形——重试期间
     * 计量路径不被破坏（AC 对账口径）。行为级钉装配：重试真发生（raw 两次直调）+
     * 计量真入账（恰一条 usage 事件、deepseek 归属）。
     */
    @Test
    void given_429_then_success_when_stream_layer_then_retried_and_metered_once()
            throws InterruptedException {
        Model raw = mock(Model.class);
        when(raw.stream(any(), any(), any()))
                .thenReturn(Flux.error(new OpenAIException("HTTP 429", 429, null)))
                .thenReturn(Flux.just(ChatResponse.builder()
                        .usage(new ChatUsage(100, 40, 0, 0.5))
                        .build()));
        List<UsageEvent> events = new ArrayList<>();
        UsageEventSink sink = events::add;
        AgentscopeProperties properties = new AgentscopeProperties();
        properties.setModelStreamRetryInitialBackoff(java.time.Duration.ofMillis(10));
        properties.setModelStreamRetryMaxBackoff(java.time.Duration.ofMillis(50));

        // 计量上下文随订阅链传递（#324）：订阅点 contextWrite 写入（runTurn 同形态）
        AgentscopeHarnessAgentFactory
                .streamLayer(raw, "deepseek:deepseek-v4-flash", properties)
                .stream(List.of(), null, null)
                .contextWrite(ctx -> ctx.put(MeteringScope.CONTEXT_KEY, new MeteringScope(
                        "run-1", "s-1", new UsageContext("prj-1", Map.of()), CLOCK, sink,
                        "prefix-run-1")))
                .blockLast();
        // doFinally 计量收口与下游终端信号跨线程——有界等待消竞态
        long deadline = System.currentTimeMillis() + 2000;
        while (events.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }

        verify(raw, times(2)).stream(any(), any(), any());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).tokens().input()).isEqualTo(100);
        assertThat(events.get(0).tokens().output()).isEqualTo(40);
    }

    /**
     * 内层重试关闭契约（#321）：resolve 注入的执行配置＝重试关（maxAttempts=1，
     * presence 即跳过框架 builder 的 MODEL_DEFAULTS 缺省注入）＋缺省 inter-signal
     * 超时保留（timeout 取 MODEL_DEFAULTS 同值——除重试维度外不改既有行为，
     * 流时长上限属 #322）。平台层（RetryingModel）独占流层重试。
     */
    @Test
    void given_inner_retry_disabled_options_when_built_then_retry_off_and_default_timeout_kept() {
        io.agentscope.core.model.ExecutionConfig config =
                AgentscopeHarnessAgentFactory.innerRetryDisabledOptions().getExecutionConfig();

        assertThat(config.getMaxAttempts()).isEqualTo(1);
        assertThat(config.getTimeout())
                .isEqualTo(io.agentscope.core.model.ExecutionConfig.MODEL_DEFAULTS.getTimeout());
    }

    /**
     * 总时长上限装配（#322）：同一 streamLayer 单点同时承载重试与上限——零发射挂流
     * 超上限转可重试失败，第二发正常收流；行为级钉「工厂生产装配真实带上限」而非
     * 只测 RetryingModel 孤件。
     */
    @Test
    void given_hung_stream_over_cap_when_stream_layer_then_capped_and_retried() {
        Model raw = mock(Model.class);
        when(raw.stream(any(), any(), any()))
                .thenReturn(Flux.never())
                .thenReturn(Flux.just(ChatResponse.builder()
                        .usage(new ChatUsage(100, 40, 0, 0.5))
                        .build()));
        AgentscopeProperties properties = new AgentscopeProperties();
        properties.setModelStreamRetryInitialBackoff(java.time.Duration.ofMillis(10));
        properties.setModelStreamRetryMaxBackoff(java.time.Duration.ofMillis(50));
        properties.setModelStreamMaxDuration(java.time.Duration.ofMillis(150));

        ChatResponse received = AgentscopeHarnessAgentFactory
                .streamLayer(raw, "deepseek:deepseek-v4-flash", properties)
                .stream(List.of(), null, null)
                .blockLast(java.time.Duration.ofSeconds(5));

        verify(raw, times(2)).stream(any(), any(), any());
        assertThat(received.getUsage().getTotalTokens()).isEqualTo(140);
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

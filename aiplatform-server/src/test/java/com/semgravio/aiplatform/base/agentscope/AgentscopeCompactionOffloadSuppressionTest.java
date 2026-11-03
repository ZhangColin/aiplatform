package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import reactor.core.publisher.Flux;

/**
 * #379 压缩 offload 宿主泄漏关停回归：框架压缩链（ConversationCompactor）在压缩前
 * 把被逐消息 offload 进会话树——{@code SessionTree.flush()} 以<b>纯宿主 NIO</b> 写
 * context/log 两份 jsonl 到 {@code <workspace根>/<用户>/agents/<智能体名>/sessions/}
 * （不走 abstractFilesystem，#107 重映射罩不住这条腿）。项目容器姿态交给框架的根是
 * 容器路径 {@code /workspace}：Windows 宿主解析为 {@code C:\workspace}（issue 现场：
 * {@code C:\workspace\<runId>\agents\platform-agent\sessions\coder-*.jsonl}），部署
 * 环境落后端容器可写层。会话正本在 PG（cat_agent_state），宿主 jsonl 是无读者的
 * 孤儿副本——平台压缩配置单点关停（{@code offloadBeforeCompact(false)}，全形态
 * 一致、不做姿态分叉）。
 *
 * <p>两断言互补（镜像 #363 {@link AgentscopeSkillStageSuppressionTest} 双断言模式）：
 * 装配面（{@link AgentscopeHarnessAgentFactory#compactionConfig} 产出即关停——全宿主
 * 确定性）＋症状面（Local 姿态显式根下真驱超阈值会话触发压缩——正向证据＝摘要消息
 * 在案 {@code __compaction_summary__}（防空洞绿），症状断言＝工作区根下无任何
 * jsonl）。模型经自注册 {@code probe379:} 桩工厂解析（SDK 公开
 * {@code ModelRegistry.registerFactory}——测试不依赖任何模型环境 key）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentscopeCompactionOffloadSuppressionTest {

    /** 桩模型串前缀（注册 {@code probe379:.+} 工厂——主/压缩模型共用同一桩实例）。 */
    private static final String PROBE_MODEL = "probe379:stub-model";

    /** 压缩摘要消息标记（框架 ConversationCompactor 落摘要的 Msg 名——压缩已发生的正向证据）。 */
    private static final String COMPACTION_SUMMARY_NAME = "__compaction_summary__";

    /**
     * 低触发阈值（触发必要条件；配置非零即原样生效，不被框架动态演算改写）。触发
     * ≠落剪：cutoff>0 还须总量超过尾部保留预算（keepTokens 动态缺省 8000）——症状
     * 测试的消息长度与轮数据此定（见其 javadoc）。
     */
    private static final int LOW_TRIGGER_TOKENS = 64;

    @TempDir
    Path workspaceRoot;

    @BeforeAll
    static void registerProbeModelFactory() {
        // 构建期模型解析不触网不依赖环境 key（同 #363 probe 先例）；恒定文本响应——
        // 主循环应答与压缩摘要调用共用（压缩链摘要也走该模型实例）
        ModelRegistry.registerFactory(
                "probe379:.+", (modelId, context) -> new FixedTextModel());
    }

    private static final AgentToolkitSupplier TOOLKITS = (agentKey, workspace, toolSpec) -> new Toolkit();

    private static final AgentSkillRepositorySupplier SKILL_REPOS = (agentKey, workspace) -> List.of();

    private static final AgentSubagentSupplier SUBAGENTS = (agentKey, workspace) -> List.of();

    private static final AgentMiddlewareSupplier NO_MIDDLEWARES = (agentKey, workspace) -> List.of();

    /** 桩模型串（构建期解析即注册工厂直构——无通道语境、不触网）。 */
    private static final EdgeModelSupplier EDGE_MODELS = new EdgeModelSupplier() {
        @Override
        public EdgeModel compactionModel() {
            return new EdgeModel(PROBE_MODEL, null);
        }

        @Override
        public EdgeModel precheckModel() {
            return new EdgeModel(PROBE_MODEL, null);
        }
    };

    /** 恒定长文本响应桩（每次调用同一形状——主循环应答与压缩摘要调用无差别服务）。 */
    private static final class FixedTextModel implements Model {

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .id("resp-379")
                    .content(List.of(TextBlock.builder().text(
                            "这是桩模型的长回答，用于让会话越过压缩触发阈值并让总量超过尾部保留预算，持续累积上下文。".repeat(80)).build()))
                    .usage(new ChatUsage(120, 40, 0, 0.01))
                    .build());
        }

        @Override
        public String getModelName() {
            return "stub-379";
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return false;
        }

        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            return false;
        }

        @Override
        public int getContextWindowSize() {
            return 128_000;
        }
    }

    /** 装配面守护（全宿主确定性）：平台压缩配置装配方法产出即关停压缩前 offload。 */
    @Test
    void given_platform_compaction_config_when_built_then_offload_disabled() {
        AgentscopeProperties properties = new AgentscopeProperties();
        properties.setCompactionTriggerTokens(LOW_TRIGGER_TOKENS);

        CompactionConfig config = AgentscopeHarnessAgentFactory.compactionConfig(
                properties, new EdgeModelSupplier.EdgeModel(PROBE_MODEL, null));

        assertThat(config.isOffloadBeforeCompact())
                .as("平台压缩配置必须关停压缩前会话树 offload（#379 宿主泄漏正源）")
                .isFalse();
    }

    /**
     * 症状面守护：Local 姿态显式根（真实宿主目录——泄漏腿同款纯宿主 NIO，写向即根
     * 本身）真驱十轮超阈值会话——总量须越过尾部保留预算（keepTokens 动态缺省
     * 8000：128K 窗口 − 保留量 × 0.25，截于上限 8000），否则 cutoff=0 框架跳过
     * 压缩（触发≠落剪）。正向证据先行（压缩确已发生——否则本测试空洞绿），症状
     * 断言随后：压缩照常触发与摘要，但工作区根下不得新建会话树 jsonl（泄漏时落
     * {@code <根>/<会话id>/agents/<智能体名>/sessions/} 下 {@code .jsonl} 与
     * {@code .log.jsonl} 两份）。
     */
    @Test
    void given_over_threshold_conversation_when_compacted_then_no_session_tree_files() {
        AgentscopeProperties properties = new AgentscopeProperties();
        properties.setCompactionTriggerTokens(LOW_TRIGGER_TOKENS);
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                stateStore, TOOLKITS, SKILL_REPOS, SUBAGENTS,
                NO_MIDDLEWARES, EDGE_MODELS, properties);
        HarnessAgent agent = factory.obtain("platform-agent-379", "sys", PROBE_MODEL,
                null, new AgentWorkspace.Local(workspaceRoot), "main", null);
        try {
            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId("sess-379").userId("user-379").build();
            for (int turn = 1; turn <= 10; turn++) {
                agent.streamEvents(List.of(userMsg(turn)), ctx).blockLast();
            }
            // 正向证据：压缩确实触发（摘要消息在案——会话正本读回口径同平台恢复
            // 腿：状态库按 userId+sessionId 取 agent_state）——防空洞绿
            AgentState state = stateStore.get("user-379", "sess-379", "agent_state",
                    AgentState.class).orElseThrow(() -> new AssertionError(
                            "会话状态未落库（装配面变化？）——压缩证据无从读起"));
            assertThat(state.getContext())
                    .as("前置：压缩应已触发（低阈值多轮会话）——未触发则症状断言空洞")
                    .anyMatch(msg -> COMPACTION_SUMMARY_NAME.equals(msg.getName()));
            // 症状断言：压缩照常、摘要生效，但根下无会话树 jsonl
            assertThat(jsonlFilesUnder(workspaceRoot))
                    .as("工作区根 %s 下不应新建会话树 jsonl（#379）", workspaceRoot)
                    .isEmpty();
        }
        finally {
            agent.close();
        }
    }

    private static Msg userMsg(int turn) {
        return Msg.builderForRole(MsgRole.USER)
                .content(TextBlock.builder().text(
                        ("第" + turn + "轮：用来越过压缩触发阈值并超过尾部保留预算的会话内容，逐轮累积上下文。").repeat(40))
                        .build())
                .build();
    }

    /** 工作区根下全部 jsonl 文件（泄漏两份＝<code>*.jsonl</code> 与 <code>*.log.jsonl</code>）。 */
    private static List<Path> jsonlFilesUnder(Path root) {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        }
        catch (IOException e) {
            throw new IllegalStateException("工作区根遍历失败: " + root, e);
        }
    }
}

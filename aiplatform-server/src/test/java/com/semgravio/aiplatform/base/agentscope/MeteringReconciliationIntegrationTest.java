package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.semgravio.aiplatform.IntegrationTest;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 计量对账级集成（#324）：脚本化会话（真实 HarnessAgent ReAct 链 + 真计量落库链
 * {@code MeteringLocalAdapter → met_usage_events}，仅最底层模型换脚本替身）多轮
 * 多流的用量<b>按计量事件全量对账</b>——成功轮的每个模型流、失败轮的已耗流全部
 * 入账、按项目（subject）归集逐条可核；幂等键形态随查随验。与单测
 * {@link AgentscopeMeteringReconcileTest}（sink 端逐条）互补：本类在数据库可观察
 * 面对账（对账准绳＝met_usage_events 全量事件）。
 */
@IntegrationTest
class MeteringReconciliationIntegrationTest {

    @Autowired
    private AgentscopeAgentClient agentClient;

    @Autowired
    private AgentscopeProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private io.agentscope.core.state.AgentStateStore stateStore;

    /** 工厂换脚本腿：obtain 返回真实 HarnessAgent（模型＝MeteredModel 包装的脚本）。 */
    @MockitoBean
    private AgentscopeHarnessAgentFactory factory;

    @TempDir
    Path workspaceDir;

    private Path workspaceBackup;

    @BeforeEach
    void isolateWorkspace() {
        workspaceBackup = properties.getWorkspace();
        properties.setWorkspace(workspaceDir);
    }

    @AfterEach
    void restoreAndClean() {
        properties.setWorkspace(workspaceBackup);
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE run_id LIKE 'recon-%'");
        jdbcTemplate.update("DELETE FROM cat_agent_state WHERE user_id = 'recon-user'");
    }

    /**
     * 验收锚点（#324 对账级判据）：脚本化会话三 run——成功多迭代轮（2 流）、失败轮
     * （已耗流照报）、邻项目轮（归集隔离）——按 met_usage_events 全量对账：逐 run
     * 条数与用量逐条核、按 subject 归集不串项目、幂等键形态钉锚。
     */
    @Test
    void given_scripted_multi_run_session_when_reconcile_then_all_streams_in_project_ledger() {
        // run-1（prj-1，同会话第 1 轮）：ReAct 两流——工具迭代 + 收口
        mountAgent(scriptedAgent(new ScriptedModel(List.of(
                List.of(toolCall("echo_note", "t-1", new ChatUsage(100, 40, 0, 0.1))),
                List.of(text("做完了", new ChatUsage(60, 30, 0, 0.05)))))));
        agentClient.converse(command("recon-1", "prj-1"), event -> {
        });

        // run-2（prj-1，同会话第 2 轮）：流中途失败——已耗照报、attempt 不弃账
        ScriptedModel failing = new ScriptedModel(List.of(
                List.of(text("写到一半", new ChatUsage(80, 20, 0, 0.02)))));
        failing.failFromCall(0);
        mountAgent(scriptedAgent(failing));
        assertThatThrownBy(() -> agentClient.converse(command("recon-2", "prj-1"), event -> {
        })).isInstanceOf(IllegalStateException.class);

        // run-3（prj-2，另一项目）：单流——归集隔离对照
        mountAgent(scriptedAgent(new ScriptedModel(List.of(
                List.of(text("另一个项目的活", new ChatUsage(30, 10, 0, 0.01)))))));
        agentClient.converse(command("recon-3", "prj-2", "s-recon-other"), event -> {
        });

        // 对账（逐 run 条数与用量和——成功轮两流合计；失败轮至少已耗流入账，
        // 内核对失败流段的重试次数不钉死（重试流同机制自然入账，逐条值核））
        assertThat(ledgerOf("prj-1", "recon-1")).containsExactly(entry(2, 160L, 70L));
        int recon2Events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE run_id = 'recon-2'",
                Integer.class);
        assertThat(recon2Events).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT input || '/' || output FROM met_usage_events WHERE run_id = 'recon-2'",
                String.class)).containsOnly("80/20");
        assertThat(ledgerOf("prj-2", "recon-3")).containsExactly(entry(1, 30L, 10L));

        // 对账（全量条数）：3 run 全量 = 2 + 失败轮（已耗 + 可能的内核重试流）+ 1
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE run_id LIKE 'recon-%'",
                Integer.class)).isEqualTo(3 + recon2Events);

        // 对账（归集隔离）：prj-1 恰两 run、prj-2 恰一 run，无串项
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT run_id) FROM met_usage_events WHERE subject = 'prj-1'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE subject = 'prj-2' AND run_id != 'recon-3'",
                Integer.class)).isZero();

        // 幂等键形态：agent-usage-{runId}-{seq}，逐流唯一
        assertThat(jdbcTemplate.queryForList(
                "SELECT event_id FROM met_usage_events WHERE run_id = 'recon-1' ORDER BY event_id",
                String.class)).containsExactly("agent-usage-recon-1-1", "agent-usage-recon-1-2");

        // dims 归集（业务维度随账入行）
        assertThat(jdbcTemplate.queryForList(
                "SELECT dims ->> 'agentKind' FROM met_usage_events WHERE subject = 'prj-1'",
                String.class)).containsOnly("coder");
    }

    /** 项目账视图：(run 数, input 合计, output 合计) 逐 run 分组。 */
    private List<Object[]> ledgerOf(String subject, String runId) {
        return jdbcTemplate.query(
                "SELECT COUNT(*), SUM(input), SUM(output) FROM met_usage_events"
                        + " WHERE subject = ? AND run_id = ?",
                (rs, i) -> entry(rs.getInt(1), rs.getLong(2), rs.getLong(3)),
                subject, runId);
    }

    private static Object[] entry(long runs, long input, long output) {
        return new Object[] {runs, input, output};
    }

    private AgentCommand command(String runSuffix, String subject) {
        return command(runSuffix, subject, "s-reconcile");
    }

    private AgentCommand command(String runSuffix, String subject, String sessionId) {
        return new AgentCommand(runSuffix, "干活", null,
                "deepseek:deepseek-chat", sessionId, "recon-user",
                new UsageContext(subject, Map.of("agentKind", "coder")), null, Map.of());
    }

    private void mountAgent(HarnessAgent agent) {
        when(factory.obtain(any(), any(), any(), any(), any(), any(), any())).thenReturn(agent);
    }

    /** 真实 HarnessAgent（装配对齐平台工厂：MeteredModel 包装 + 平台压缩配置 + 记忆关闭）。 */
    private HarnessAgent scriptedAgent(Model model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoNoteTool());
        return HarnessAgent.builder()
                .name(properties.getAgentName())
                .sysPrompt(properties.getDefaultSystemPrompt())
                .model(MeteredModel.wrap(model, "deepseek:deepseek-chat"))
                .stateStore(stateStore)
                .toolkit(toolkit)
                .workspace(workspaceDir)
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableDefaultWorkspaceSkills()
                // 压缩保持框架默认开（压缩模型未配＝回落主模型，平台工厂同语义；
                // 不走 compactionConfig(properties)——那会 resolve 配置档真模型、
                // 需通道 key（volcengine 通道行，测试库种子注入），脚本腿起不来）
                .compaction(io.agentscope.harness.agent.memory.compaction.CompactionConfig
                        .builder().build())
                .build();
    }

    /** 最简工具（脚本模型的 tool_use 指名调用）。 */
    static final class EchoNoteTool {

        @Tool(name = "echo_note", description = "回执一条笔记")
        public String run(@ToolParam(name = "text") String text) {
            return "ok: " + text;
        }
    }

    /**
     * 脚本化模型：逐调用吐脚本响应（boundedElastic 发信号＝真实 HTTP 异步边界），
     * {@code failFromCall(k)} 让第 k 次起的调用在响应后转错误（失败流段形态）。
     */
    static final class ScriptedModel implements Model {

        private final List<List<ChatResponse>> script;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile int failFrom = Integer.MAX_VALUE;

        ScriptedModel(List<List<ChatResponse>> script) {
            this.script = script;
        }

        void failFromCall(int callNo) {
            this.failFrom = callNo;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                GenerateOptions options) {
            int callNo = calls.getAndIncrement();
            List<ChatResponse> responses = script.get(Math.min(callNo, script.size() - 1));
            Flux<ChatResponse> flow = Flux.fromIterable(responses);
            if (callNo >= failFrom) {
                flow = Flux.concat(flow, Flux.error(new RuntimeException("mid-stream boom")));
            }
            return flow.subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        public String getModelName() {
            return "scripted";
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

    private static ChatResponse text(String text, ChatUsage usage) {
        return ChatResponse.builder()
                .id("resp-" + text.hashCode())
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(usage)
                .build();
    }

    private static ChatResponse toolCall(String toolName, String callId, ChatUsage usage) {
        return ChatResponse.builder()
                .id("resp-" + callId)
                .content(List.of(ToolUseBlock.builder()
                        .name(toolName).id(callId)
                        .input(Map.of("text", "一条")).build()))
                .usage(usage)
                .build();
    }
}

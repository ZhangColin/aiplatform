package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 真实对话冒烟（#44 验收 + #45 流桥）：平台进程内 HarnessAgent 走主通道真模型
 * （volcengine:方舟 DeepSeek，ADR-0031/#369 退场确认后无 DEEPSEEK_API_KEY 环境腿）
 * 跑通一轮对话——流事件可观测（run-start → text 增量连续 →
 * run-finish，runId 锚定、增量拼接 = 汇聚文本），该轮产生恰一条 UsageEvent 落库
 * （subject/dims 归属）。
 *
 * <p>通道件从测试库供应商行现读（volcengine 种子行——与业务侧档位解析同源，
 * key 经部署环境注入种子）：key 空即整类跳过（Assumption，不失败）。流式/计量/
 * 幂等键行为在 {@code AgentscopeAgentClientTest}（mock 事件流）已覆盖，本类只验
 * 真内核接线。workspace 经临时目录隔离；AgentState 落 PG（#48），冒烟槽位
 * 每轮清理。</p>
 */
@IntegrationTest
class AgentscopeConverseSmokeTest {

    @TempDir
    static Path agentscopeHome;

    @Autowired
    private AgentscopeAgentClient agentClient;

    @Autowired
    private AgentscopeProperties chatAgentProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Path workspaceBackup;

    @BeforeAll
    static void requireVolcengineChannel() {
        // 通道件正本在测试库（种子 key 与 dev 同源 VOLCENGINE_API_KEY 注入）；
        // @BeforeAll 静态无 jdbc，以同源 env 判可跑性，行内容测试内现读
        String key = System.getenv("VOLCENGINE_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "VOLCENGINE_API_KEY 未设置（volcengine 通道种子无 key），跳过真实对话冒烟");
    }

    @BeforeEach
    void isolateWorkspace() {
        workspaceBackup = chatAgentProperties.getWorkspace();
        chatAgentProperties.setWorkspace(agentscopeHome.resolve("workspace"));
    }

    @AfterEach
    void restoreAndClean() {
        chatAgentProperties.setWorkspace(workspaceBackup);
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE run_id LIKE 'smoke-chat-%'");
        // #48 会话状态落 PG：冒烟槽位清理（无 workspaceId 走本地工作区，状态仍在库）
        jdbcTemplate.update("DELETE FROM cat_agent_state WHERE user_id = 'smoke-user'");
    }

    @Test
    void given_real_volcengine_when_converse_then_streamed_frames_and_usage_recorded()
            throws InterruptedException {
        String runId = "smoke-chat-" + UUID.randomUUID();
        List<AgentEvent> frames = new ArrayList<>();
        // 通道件从测试库供应商行现读（主链路装配的忠实复刻：档位解析查库构造同款）
        var channelRow = jdbcTemplate.queryForMap(
                "SELECT base_url, api_key FROM prj_model_providers WHERE provider_key = 'volcengine'");
        ModelChannel channel = new ModelChannel(
                (String) channelRow.get("base_url"), (String) channelRow.get("api_key"), false);
        VolcengineModelRegistrar.register();

        AgentReply reply = agentClient.converse(
                new AgentCommand(runId, "用一句话介绍你自己", null,
                        "volcengine:deepseek-v4-flash-ga-260731", channel,
                        "smoke-session", "smoke-user",
                        new UsageContext("smoke-prj", Map.of("scene", "agentscope-smoke")),
                        null, Map.of(), null, null, null, null, false, false, null, null),
                frames::add);

        // 事件序与锚定（#45 事件桥验收：流事件经端口可观测、文本增量连续）
        assertThat(frames.get(0).type()).isEqualTo(AgentEventTypes.RUN_START);
        assertThat(frames.get(0).payload()).containsEntry("runId", runId);
        List<String> deltas = textDeltas(frames);
        assertThat(deltas).isNotEmpty();
        assertThat(String.join("", deltas)).isEqualTo(reply.text());
        assertThat(frames.get(frames.size() - 1).type()).isEqualTo(AgentEventTypes.RUN_FINISH);
        assertThat(frames.get(frames.size() - 1).payload()).containsEntry("runId", runId);
        assertThat(reply.runId()).isEqualTo(runId);
        assertThat(reply.text()).isNotBlank();

        // 计量（#109 模型边界计量 / #324 对账级）：单轮纯文本对话 = 一次主循环模型
        // 调用 → 恰一条 UsageEvent（幂等键 event_id = agent-usage-{runId}-{seq}，按
        // run_id 寻址不看键形状；多迭代/失败流的全量对账见
        // AgentscopeMeteringReconcileTest / MeteringReconciliationIntegrationTest）。
        // 落库收口在模型流终止钩子（doFinally）——与 converse 返回（订阅 blockLast
        // 完）间有毫秒级异步窗口（#369 冒烟实测：查询可比落库早 ~10ms），轮询等
        // 事件可见（最终一致，读面都在事后——生产无重叠读者）
        Map<String, Object> row = awaitUsageEvent(runId);
        assertThat(row.get("subject")).isEqualTo("smoke-prj");
        assertThat(row.get("provider")).isEqualTo("volcengine");
        assertThat((Long) row.get("input")).isPositive();
        assertThat((Long) row.get("output")).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE run_id = ?", Integer.class, runId))
                .isEqualTo(1);
    }

    /** 轮询等本轮 UsageEvent 落库可见（终止钩子毫秒级异步，最多等 5s——超时按空集失败）。 */
    private Map<String, Object> awaitUsageEvent(String runId) throws InterruptedException {
        String sql = "SELECT subject, provider, input, output "
                + "FROM met_usage_events WHERE run_id = ?";
        for (int i = 0; i < 50; i++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, runId);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(100);
        }
        return jdbcTemplate.queryForMap(sql, runId);
    }

    private static List<String> textDeltas(List<AgentEvent> frames) {
        List<String> deltas = new ArrayList<>();
        for (AgentEvent frame : frames) {
            if ("text".equals(frame.type())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) frame.payload().get("data");
                deltas.add((String) data.get("delta"));
            }
        }
        return deltas;
    }
}

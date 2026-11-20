package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.AgentscopeProperties;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.base.agentscope.UsageContext;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * 单通道故障活体演练（#560/T6，spec #552 总验收①②「live 一门真通道假 key」腿）：
 * <b>库面注入故障</b>——测试库 flash 档（平台现役快档）主候选指向 OpenAI 兼容协议
 * 通道行（同 deepseek 官方端点×<b>假 key</b>——生产事故 2026-11-19 同形：key 供应商
 * 侧被删、真服务器回真 401），备候选＝deepseek 官方行×真 key（env DEEPSEEK_API_KEY
 * 注入落库——apiKey 库为唯一正本的生产口径）。经<b>真实解析链</b>
 * （{@code ModelTierAppService#resolveQueue}：档位行 → 候选表 → 通道行）＋
 * <b>平台命令面</b>（{@link AgentscopeAgentClient#converse}）跑一轮对话，验收：
 * 单通道故障下命令面持续可用（自动切备、生成成功、无人工干预）＋failover 事实
 * 与命中通道双视角可见（切换留痕 {@code prj_model_tier_switch_traces}、计量
 * {@code met_usage_events} 按账号 provider/按供应商 vendor、[model-failover] 日志）。
 *
 * <p><b>为什么主候选走 {@code openai:} 前缀</b>：候选模型串前缀即协议路由
 * （ADR-0031），SDK 内置 provider 前缀封闭（deepseek/openai/kimi/glm/minimax）
 * ——通道行键须取受支持前缀。{@code openai:}＝OpenAI 兼容协议路由，通道行
 * {@code base_url} 注入指向 deepseek 官方端点（与火山方舟事故同形：OpenAI 兼容
 * 工厂×真端点×失效 key）；vendor 列填 deepseek（归一供应商维度，ADR-0053 ②
 * ——按模型统计与单价匹配按 vendor 归带，跨账号行合并同供应商）。</p>
 *
 * <p><b>注入纪律（共享库面）</b>：只动三处且全部还原——①openai 演练通道行
 * （自建自清，前置断言不存在残留）；②deepseek 行 api_key（原值先存、teardown
 * 还原——真 key 仅演练期间落库）；③flash 候选两行（p1 改指 openai、p2 新增
 * 备选；teardown 反序还原）。注入顺序先备后主（p2 先入队），任一时刻 flash
 * 队列不缺可用候选。前置断言 flash 现役形态（单候选 deepseek）——形态漂移
 * 即 fail-fast 留指引，不在未知形态上盲动。</p>
 *
 * <p>env 未配置即跳过（Assumption）；{@code @Tag("live")}——fast lane 不跑
 * （共享库面注入只宜显式 live 演练），跑法：
 * {@code set -a; source .env.local; set +a && mvn test -P live -Dtest=ModelFailoverDrillLiveTest}。</p>
 */
@IntegrationTest
@Tag("live")
class ModelFailoverDrillLiveTest {

    /** 演练注入的 OpenAI 兼容通道行键（＝模型串前缀，见类 javadoc）。 */
    private static final String DRILL_PROVIDER_KEY = "openai";
    /** 演练档位＝平台现役快档（三座缺省/执行体档，demo-stage SOP 正本）。 */
    private static final String DRILL_TIER_KEY = "flash";
    /** 假 key：形状刻意非真值（真 key＝sk-+32hex），供应商侧必回真 401。 */
    private static final String FAKE_KEY = "sk-drill-fake-key-401-fault-injection";

    @TempDir
    static Path agentscopeHome;

    @Autowired
    private AgentscopeAgentClient agentClient;

    @Autowired
    private AgentscopeProperties chatAgentProperties;

    @Autowired
    private ModelTierAppService modelTiers;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Path workspaceBackup;
    /** 注入前原值（teardown 还原腿）：deepseek 行现 key——可空值（V79 NULLIF 可产 NULL），
     * 还原须区分「未捕获」与「捕获到 NULL」，独立标志位判捕获（防真 key 泄漏进共享库）。 */
    private String originalDeepseekKey;
    private boolean deepseekKeyCaptured;
    /** 注入前原值：flash p1 通道指向。 */
    private String originalFlashPrimaryProvider;

    @BeforeAll
    static void requireRealKey() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "DEEPSEEK_API_KEY 未设置（备候选需真 key），跳过 failover 活体演练");
    }

    @BeforeEach
    void injectFaultIntoPrimaryCandidate() {
        workspaceBackup = chatAgentProperties.getWorkspace();
        chatAgentProperties.setWorkspace(agentscopeHome.resolve("workspace"));
        injectQueueFault();
    }

    @AfterEach
    void restoreInjectedState() {
        if (workspaceBackup != null) {
            chatAgentProperties.setWorkspace(workspaceBackup);
        }
        restoreQueueFault();
        // 演练产物清理（留痕表/计量事件/会话状态——按本演练自有前缀寻址）
        jdbcTemplate.update("DELETE FROM prj_model_tier_switch_traces WHERE run_id LIKE 'smoke-fo-%'");
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE run_id LIKE 'smoke-fo-%'");
        jdbcTemplate.update("DELETE FROM cat_agent_state WHERE user_id = 'smoke-fo-user'");
    }

    @Test
    @Timeout(180)
    void given_primary_candidate_fake_key_when_converse_then_failover_to_backup_succeeds()
            throws InterruptedException {
        String runId = "smoke-fo-" + UUID.randomUUID();
        // 真实解析链读注入后的队列（运营面改候选后的生效路径同款——动态查库）
        ModelCandidateQueue queue = modelTiers.resolveQueue(DRILL_TIER_KEY);
        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.first().providerKey()).isEqualTo(DRILL_PROVIDER_KEY);

        List<AgentEvent> frames = new ArrayList<>();
        AgentReply reply = agentClient.converse(
                new AgentCommand(runId, "只回复两个字：成功", null,
                        queue.modelString(), queue.channel(),
                        "smoke-fo-session", "smoke-fo-user",
                        new UsageContext("smoke-fo-prj", Map.of("scene", "failover-drill")),
                        null, Map.of(), null, null, null, null, false, false, null, null,
                        queue),
                frames::add);

        // 总验收①：命令面持续可用——单通道故障（真 401）下单次调用自动切备、生成
        // 成功、无人工干预；用户面流形态完好（run-start → text 增量 → run-finish）
        assertThat(reply.runId()).isEqualTo(runId);
        assertThat(reply.text()).contains("成功");
        assertThat(frames.get(0).type()).isEqualTo(AgentEventTypes.RUN_START);
        assertThat(frames.get(0).payload()).containsEntry("runId", runId);
        assertThat(frames.get(frames.size() - 1).type()).isEqualTo(AgentEventTypes.RUN_FINISH);
        assertThat(frames.get(frames.size() - 1).payload()).containsEntry("runId", runId);
        List<String> deltas = textDeltas(frames);
        assertThat(deltas).isNotEmpty();
        assertThat(String.join("", deltas)).isEqualTo(reply.text());

        // 总验收②a：切换留痕（append-only）——真 401 触发、主备指向如实、run 上下文锚定
        // （恰一条：主候选 401 轮一次切换；compaction 边缘档同队列走同一 FailoverModel，
        // 短轮不触发压缩——多痕即异常形态，显式计数防 queryForMap 静默取首行）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_model_tier_switch_traces WHERE run_id = ?",
                Integer.class, runId)).isEqualTo(1);
        Map<String, Object> trace = jdbcTemplate.queryForMap(
                "SELECT tier_key, from_provider_key, to_provider_key, trigger_error, run_id "
                        + "FROM prj_model_tier_switch_traces WHERE run_id = ?", runId);
        assertThat(trace.get("tier_key")).isEqualTo(DRILL_TIER_KEY);
        assertThat(trace.get("from_provider_key")).isEqualTo(DRILL_PROVIDER_KEY);
        assertThat(trace.get("to_provider_key")).isEqualTo("deepseek");
        assertThat((String) trace.get("trigger_error")).contains("401");

        // 总验收②b：计量归因（命中通道双视角）——按账号 provider＝备通道行键、
        // 按供应商 vendor＝deepseek（跨账号行合并键）；主候选 401 轮零 usage 不落
        // 事件（MeteringScope 零用量跳过），恰一条＝备候选产出
        Map<String, Object> usage = awaitUsageEvent(runId);
        assertThat(usage.get("provider")).isEqualTo("deepseek");
        assertThat(usage.get("vendor")).isEqualTo("deepseek");
        assertThat((Long) usage.get("input")).isPositive();
        assertThat((Long) usage.get("output")).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE run_id = ?", Integer.class, runId))
                .isEqualTo(1);
    }

    /**
     * 库面故障注入（顺序＝先备后主）：①openai 演练通道行（假 key、vendor=deepseek）
     * → ②flash p2 备候选（deepseek 真通道行）→ ③deepseek 行 key 换真值（备候选
     * 真_key 腿——apiKey 库为唯一正本）→ ④flash p1 改指 openai（主候选注入假 key）。
     */
    private void injectQueueFault() {
        String realKey = System.getenv("DEEPSEEK_API_KEY");
        // 前置断言（fail-fast 留指引，不在未知形态上盲动）：openai 行无残留（上次
        // 演练硬中断的残迹——手动清 prj_model_tier_candidates p 行 + 本行后重跑）；
        // flash 现役形态＝单候选 deepseek（V79 种子态）
        Integer openaiRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_model_providers WHERE provider_key = ?",
                Integer.class, DRILL_PROVIDER_KEY);
        assertThat(openaiRows).as("openai 通道行已存在（疑似上次演练残留）——先手动清理再跑").isZero();
        Integer flashCandidateRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_model_tier_candidates WHERE tier_key = ?",
                Integer.class, DRILL_TIER_KEY);
        assertThat(flashCandidateRows).as("flash 候选形态非单候选（非 V79 种子态）——确认库态再跑").isEqualTo(1);

        originalDeepseekKey = jdbcTemplate.queryForObject(
                "SELECT api_key FROM prj_model_providers WHERE provider_key = 'deepseek'", String.class);
        deepseekKeyCaptured = true;
        originalFlashPrimaryProvider = jdbcTemplate.queryForObject(
                "SELECT provider_key FROM prj_model_tier_candidates WHERE tier_key = ? AND priority = 1",
                String.class, DRILL_TIER_KEY);
        // 种子事实单源现读（不复制 V79 字面值）：主候选模型名沿 flash p1 行、假通道行
        // 端点沿 deepseek 行——种子漂移时前置断言先红，此处不另背一份常量
        String tierModelName = jdbcTemplate.queryForObject(
                "SELECT model_name FROM prj_model_tier_candidates WHERE tier_key = ? AND priority = 1",
                String.class, DRILL_TIER_KEY);
        String deepseekBaseUrl = jdbcTemplate.queryForObject(
                "SELECT base_url FROM prj_model_providers WHERE provider_key = 'deepseek'", String.class);

        jdbcTemplate.update(
                "INSERT INTO prj_model_providers (provider_key, base_url, api_key, enabled, vendor) "
                        + "VALUES (?, ?, ?, TRUE, 'deepseek')",
                DRILL_PROVIDER_KEY, deepseekBaseUrl, FAKE_KEY);
        jdbcTemplate.update(
                "INSERT INTO prj_model_tier_candidates (tier_key, priority, provider_key, model_name, enabled) "
                        + "VALUES (?, 2, 'deepseek', ?, TRUE)",
                DRILL_TIER_KEY, tierModelName);
        jdbcTemplate.update(
                "UPDATE prj_model_providers SET api_key = ? WHERE provider_key = 'deepseek'", realKey);
        jdbcTemplate.update(
                "UPDATE prj_model_tier_candidates SET provider_key = ? WHERE tier_key = ? AND priority = 1",
                DRILL_PROVIDER_KEY, DRILL_TIER_KEY);
    }

    /** 注入还原（反序：先还 p1 指向〔FK 引用腿〕再删 openai 行；key 原值回写）。 */
    private void restoreQueueFault() {
        if (originalFlashPrimaryProvider != null) {
            jdbcTemplate.update(
                    "UPDATE prj_model_tier_candidates SET provider_key = ? "
                            + "WHERE tier_key = ? AND priority = 1",
                    originalFlashPrimaryProvider, DRILL_TIER_KEY);
        }
        jdbcTemplate.update(
                "DELETE FROM prj_model_tier_candidates WHERE tier_key = ? AND priority = 2",
                DRILL_TIER_KEY);
        jdbcTemplate.update(
                "DELETE FROM prj_model_providers WHERE provider_key = ?", DRILL_PROVIDER_KEY);
        if (deepseekKeyCaptured) {
            // 捕获值可空（V79 NULLIF 形）也须回写——空值跳过会把真 key 永久留在共享库
            jdbcTemplate.update(
                    "UPDATE prj_model_providers SET api_key = ? WHERE provider_key = 'deepseek'",
                    originalDeepseekKey);
        }
    }

    /** 轮询等本轮 UsageEvent 落库可见（终止钩子毫秒级异步，最多等 5s——超时按空集失败）。 */
    private Map<String, Object> awaitUsageEvent(String runId) throws InterruptedException {
        String sql = "SELECT subject, provider, vendor, input, output "
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

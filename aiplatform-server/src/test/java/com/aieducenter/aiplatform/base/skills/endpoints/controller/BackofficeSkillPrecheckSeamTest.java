package com.aieducenter.aiplatform.base.skills.endpoints.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.skills.application.SkillsProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 指派双头预检端点（#255，#254 spec）在 {@code #152} seam 上全绿：真过滤链
 * （签名闸）＋真应用服务＋真协议端口适配器（business 侧生效值解析单点真链路）
 * ＋aiplatform_test 真库；判定引擎按 classify 测试先例以 {@code @MockitoBean}
 * 桩替内核客户端（技能域无真实模型调用面——预检回执全部经桩注入）。
 *
 * <p>#255 八面钉死：</p>
 * <ul>
 * <li><b>重叠场景</b>：判定结果（桩注入结构化 JSON）透出为提示列表——每条含
 * skills/counterpart/overlap/resolution 四件（含消解方向）；</li>
 * <li><b>无重叠</b>：空提示＋executed=true 成功态；</li>
 * <li><b>失败/超时降级</b>：桩抛错/返回不可解析输出 → executed=false「未执行」
 * （与「无重叠」可区分）、hints 恒空，且<b>不拦后续指派</b>（PUT 照常落地）；</li>
 * <li><b>命令断言</b>（ArgumentCaptor 逐字段）：专用会话前缀 skillcheck-、专用
 * flash 档配置键（代码保证缺省）、空 sink（喂事件无事发生）、短超时 15s、专用
 * 计量标记 agentKind=skillcheck＋平台级归属桶、不触工作区、无配置语境；</li>
 * <li><b>对照材料组装</b>：生效 systemPrompt 库值覆盖优先（造一条 prj_agent_configs
 * 覆盖行验证——提示词含覆盖文本、不含枚举默认正文）＋该槽位已指派启用技能
 * ＋候选集（description＋正文全文入 prompt）；subagent 无协议面如实标注、
 * 机制同一不特判；</li>
 * <li><b>只读</b>：预检调用后槽位指派表零变更；</li>
 * <li><b>负例族</b>：未知槽位 404 SKL_010、内置柄 400 SKL_011、未寻址 TSID
 * 404 SKL_001（与 PUT 同源）——且零判定调用；</li>
 * <li><b>鉴权</b>：非签名请求被拒（401）。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeSkillPrecheckSeamTest {

    /** 信封数字业务码：SKL 域码 7 × 1000＋序号。 */
    private static final int SKILL_NOT_FOUND_CODE = 7001;
    private static final int SKILL_SLOT_NOT_FOUND_CODE = 7010;
    private static final int SKILL_BUILTIN_NOT_ASSIGNABLE_CODE = 7011;

    /** 操作者透传头样例（指派写口落痕用——预检本身只读不留痕）。 */
    private static final String OPERATOR_ID = "700300";
    private static final String OPERATOR_NAME = "运营·预检验证";

    /** 生效协议覆盖文本（库值覆盖优先断言锚——枚举默认正文绝无此句）。 */
    private static final String PROTOCOL_OVERRIDE =
            "测试覆盖协议：收到变更先写失败测试再最小实现（预检库值覆盖验证用）。";

    /** 枚举默认 EXECUTOR 协议正文的特征句（覆盖生效时 prompt 必不含）。 */
    private static final String EXECUTOR_DEFAULT_MARKER = "0.0.0.0:8081";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SkillsProperties properties;

    /** 判定引擎桩（classify 测试先例）：按会话前缀脚本化分流。 */
    @MockitoBean
    private AgentscopeAgentClient agentClient;

    /** 已种植库条目 id（teardown 精确清理）。 */
    private final List<Long> plantedSkillIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM skl_slot_assignments");
        for (Long id : plantedSkillIds) {
            jdbcTemplate.update("DELETE FROM skl_skills WHERE id = ?", id);
        }
        plantedSkillIds.clear();
        // 覆盖配置行只清本类写入的两键（dev 配置面不动——测试库本就独立）
        jdbcTemplate.update("DELETE FROM prj_agent_configs WHERE agent_key IN ('main', 'executor')");
    }

    // ---------- 重叠场景：判定结果透出（含消解方向）＋命令断言逐字段 ----------

    @Test
    void given_overlapping_candidate_when_signed_precheck_then_hints_surface_with_resolution()
            throws Exception {
        long assignedId = plantSkill(76_100_001L, "test-first",
                "测试先行纪律包", "测试先行纪律正文：先写失败测试，再最小实现，最后重构。");
        long candidateId = plantSkill(76_100_002L, "tdd-strict",
                "严格 TDD 包", "严格测试驱动正文：红绿重构循环——绝不先写实现。");
        assign("executor", assignedId);
        givenPrecheckReply("""
                {"overlaps":[{"skills":["tdd-strict"],"counterpart":"test-first",\
                "overlap":"两者都是测试先行的实施纪律","resolution":"二选一保留其一，或收窄其一的适用范围"}]}
                """);

        signedPrecheck("executor", Long.toString(candidateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.executed").value(true))
                .andExpect(jsonPath("$.data.hints", hasSize(1)))
                .andExpect(jsonPath("$.data.hints[0].skills[0]").value("tdd-strict"))
                .andExpect(jsonPath("$.data.hints[0].counterpart").value("test-first"))
                .andExpect(jsonPath("$.data.hints[0].overlap").value("两者都是测试先行的实施纪律"))
                .andExpect(jsonPath("$.data.hints[0].resolution").value(
                        "二选一保留其一，或收窄其一的适用范围"));

        // 命令断言（ArgumentCaptor 逐字段，classify 先例形制）
        AgentCommand command = capturedCommand();
        assertThat(command.sessionId()).startsWith("skillcheck-");
        assertThat(command.modelString())
                .isEqualTo(properties.getPrecheckModel())
                .isEqualTo("deepseek:deepseek-v4-flash"); // 缺省即 flash 档（代码保证）
        assertThat(command.timeout()).isEqualTo(java.time.Duration.ofSeconds(15));
        assertThat(command.usageContext().subject()).isEqualTo("skills");
        assertThat(command.usageContext().dims())
                .containsEntry("agentKind", "skillcheck");
        assertThat(command.workspaceId()).isNull(); // 不触任何工作区
        assertThat(command.agentKey()).isNull(); // 一次性判定无配置语境
        assertThat(command.toolSpec()).isNull();
        assertThat(command.systemPrompt()).isNotBlank(); // 判定协议在位
        assertThat(command.prompt()).isNotBlank();
        // 空 sink：喂一个事件无事发生（结构性 no-op——预检零事件外漏）
        capturedSink().accept(new AgentEvent(AgentEventTypes.RUN_START, Map.of()));
    }

    // ---------- 无重叠：空提示＋成功态 ----------

    @Test
    void given_no_overlap_when_signed_precheck_then_empty_hints_with_executed() throws Exception {
        plantSkill(76_100_011L, "code-review", "双轴并行审查包", "规范轴与需求轴互为对照审查。");
        givenPrecheckReply("{\"overlaps\":[]}");

        signedPrecheck("executor", "76100011")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executed").value(true))
                .andExpect(jsonPath("$.data.hints", hasSize(0)));

        verify(agentClient, times(1)).converse(any(), any());
    }

    // ---------- 失败/超时/不可解析：降级「未执行」＋非阻断 ----------

    @Test
    void given_judge_failure_when_signed_precheck_then_not_executed_and_assignment_unblocked()
            throws Exception {
        long candidateId = plantSkill(76_100_021L, "planning", "计划管理包", "计划管理正文。");
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("skillcheck-")) {
                throw new IllegalStateException("预检超时");
            }
            return new AgentReply(command.runId(), "不该到");
        });

        signedPrecheck("executor", Long.toString(candidateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executed").value(false))
                .andExpect(jsonPath("$.data.hints", hasSize(0)));

        // 非阻断：预检失败后指派照常可完成（提示不是门）
        signedPutAssignments("executor", Long.toString(candidateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skills", hasSize(1)));
        Integer assignmentRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skl_slot_assignments WHERE slot = 'executor'",
                Integer.class);
        assertThat(assignmentRows).isEqualTo(1);
    }

    @Test
    void given_unparsable_output_when_signed_precheck_then_not_executed() throws Exception {
        plantSkill(76_100_031L, "debugging", "系统化调试包", "先复现、再隔离、后修复。");
        givenPrecheckReply("抱歉我无法以结构化形式回答这个问题");

        signedPrecheck("executor", "76100031")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executed").value(false))
                .andExpect(jsonPath("$.data.hints", hasSize(0)));
    }

    // ---------- 对照材料组装：库值覆盖优先＋已指派＋候选集（description＋正文） ----------

    @Test
    void given_protocol_override_when_signed_precheck_then_prompt_uses_effective_protocol()
            throws Exception {
        long assignedId = plantSkill(76_100_041L, "test-first",
                "测试先行纪律包", "已指派技能正文标记：测试先行纪律。");
        long candidateId = plantSkill(76_100_042L, "tdd-strict",
                "严格 TDD 包", "候选技能正文标记：红绿重构循环。");
        assign("executor", assignedId);
        // 造一条覆盖配置：生效 systemPrompt 库值优先（缺省回落被压住）
        overrideExecutorProtocol();
        givenPrecheckReply("{\"overlaps\":[]}");

        signedPrecheck("executor", Long.toString(candidateId)).andExpect(status().isOk());

        String prompt = capturedCommand().prompt();
        // 生效工作协议＝覆盖值（含）、非枚举默认（不含其特征句）
        assertThat(prompt).contains(PROTOCOL_OVERRIDE);
        assertThat(prompt).doesNotContain(EXECUTOR_DEFAULT_MARKER);
        // 已指派技能与候选集：description＋正文全文入 prompt
        assertThat(prompt).contains("test-first").contains("测试先行纪律包")
                .contains("已指派技能正文标记");
        assertThat(prompt).contains("tdd-strict").contains("严格 TDD 包")
                .contains("候选技能正文标记");
        // 对照面分节呈现
        assertThat(prompt).contains("【该槽位生效工作协议】");
        assertThat(prompt).contains("【该槽位当前已指派的启用技能】");
        assertThat(prompt).contains("【候选指派集（本次拟指派）】");
    }

    @Test
    void given_subagent_slot_when_signed_precheck_then_no_protocol_face_same_mechanism()
            throws Exception {
        long candidateId = plantSkill(76_100_051L, "self-test-aids", "自测辅助包", "自测辅助正文。");
        givenPrecheckReply("{\"overlaps\":[]}");

        // subagent 无运营配置正本：无协议面如实标注、机制同一不特判
        signedPrecheck("subagent", Long.toString(candidateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executed").value(true));

        String prompt = capturedCommand().prompt();
        assertThat(prompt).contains("【槽位】subagent");
        assertThat(prompt).contains("（该槽位无内建工作协议）");
        verify(agentClient, times(1)).converse(any(), any());
    }

    @Test
    void given_multi_candidates_on_main_when_signed_precheck_then_intra_set_compared_with_default_protocol()
            throws Exception {
        long tddId = plantSkill(76_100_081L, "tdd-strict", "严格 TDD 包", "集内候选正文：红绿重构。");
        long testFirstId = plantSkill(76_100_082L, "test-first", "测试先行纪律包", "集内候选正文：先写失败测试。");
        // 集内重叠（两候选互为 counterpart）经桩注入透出
        givenPrecheckReply("""
                {"overlaps":[{"skills":["tdd-strict"],"counterpart":"test-first",\
                "overlap":"集内两技能同为测试先行实施纪律","resolution":"二选一保留其一"}]}
                """);

        signedPrecheck("main", Long.toString(tddId), Long.toString(testFirstId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executed").value(true))
                .andExpect(jsonPath("$.data.hints", hasSize(1)))
                .andExpect(jsonPath("$.data.hints[0].skills[0]").value("tdd-strict"))
                .andExpect(jsonPath("$.data.hints[0].counterpart").value("test-first"));

        // 候选集内互相对照：两候选同入【候选指派集】（description＋正文）
        String prompt = capturedCommand().prompt();
        assertThat(prompt).contains("【候选指派集（本次拟指派）】");
        assertThat(prompt).contains("tdd-strict").contains("集内候选正文：红绿重构。");
        assertThat(prompt).contains("test-first").contains("集内候选正文：先写失败测试。");
        // main 槽生效协议面＝无覆盖行回落枚举默认（与 executor 覆盖腿互补钉死）
        assertThat(prompt).contains("【槽位】main");
        assertThat(prompt).contains("你是平台的主智能体");
    }

    // ---------- 只读：预检后槽位指派表零变更 ----------

    @Test
    void given_existing_assignment_when_signed_precheck_then_assignment_table_unchanged()
            throws Exception {
        long assignedId = plantSkill(76_100_061L, "test-first", "测试先行纪律包", "正文。");
        long candidateId = plantSkill(76_100_062L, "tdd-strict", "严格 TDD 包", "正文。");
        assign("executor", assignedId);
        Integer before = assignmentRowCount();
        givenPrecheckReply("{\"overlaps\":[]}");

        signedPrecheck("executor", Long.toString(candidateId)).andExpect(status().isOk());

        assertThat(assignmentRowCount()).isEqualTo(before);
    }

    // ---------- 负例族：与 PUT 同源＋零判定调用 ----------

    @Test
    void given_bad_slot_builtin_or_unknown_id_when_signed_precheck_then_error_family_no_call()
            throws Exception {
        // 未知槽位：404 SKL_010
        signedPrecheck("reviewer", "76100071")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_SLOT_NOT_FOUND_CODE));
        // 内置柄：400 SKL_011（与指派写口同源——候选集即拟指派集）
        signedPrecheck("executor", "builtin:prd-writing")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_BUILTIN_NOT_ASSIGNABLE_CODE));
        // 未寻址/畸形 TSID：404 SKL_001
        signedPrecheck("executor", "999999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));
        signedPrecheck("executor", "not-a-tsid")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));

        // 解析守卫先于判定调用：零模型调用
        verify(agentClient, never()).converse(any(), any());
    }

    // ---------- 鉴权：非签名被拒 ----------

    @Test
    void given_no_signature_headers_when_precheck_then_401_signature_required() throws Exception {
        mockMvc.perform(post("/api/backoffice/skills/assignments/executor/precheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillIds\": []}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // ---------- 夹具与桩 ----------

    /** 种植库条目（预检只读面夹具——frontmatter 与 description 同源）。 */
    private long plantSkill(long id, String name, String description, String content) {
        plantedSkillIds.add(id);
        jdbcTemplate.update("""
                INSERT INTO skl_skills
                    (id, name, description, source_package, version, status, frontmatter, content)
                VALUES (?, ?, ?, '预检夹具包', 'commit-pc', 1, ?::jsonb, ?)
                """,
                id, name, description,
                "{\"name\": \"" + name + "\", \"description\": \"" + description + "\"}", content);
        return id;
    }

    /** 直插指派行（已指派启用技能夹具——预检对照头）。 */
    private void assign(String slot, long skillId) {
        jdbcTemplate.update(
                "INSERT INTO skl_slot_assignments (slot, skill_id, operator_id, operator_name) "
                        + "VALUES (?, ?, ?, ?)",
                slot, skillId, OPERATOR_ID, OPERATOR_NAME);
    }

    /** 造一条 executor 协议覆盖行（生效值库值优先断言用）。 */
    private void overrideExecutorProtocol() {
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, system_prompt, model_id, operator_id, operator_name)
                VALUES ('executor', ?, NULL, ?, ?)
                ON CONFLICT (agent_key) DO UPDATE SET system_prompt = EXCLUDED.system_prompt
                """, PROTOCOL_OVERRIDE, OPERATOR_ID, OPERATOR_NAME);
    }

    /** 脚本化判定（skillcheck-* 会话回放文本）；其余会话按普通回复（不应被触）。 */
    private void givenPrecheckReply(String replyText) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command == null) {
                return null; // 重打桩空参（Mockito when() 触发旧 answer）
            }
            if (command.sessionId().startsWith("skillcheck-")) {
                return new AgentReply(command.runId(), replyText);
            }
            return new AgentReply(command.runId(), "其他会话不应被调用");
        });
    }

    /** 捕获判定命令（单次 converse 断言形制）。 */
    private AgentCommand capturedCommand() {
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(command.capture(), any());
        return command.getValue();
    }

    /** 捕获判定事件 sink（空 sink 断言形制——喂事件验证无事发生）。 */
    @SuppressWarnings("unchecked")
    private Consumer<AgentEvent> capturedSink() {
        ArgumentCaptor<Consumer<AgentEvent>> sink = ArgumentCaptor.forClass(Consumer.class);
        verify(agentClient, times(1)).converse(any(), sink.capture());
        return sink.getValue();
    }

    private Integer assignmentRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skl_slot_assignments", Integer.class);
    }

    /** 签名预检 POST（JSON 体＝候选 skillIds，与 PUT 指派同形）。 */
    private ResultActions signedPrecheck(String slot, String... skillIds) throws Exception {
        String path = "/api/backoffice/skills/assignments/" + slot + "/precheck";
        StringBuilder ids = new StringBuilder();
        for (String id : skillIds) {
            if (!ids.isEmpty()) {
                ids.append(", ");
            }
            ids.append('"').append(id).append('"');
        }
        String body = "{\"skillIds\": [%s]}".formatted(ids);
        return mockMvc.perform(BackofficeSignatures.signed(
                post(path).contentType(MediaType.APPLICATION_JSON).content(body), path, body));
    }

    /** 签名 PUT 指派（非阻断断言用——预检失败后指派照常）。 */
    private ResultActions signedPutAssignments(String slot, String... skillIds) throws Exception {
        String path = "/api/backoffice/skills/assignments/" + slot;
        StringBuilder ids = new StringBuilder();
        for (String id : skillIds) {
            if (!ids.isEmpty()) {
                ids.append(", ");
            }
            ids.append('"').append(id).append('"');
        }
        String body = "{\"skillIds\": [%s]}".formatted(ids);
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(
                put(path).contentType(MediaType.APPLICATION_JSON).content(body), path, body)
                .header("X-User-Id", OPERATOR_ID).header("X-User-Name", OPERATOR_NAME);
        return mockMvc.perform(request);
    }
}

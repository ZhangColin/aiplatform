package com.aieducenter.aiplatform.business.project.endpoints.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.agentscope.HarnessBuiltinTools;
import com.aieducenter.aiplatform.business.project.application.AgentConfigAppService;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.infrastructure.agentscope.ProfileSubagentSupplier;
import com.aieducenter.aiplatform.business.project.infrastructure.agentscope.ProfileToolkitSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工具面两端点（#252，GET 清单＋PUT 窄幅开关）在 {@code #152} seam 上全绿：真过滤链
 * （签名闸/会话豁免/强制拦截器）＋真应用服务＋aiplatform_test 真库。本测试类独写
 * {@code prj_agent_configs}/{@code prj_agent_config_traces}——teardown 整面清即净。
 *
 * <p>六面钉死（#252 验收面）：</p>
 * <ul>
 * <li><b>清单可观测</b>：三槽位分组（main/executor/subagent）——main 平台八件
 * （骨架六＋增强两）、executor 平台两件＋harness 内建编码工具（注册自省，含
 * read_file/execute）、subagent＝self-test 声明六件；增强 enabled 按生效开关、
 * 骨架/内建恒 true；</li>
 * <li><b>开关生效装配断言</b>：PUT 关 web_search → 清单 enabled=false ＋ 真装配链
 * （{@link AgentConfigAppService#effectiveOf} 取生效规格 →
 * {@link ProfileToolkitSupplier#toolkitFor} 真装配）不含 web_search、fetch_url 与
 * 骨架不动；PUT 开 → 回归（装配含回）；</li>
 * <li><b>骨架锁死</b>：骨架工具名（ask_user/savePrd/finish_edit/update_plan 等）与
 * harness 内建名（read_file/execute）接口层拒绝 403 PRJ_036（ADR-0021 编排权
 * 不下放配置）；</li>
 * <li><b>留痕共机制</b>：开关变更走配置留痕（值面四件全量快照＋操作者）、幂等
 * 重写零落痕、与全量 PUT 共表；</li>
 * <li><b>负例</b>：未知工具名 404 PRJ_035、enabled 缺 400 PRJ_037、缺操作者头
 * 400 PRJ_034；</li>
 * <li><b>鉴权</b>：非签名 401。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeAgentToolSeamTest {

    /** 信封数字业务码：PRJ 域码 4 × 1000＋序号（PRJ_035/036/037）。 */
    private static final int TOOL_NOT_FOUND_CODE = 4035;
    private static final int TOOL_TOGGLE_FORBIDDEN_CODE = 4036;
    private static final int TOOL_TOGGLE_TARGET_REQUIRED_CODE = 4037;
    private static final int OPERATOR_REQUIRED_CODE = 4034;

    private static final String OPERATOR_ID = "700310";
    private static final String OPERATOR_NAME = "运营·工具面管理员";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 装配读面与真装配器（#252 装配断言：关→退出装配面、开→回归）。 */
    @Autowired
    private AgentConfigAppService agentConfigs;

    @Autowired
    private ProfileToolkitSupplier profileToolkitSupplier;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_agent_config_traces");
        jdbcTemplate.update("DELETE FROM prj_agent_configs");
    }

    // ---------- 清单可观测：三槽位分组、类别与挂载态 ----------

    @Test
    void given_defaults_when_signed_get_inventory_then_three_slots_with_expected_tools()
            throws Exception {
        signedGet()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(3)))
                // 三槽位键序（与技能槽位同键）
                .andExpect(jsonPath("$.data[0].slot").value("main"))
                .andExpect(jsonPath("$.data[1].slot").value("executor"))
                .andExpect(jsonPath("$.data[2].slot").value("subagent"))
                // main：平台八件（骨架六恒挂载＋增强两件按生效开关缺省开）
                .andExpect(jsonPath("$.data[0].tools", hasSize(8)))
                .andExpect(jsonPath("$.data[0].tools[?(@.name == 'web_search')].kind")
                        .value("ENHANCEMENT"))
                .andExpect(jsonPath("$.data[0].tools[?(@.name == 'web_search')].enabled")
                        .value(true))
                .andExpect(jsonPath("$.data[0].tools[?(@.name == 'fetch_url')].enabled")
                        .value(true))
                .andExpect(jsonPath("$.data[0].tools[?(@.name == 'ask_user')].kind")
                        .value("SKELETON"))
                .andExpect(jsonPath("$.data[0].tools[?(@.name == 'ask_user')].enabled").value(true))
                // executor：平台两件（骨架）＋harness 内建编码工具（注册自省）
                .andExpect(jsonPath("$.data[1].tools[?(@.name == 'finish_edit')].kind")
                        .value("SKELETON"))
                .andExpect(jsonPath("$.data[1].tools[?(@.name == 'read_file')].kind")
                        .value("HARNESS_BUILTIN"))
                .andExpect(jsonPath("$.data[1].tools[?(@.name == 'execute')].kind")
                        .value("HARNESS_BUILTIN"))
                // harness 内建件数与自省一致（平台两件之外全是内建呈现）
                .andExpect(jsonPath("$.data[1].tools.length()").value(
                        2 + harnessBuiltinCount()))
                // subagent：self-test 声明六件（同源引用声明常量）
                .andExpect(jsonPath("$.data[2].tools", hasSize(
                        ProfileSubagentSupplier.SELF_TEST_TOOLS.size())));
    }

    // ---------- 开关生效：关→退出装配面、开→回归（装配断言） ----------

    @Test
    void given_web_search_toggled_off_when_inventory_and_assembly_then_exits_and_rejoins()
            throws Exception {
        // 关：回执 enabled=false
        signedPut("web_search", "{\"enabled\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("web_search"))
                .andExpect(jsonPath("$.data.kind").value("ENHANCEMENT"))
                .andExpect(jsonPath("$.data.enabled").value(false));

        // 清单面：main 槽 web_search 在册未挂载；fetch_url 与骨架不动
        signedGet()
                .andExpect(jsonPath("$.data[0].tools[?(@.name == 'web_search')].enabled")
                        .value(false))
                .andExpect(jsonPath("$.data[0].tools[?(@.name == 'fetch_url')].enabled")
                        .value(true));

        // 装配断言（#252 验收）：真装配链——生效规格（effectiveOf 单点）→ 真装配器，
        // web_search 退出装配面、骨架与 fetch_url 仍在
        var spec = agentConfigs.effectiveOf(AgentProfile.MAIN);
        var toolkit = profileToolkitSupplier.toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), spec.toolSpec());
        assertThat(toolkit.getToolNames()).doesNotContain("web_search");
        assertThat(toolkit.getToolNames()).contains("fetch_url", "ask_user", "savePrd",
                "saveBuildPlan", "list_workspace_files", "read_workspace_file",
                "query_project_facts");

        // 开：回归（装配含回）
        signedPut("web_search", "{\"enabled\": true}")
                .andExpect(jsonPath("$.data.enabled").value(true));
        var specBack = agentConfigs.effectiveOf(AgentProfile.MAIN);
        var toolkitBack = profileToolkitSupplier.toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), specBack.toolSpec());
        assertThat(toolkitBack.getToolNames()).contains("web_search");
    }

    // ---------- 留痕共机制：开关变更走配置留痕、幂等零落痕 ----------

    @Test
    void given_tool_toggle_when_traces_then_config_trace_mechanism_shared() throws Exception {
        // 先落一条 prompt 覆盖（开关写口必须不碰其余值面——同表共留痕的对照基础）
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs (agent_key, system_prompt, model_id)
                VALUES ('main', '主智能体覆盖协议 V1：每轮先复述目标。', null)
                """);
        signedPut("fetch_url", "{\"enabled\": false}").andExpect(status().isOk());
        // 值面只动开关列：prompt 覆盖原样保留（窄幅写不是全量清空）
        var row = jdbcTemplate.queryForMap(
                "SELECT system_prompt, fetch_url_enabled, web_search_enabled, operator_id"
                        + " FROM prj_agent_configs WHERE agent_key = 'main'");
        assertThat(row)
                .containsEntry("system_prompt", "主智能体覆盖协议 V1：每轮先复述目标。")
                .containsEntry("fetch_url_enabled", false)
                .containsEntry("web_search_enabled", true)
                .containsEntry("operator_id", OPERATOR_ID);

        // 走配置留痕读面（与智能体配置同机制）：一痕、old/new 开关对照、操作者在
        mockMvc.perform(BackofficeSignatures.signed(
                get("/api/backoffice/agent-configs/main/traces"),
                "/api/backoffice/agent-configs/main/traces", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].oldFetchUrlEnabled").value(true))
                .andExpect(jsonPath("$.data[0].newFetchUrlEnabled").value(false))
                .andExpect(jsonPath("$.data[0].oldWebSearchEnabled").value(true))
                .andExpect(jsonPath("$.data[0].newWebSearchEnabled").value(true))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data[0].operatorName").value(OPERATOR_NAME));

        // 幂等重写（已是关态再关）：零落痕零写行
        signedPut("fetch_url", "{\"enabled\": false}").andExpect(status().isOk());
        mockMvc.perform(BackofficeSignatures.signed(
                get("/api/backoffice/agent-configs/main/traces"),
                "/api/backoffice/agent-configs/main/traces", null))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    // ---------- 骨架锁死：接口层拒绝、语义明确 ----------

    @Test
    void given_skeleton_or_harness_builtin_when_toggle_then_403_with_explicit_semantics()
            throws Exception {
        // 骨架（编排链路件——main 与 executor 各抽查）与 harness 内建（呈现口径件）
        // 一律 403 PRJ_036：ADR-0021 编排权不下放配置，结构性锁死
        for (String toolName : new String[]{"ask_user", "savePrd", "saveBuildPlan",
                "query_project_facts", "finish_edit", "update_plan", "read_file", "execute"}) {
            signedPut(toolName, "{\"enabled\": false}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(TOOL_TOGGLE_FORBIDDEN_CODE))
                    .andExpect(jsonPath("$.message").value(
                            "该工具不开放开关（骨架/harness 内建结构性锁死——编排权不下放配置）"));
        }
        // 拒绝零写入（负例不落行不落痕）
        assertThat(configRowCount()).isZero();
        assertThat(traceRowCount()).isZero();
    }

    // ---------- 负例＋鉴权 ----------

    @Test
    void given_unknown_tool_when_toggle_then_404() throws Exception {
        signedPut("web_search_super", "{\"enabled\": false}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(TOOL_NOT_FOUND_CODE));
        signedPut("web_fetch", "{\"enabled\": false}") // 框架内建名不在本平台面（2.0.1 无）
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(TOOL_NOT_FOUND_CODE));
        assertThat(configRowCount()).isZero();
    }

    @Test
    void given_missing_enabled_or_operator_when_toggle_then_400() throws Exception {
        // enabled 必填（无缺省翻转语义）
        signedPut("web_search", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TOOL_TOGGLE_TARGET_REQUIRED_CODE))
                .andExpect(jsonPath("$.message").value("开关目标态必填（enabled=true/false）"));
        // 操作者透传头必留痕（与配置写口同款）
        String path = "/api/backoffice/agent-tools/web_search";
        mockMvc.perform(BackofficeSignatures.signed(
                put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"), path, "{\"enabled\": false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(OPERATOR_REQUIRED_CODE));
        assertThat(configRowCount()).isZero();
        assertThat(traceRowCount()).isZero();
    }

    @Test
    void given_no_signature_headers_when_any_endpoint_then_401() throws Exception {
        mockMvc.perform(get("/api/backoffice/agent-tools"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
        mockMvc.perform(put("/api/backoffice/agent-tools/web_search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // ---------- 夹具 ----------

    /** harness 内建编码工具件数（自省单源——清单断言不手工抄数）。 */
    private static int harnessBuiltinCount() {
        return HarnessBuiltinTools.codingToolNames().size();
    }

    private ResultActions signedGet() throws Exception {
        String path = "/api/backoffice/agent-tools";
        return mockMvc.perform(BackofficeSignatures.signed(get(path), path, null));
    }

    private ResultActions signedPut(String toolName, String body) throws Exception {
        String path = "/api/backoffice/agent-tools/" + toolName;
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(
                put(path).contentType(MediaType.APPLICATION_JSON).content(body), path, body);
        return mockMvc.perform(request.header("X-User-Id", OPERATOR_ID)
                .header("X-User-Name", OPERATOR_NAME));
    }

    private Integer configRowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prj_agent_configs", Integer.class);
    }

    private Integer traceRowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prj_agent_config_traces",
                Integer.class);
    }
}

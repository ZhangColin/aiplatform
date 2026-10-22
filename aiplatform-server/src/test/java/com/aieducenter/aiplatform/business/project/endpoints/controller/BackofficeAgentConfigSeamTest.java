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
import com.aieducenter.aiplatform.business.project.application.AgentConfigAppService;
import com.aieducenter.aiplatform.business.project.application.ModelTierAppService;
import com.aieducenter.aiplatform.business.project.application.ModelTierSeed;
import com.aieducenter.aiplatform.business.project.domain.model.AgentEdgePoint;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 智能体运营配置三端点（#251，GET/PUT 配置＋GET 留痕）在 {@code #152} seam 上
 * 全绿：真过滤链（签名闸/会话豁免/强制拦截器）＋真应用服务＋aiplatform_test 真库。
 * 本测试类独写 {@code prj_agent_configs}/{@code prj_agent_config_traces}——teardown
 * 整面清即净。
 *
 * <p>六面钉死（#251 验收面）：</p>
 * <ul>
 * <li><b>读面缺省</b>：无覆盖行＝全回落——生效值逐字等于枚举默认、覆盖标记
 * false、默认预览同值、开关 true、写者 null；</li>
 * <li><b>覆盖写＋装配断言（缝 2）</b>：PUT 后 GET 生效值＝库值＋覆盖标记 true；
 * 装配读面（{@link AgentConfigAppService}——MainAgentAppService/CoderRunAttempts
 * 命令构建的取值单点）返回库值（systemPrompt 与档位键）；两座智能体各自独立（动 main 不动 executor）；</li>
 * <li><b>清空回落</b>：null/缺省/纯空白＝清空覆盖，GET 与装配读面回枚举默认；</li>
 * <li><b>留痕＋回滚</b>：每次实际变更一痕（前后全量值快照＋操作者＋倒序最近先）、
 * 同值幂等重写不落痕；回滚＝旧值写回（一次新变更、留新痕——不做版本树）；</li>
 * <li><b>工具开关写面</b>：PUT 开关落库留痕、GET 可见（装配生效与窄幅写口
 * 属 #252 工具面两端点）；开关缺省 true；</li>
 * <li><b>负例＋鉴权</b>：未知键（classify/naming 一次性判定不进配置面）404
 * PRJ_033＋缺操作者 400 PRJ_034＋非签名 401。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeAgentConfigSeamTest {

    /** 信封数字业务码：PRJ 域码 4 × 1000＋序号（PRJ_033/PRJ_034）。 */
    private static final int AGENT_CONFIG_NOT_FOUND_CODE = 4033;
    private static final int AGENT_CONFIG_OPERATOR_REQUIRED_CODE = 4034;

    /** 操作者透传头样例（admin 侧管理员，签名面明示信任）。 */
    private static final String OPERATOR_ID = "700210";
    private static final String OPERATOR_NAME = "运营·智能体管理员";
    private static final String OPERATOR_ID_2 = "700211";
    private static final String OPERATOR_NAME_2 = "运营·小陈";

    private static final String MAIN_PROMPT_V1 = "主智能体覆盖协议 V1：每轮先复述目标。";
    private static final String MAIN_PROMPT_V2 = "主智能体覆盖协议 V2：催促收敛优先。";
    private static final String EXECUTOR_PROMPT_V1 = "执行体覆盖协议 V1：开工先列步骤。";

    /** 信封数字业务码：PRJ 域码 4 × 1000＋序号（边缘点面 PRJ_064，#366）。 */
    private static final int EDGE_POINT_NOT_FOUND_CODE = 4064;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 装配读面（#251 缝 2 真链路断言：两座命令构建的取值单点）。 */
    @Autowired
    private AgentConfigAppService agentConfigs;

    /** 档位解析（ADR-0031 解析单点：边缘点档位键 → 模型串＋通道，#366 缝 2 真链路）。 */
    @Autowired
    private ModelTierAppService modelTiers;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_agent_config_traces");
        jdbcTemplate.update("DELETE FROM prj_agent_configs");
    }

    // ---------- 读面缺省：无覆盖行＝全回落枚举默认 ----------

    @Test
    void given_no_override_row_when_signed_get_then_effective_falls_back_to_enum_defaults()
            throws Exception {
        for (String agentKey : new String[]{"main", "executor"}) {
            AgentProfile profile = AgentProfile.byKey(agentKey).orElseThrow();
            signedGet(agentKey)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.agentKey").value(agentKey))
                    .andExpect(jsonPath("$.data.agentName").value(profile.getName()))
                    // 生效值＝枚举默认（逐字）；覆盖标记 false；默认预览同值
                    .andExpect(jsonPath("$.data.systemPrompt").value(profile.systemPrompt()))
                    .andExpect(jsonPath("$.data.modelId").value(profile.modelId()))
                    .andExpect(jsonPath("$.data.systemPromptOverridden").value(false))
                    .andExpect(jsonPath("$.data.modelIdOverridden").value(false))
                    .andExpect(jsonPath("$.data.defaultSystemPrompt").value(profile.systemPrompt()))
                    .andExpect(jsonPath("$.data.defaultModelId").value(profile.modelId()))
                    // 开关存储态缺省 true（与现行工具装配一致）
                    .andExpect(jsonPath("$.data.webSearchEnabled").value(true))
                    .andExpect(jsonPath("$.data.fetchUrlEnabled").value(true))
                    // 从未配置：写者两列 null
                    .andExpect(jsonPath("$.data.operatorId").value(nullValue()))
                    .andExpect(jsonPath("$.data.operatorName").value(nullValue()));
        }
        // 装配读面同步回落（缝 2：命令构建取值单点）
        var mainDefaults = agentConfigs.effectiveOf(AgentProfile.MAIN);
        assertThat(mainDefaults.systemPrompt()).isEqualTo(AgentProfile.MAIN.systemPrompt());
                assertThat(agentConfigs.effectiveOf(AgentProfile.EXECUTOR).systemPrompt())
                .isEqualTo(AgentProfile.EXECUTOR.systemPrompt());
    }

    // ---------- 覆盖写＋装配断言：库值优先，两座独立 ----------

    @Test
    void given_override_persisted_when_get_and_assembly_then_library_value_wins()
            throws Exception {
        signedPut("main", OPERATOR_ID, OPERATOR_NAME, """
                {"systemPrompt": "%s", "modelId": "pro"}""".formatted(MAIN_PROMPT_V1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentKey").value("main"))
                .andExpect(jsonPath("$.data.systemPrompt").value(MAIN_PROMPT_V1))
                .andExpect(jsonPath("$.data.modelId").value("pro"))
                .andExpect(jsonPath("$.data.systemPromptOverridden").value(true))
                .andExpect(jsonPath("$.data.modelIdOverridden").value(true))
                // 默认预览仍指枚举缺省（清空回落即落此值）
                .andExpect(jsonPath("$.data.defaultSystemPrompt").value(AgentProfile.MAIN.systemPrompt()))
                .andExpect(jsonPath("$.data.defaultModelId").value(AgentProfile.MAIN.modelId()))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.operatorName").value(OPERATOR_NAME));

        // GET 重读一致（写回执非独占视图）
        signedGet("main")
                .andExpect(jsonPath("$.data.systemPrompt").value(MAIN_PROMPT_V1))
                .andExpect(jsonPath("$.data.modelIdOverridden").value(true));

        // 装配断言（缝 2）：库值优先——systemPrompt 取库值、模型串 provider 前缀单源拼装
        var main = agentConfigs.effectiveOf(AgentProfile.MAIN);
        assertThat(main.systemPrompt()).isEqualTo(MAIN_PROMPT_V1);
        assertThat(main.modelId()).isEqualTo("pro");
        // 两座独立：main 的覆盖不漂进 executor（仍枚举默认）
        var executorDefaults = agentConfigs.effectiveOf(AgentProfile.EXECUTOR);
        assertThat(executorDefaults.systemPrompt()).isEqualTo(AgentProfile.EXECUTOR.systemPrompt());
        assertThat(executorDefaults.modelId()).isEqualTo(AgentProfile.EXECUTOR.modelId());
    }

    // ---------- 清空回落：null/缺省/纯空白＝回落枚举默认 ----------

    @Test
    void given_override_cleared_when_get_and_assembly_then_back_to_enum_defaults()
            throws Exception {
        // #341 起 Demo 阶段（executor）缺省＝快模型档，覆盖档取强模型档以示覆盖值≠缺省值
        signedPut("executor", OPERATOR_ID, OPERATOR_NAME,
                "{\"systemPrompt\": \"%s\", \"modelId\": \"pro\"}"
                        .formatted(EXECUTOR_PROMPT_V1))
                .andExpect(status().isOk());
        assertThat(agentConfigs.effectiveOf(AgentProfile.EXECUTOR).systemPrompt())
                .isEqualTo(EXECUTOR_PROMPT_V1);

        // 缺省字段即清空（PUT 全量语义：systemPrompt/modelId 不带＝null）
        signedPut("executor", OPERATOR_ID_2, OPERATOR_NAME_2, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemPrompt").value(AgentProfile.EXECUTOR.systemPrompt()))
                .andExpect(jsonPath("$.data.modelId").value(AgentProfile.EXECUTOR.modelId()))
                .andExpect(jsonPath("$.data.systemPromptOverridden").value(false))
                .andExpect(jsonPath("$.data.modelIdOverridden").value(false));
        var executorBack = agentConfigs.effectiveOf(AgentProfile.EXECUTOR);
        assertThat(executorBack.systemPrompt()).isEqualTo(AgentProfile.EXECUTOR.systemPrompt());
        assertThat(executorBack.modelId()).isEqualTo(AgentProfile.EXECUTOR.modelId());

        // 纯空白同 null（归一）；显式 null 同语义；行保留（写者留最近动作者）
        signedPut("main", OPERATOR_ID, OPERATOR_NAME,
                "{\"systemPrompt\": \"%s\"}".formatted(MAIN_PROMPT_V1))
                .andExpect(status().isOk());
        signedPut("main", OPERATOR_ID_2, OPERATOR_NAME_2,
                "{\"systemPrompt\": \"  \", \"modelId\": null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemPrompt").value(AgentProfile.MAIN.systemPrompt()))
                .andExpect(jsonPath("$.data.modelId").value(AgentProfile.MAIN.modelId()));
        var row = jdbcTemplate.queryForMap(
                "SELECT system_prompt, model_id, operator_id FROM prj_agent_configs WHERE agent_key = 'main'");
        assertThat(row)
                .containsEntry("system_prompt", null)
                .containsEntry("model_id", null)
                .containsEntry("operator_id", OPERATOR_ID_2);
    }

    // ---------- 留痕：前后全量快照＋操作者＋倒序；幂等不落痕；回滚＝写回 ----------

    @Test
    void given_sequence_of_changes_when_traces_then_full_snapshots_newest_first()
            throws Exception {
        // 变更一（V1 覆盖）→ 变更二（V2 翻新）→ 幂等（同值重写）→ 变更三（回滚＝V1 写回）
        signedPut("main", OPERATOR_ID, OPERATOR_NAME,
                "{\"systemPrompt\": \"%s\", \"modelId\": \"pro\"}".formatted(MAIN_PROMPT_V1))
                .andExpect(status().isOk());
        signedPut("main", OPERATOR_ID_2, OPERATOR_NAME_2,
                "{\"systemPrompt\": \"%s\"}".formatted(MAIN_PROMPT_V2))
                .andExpect(status().isOk());
        // 同值幂等重写：值面无动——不落痕不写行（操作者列不因空写漂移）
        signedPut("main", OPERATOR_ID, OPERATOR_NAME,
                "{\"systemPrompt\": \"%s\"}".formatted(MAIN_PROMPT_V2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID_2));
        // 回滚＝把留痕旧值写回（一次新变更、留新痕——不做版本树）
        signedPut("main", OPERATOR_ID, OPERATOR_NAME,
                "{\"systemPrompt\": \"%s\", \"modelId\": \"pro\"}".formatted(MAIN_PROMPT_V1))
                .andExpect(status().isOk());

        signedGet("main/traces")
                .andExpect(status().isOk())
                // 三痕（幂等重写不落痕）倒序：最近先
                .andExpect(jsonPath("$.data", hasSize(3)))
                // 痕三（最近）：回滚——old=V2/模型清空态，new=V1/pro
                .andExpect(jsonPath("$.data[0].oldSystemPrompt").value(MAIN_PROMPT_V2))
                .andExpect(jsonPath("$.data[0].oldModelId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].newSystemPrompt").value(MAIN_PROMPT_V1))
                .andExpect(jsonPath("$.data[0].newModelId").value("pro"))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data[0].oldWebSearchEnabled").value(true))
                .andExpect(jsonPath("$.data[0].newWebSearchEnabled").value(true))
                // 痕二：翻新——old=V1/pro，new=V2/模型清空（PUT 全量：未带即 null）
                .andExpect(jsonPath("$.data[1].oldSystemPrompt").value(MAIN_PROMPT_V1))
                .andExpect(jsonPath("$.data[1].oldModelId").value("pro"))
                .andExpect(jsonPath("$.data[1].newSystemPrompt").value(MAIN_PROMPT_V2))
                .andExpect(jsonPath("$.data[1].newModelId").value(nullValue()))
                .andExpect(jsonPath("$.data[1].operatorId").value(OPERATOR_ID_2))
                .andExpect(jsonPath("$.data[1].operatorName").value(OPERATOR_NAME_2))
                // 痕一（最早）：首次覆盖——old 全缺省态（prompt/model null、开关 true）
                .andExpect(jsonPath("$.data[2].oldSystemPrompt").value(nullValue()))
                .andExpect(jsonPath("$.data[2].oldModelId").value(nullValue()))
                .andExpect(jsonPath("$.data[2].newSystemPrompt").value(MAIN_PROMPT_V1))
                .andExpect(jsonPath("$.data[2].newModelId").value("pro"))
                .andExpect(jsonPath("$.data[2].operatorId").value(OPERATOR_ID));

        // 留痕跨智能体隔离：executor 无痕
        signedGet("executor/traces")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ---------- 工具开关写面：落库留痕可见（装配生效属 #252 工具面） ----------

    @Test
    void given_tool_toggles_persisted_when_get_and_traces_then_storage_face_visible()
            throws Exception {
        // 覆盖行落库时开关缺省 true；显式关两件（#252 起生效装配——窄幅写口在工具面）
        signedPut("main", OPERATOR_ID, OPERATOR_NAME,
                "{\"webSearchEnabled\": false, \"fetchUrlEnabled\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.webSearchEnabled").value(false))
                .andExpect(jsonPath("$.data.fetchUrlEnabled").value(false))
                // 只动开关也整痕（值面四件全量快照，prompt/model 本次清空回落）
                .andExpect(jsonPath("$.data.systemPromptOverridden").value(false));
        var row = jdbcTemplate.queryForMap(
                "SELECT web_search_enabled, fetch_url_enabled FROM prj_agent_configs WHERE agent_key = 'main'");
        assertThat(row)
                .containsEntry("web_search_enabled", false)
                .containsEntry("fetch_url_enabled", false);
        signedGet("main/traces")
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].oldWebSearchEnabled").value(true))
                .andExpect(jsonPath("$.data[0].newWebSearchEnabled").value(false))
                .andExpect(jsonPath("$.data[0].oldFetchUrlEnabled").value(true))
                .andExpect(jsonPath("$.data[0].newFetchUrlEnabled").value(false));

        // 开一回：留新痕（old=false new=true）
        signedPut("main", OPERATOR_ID, OPERATOR_NAME, "{\"webSearchEnabled\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.webSearchEnabled").value(true));
        signedGet("main/traces")
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].oldWebSearchEnabled").value(false))
                .andExpect(jsonPath("$.data[0].newWebSearchEnabled").value(true));
    }

    // ---------- 负例＋鉴权 ----------

    @Test
    void given_unknown_agent_key_when_any_endpoint_then_404() throws Exception {
        // 三座端点只认三座智能体：边缘调用点（naming 等）走 /edge/{edgeKey} 面档位
        // 端点（#366），三座端点照 404；任意未知键同语义（含 verify 子智能体——
        // 配置面只有三座智能体，#289 designer 座齐）
        for (String badKey : new String[]{"classify", "naming", "reviewer", "verify"}) {
            signedGet(badKey)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(AGENT_CONFIG_NOT_FOUND_CODE))
                    .andExpect(jsonPath("$.message").value(
                            "智能体不存在（运营配置面只有 main/executor/designer 三座智能体）"));
            signedGet(badKey + "/traces")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(AGENT_CONFIG_NOT_FOUND_CODE));
            signedPut(badKey, OPERATOR_ID, OPERATOR_NAME, "{}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(AGENT_CONFIG_NOT_FOUND_CODE));
        }
        // 拒绝零写入（负例不落行不落痕）
        assertThat(configRowCount()).isZero();
        assertThat(traceRowCount()).isZero();
    }

    @Test
    void given_missing_operator_headers_when_signed_put_then_400() throws Exception {
        signedPut("main", null, null, "{\"modelId\": \"pro\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AGENT_CONFIG_OPERATOR_REQUIRED_CODE))
                .andExpect(jsonPath("$.message").value("操作者不能为空"));
        // fail-fast：配置行与留痕零写入
        assertThat(configRowCount()).isZero();
        assertThat(traceRowCount()).isZero();
    }

    @Test
    void given_no_signature_headers_when_any_endpoint_then_401_signature_required() throws Exception {
        mockMvc.perform(put("/api/backoffice/agent-configs/main")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\": \"pro\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
        mockMvc.perform(get("/api/backoffice/agent-configs/main"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
        mockMvc.perform(get("/api/backoffice/agent-configs/main/traces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // ---------- 模型路由策略四件（#354：读面缺省 → 覆盖写＋留痕 → 清空回落） ----------

    @Test
    void given_no_routing_override_when_get_then_code_defaults_with_unoverridden_marks()
            throws Exception {
        // 路由策略读面缺省：开关 true＝开、强档与两阈值回落代码缺省（覆盖标记
        // false、默认预览同值）——路由开且无钉死即路由判定（缺省快档）
        signedGet("executor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routingEnabled").value(true))
                .andExpect(jsonPath("$.data.routingStrongModelId").value("pro"))
                .andExpect(jsonPath("$.data.routingStrongModelIdOverridden").value(false))
                .andExpect(jsonPath("$.data.defaultRoutingStrongModelId").value("pro"))
                .andExpect(jsonPath("$.data.routingFileCountThreshold").value(60))
                .andExpect(jsonPath("$.data.routingFileCountThresholdOverridden").value(false))
                .andExpect(jsonPath("$.data.defaultRoutingFileCountThreshold").value(60))
                .andExpect(jsonPath("$.data.routingSliceFeatureThreshold").value(4))
                .andExpect(jsonPath("$.data.routingSliceFeatureThresholdOverridden").value(false))
                .andExpect(jsonPath("$.data.defaultRoutingSliceFeatureThreshold").value(4));
        // 装配读面同步：路由开、三可空件 null（回落缺省归路由判定单点）
        var executor = agentConfigs.effectiveOf(AgentProfile.EXECUTOR);
        assertThat(executor.routingEnabled()).isTrue();
        assertThat(executor.routingStrongModelId()).isNull();
        assertThat(executor.routingFileCountThreshold()).isNull();
        assertThat(executor.routingSliceFeatureThreshold()).isNull();
    }

    @Test
    void given_routing_strategy_updated_when_get_and_traces_then_effective_and_snapshots()
            throws Exception {
        // 策略覆盖写（开关关＋强档换＋阈值换）→ GET 生效值＝库值、覆盖标记 true；
        // 留痕前后快照含路由四件；清空（null）回落代码缺省
        signedPut("executor", OPERATOR_ID, OPERATOR_NAME, """
                {"routingEnabled": false, "routingStrongModelId": "deepseek-v4-ultra",
                 "routingFileCountThreshold": 30, "routingSliceFeatureThreshold": 10}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routingEnabled").value(false))
                .andExpect(jsonPath("$.data.routingStrongModelId").value("deepseek-v4-ultra"))
                .andExpect(jsonPath("$.data.routingStrongModelIdOverridden").value(true))
                .andExpect(jsonPath("$.data.routingFileCountThreshold").value(30))
                .andExpect(jsonPath("$.data.routingSliceFeatureThreshold").value(10));
        var executor = agentConfigs.effectiveOf(AgentProfile.EXECUTOR);
        assertThat(executor.routingEnabled()).isFalse();
        assertThat(executor.routingStrongModelId()).isEqualTo("deepseek-v4-ultra");

        signedGet("executor/traces")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                // 旧态＝缺省（开关开、三可空件 null），新态＝本次覆盖——回滚依据
                .andExpect(jsonPath("$.data[0].oldRoutingEnabled").value(true))
                .andExpect(jsonPath("$.data[0].oldRoutingStrongModelId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].oldRoutingFileCountThreshold").value(nullValue()))
                .andExpect(jsonPath("$.data[0].oldRoutingSliceFeatureThreshold").value(nullValue()))
                .andExpect(jsonPath("$.data[0].newRoutingEnabled").value(false))
                .andExpect(jsonPath("$.data[0].newRoutingStrongModelId").value("deepseek-v4-ultra"))
                .andExpect(jsonPath("$.data[0].newRoutingFileCountThreshold").value(30))
                .andExpect(jsonPath("$.data[0].newRoutingSliceFeatureThreshold").value(10))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID));

        // 清空回落：路由策略件全 null＝回代码缺省（开关 null 归一缺省 true＝开）
        signedPut("executor", OPERATOR_ID, OPERATOR_NAME, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routingEnabled").value(true))
                .andExpect(jsonPath("$.data.routingStrongModelId").value("pro"))
                .andExpect(jsonPath("$.data.routingStrongModelIdOverridden").value(false))
                .andExpect(jsonPath("$.data.routingFileCountThreshold").value(60))
                .andExpect(jsonPath("$.data.routingSliceFeatureThreshold").value(4));
    }

    // ---------- 边缘四点（#366：仅模型档位入配置面，同表同机制） ----------

    @Test
    void given_no_edge_override_when_signed_get_edge_then_effective_falls_back_to_default_tier()
            throws Exception {
        // 四点读面缺省：生效档＝代码缺省档位键（flash）、覆盖标记 false、默认预览
        // 同值、写者 null；装配读面同值且经档位解析真链路产通道模型串（V44 种子）
        ModelTierSeed.ensureChannelKey(jdbcTemplate);
        for (AgentEdgePoint point : AgentEdgePoint.values()) {
            signedGet("edge/" + point.key())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.agentKey").value(point.key()))
                    .andExpect(jsonPath("$.data.agentName").value(point.getName()))
                    .andExpect(jsonPath("$.data.modelId").value("flash"))
                    .andExpect(jsonPath("$.data.modelIdOverridden").value(false))
                    .andExpect(jsonPath("$.data.defaultModelId").value("flash"))
                    .andExpect(jsonPath("$.data.operatorId").value(nullValue()))
                    .andExpect(jsonPath("$.data.operatorName").value(nullValue()));
            // 缝 2（装配读面＋解析单点）：缺省 flash → volcengine 通道模型串
            assertThat(agentConfigs.effectiveEdgeModelId(point)).isEqualTo("flash");
            assertThat(modelTiers.resolve(agentConfigs.effectiveEdgeModelId(point)).modelString())
                    .isEqualTo(ModelTierSeed.FLASH_MODEL_STRING);
        }
    }

    @Test
    void given_edge_tier_overridden_when_put_then_library_value_wins_next_resolve()
            throws Exception {
        // AC：后台 PUT 边缘键改档即时生效——四键逐一 PUT pro：GET 生效值＝库值＋
        // 覆盖标记 true，装配读面取库值、经解析单点产 pro 档模型串（下一轮命令
        // 构建即生效的取值半边）；四点各自独立（动 naming 不动 precheck）
        ModelTierSeed.ensureChannelKey(jdbcTemplate);
        for (AgentEdgePoint point : AgentEdgePoint.values()) {
            signedPutEdge(point.key(), OPERATOR_ID, OPERATOR_NAME, "{\"modelId\": \"pro\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.modelId").value("pro"))
                    .andExpect(jsonPath("$.data.modelIdOverridden").value(true))
                    .andExpect(jsonPath("$.data.defaultModelId").value("flash"))
                    .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID));
            assertThat(agentConfigs.effectiveEdgeModelId(point)).isEqualTo("pro");
            assertThat(modelTiers.resolve(agentConfigs.effectiveEdgeModelId(point)).modelString())
                    .isEqualTo("volcengine:deepseek-v4-pro-ga-260813");
        }

        // 清空覆盖（PUT 全量语义同款：null/缺省/纯空白＝清空）→ 回落代码缺省 flash
        signedPutEdge("naming", OPERATOR_ID_2, OPERATOR_NAME_2, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").value("flash"))
                .andExpect(jsonPath("$.data.modelIdOverridden").value(false));
        assertThat(agentConfigs.effectiveEdgeModelId(AgentEdgePoint.NAMING)).isEqualTo("flash");
        // 其余三点不受清空牵连（各自独立行）
        assertThat(agentConfigs.effectiveEdgeModelId(AgentEdgePoint.COMPACTION))
                .isEqualTo("pro");
    }

    @Test
    void given_edge_changes_when_traces_then_snapshots_and_unknown_key_404() throws Exception {
        // 留痕同机制：改档一痕（old=null 缺省态 → new=pro＋操作者）；同值幂等重写
        // 不落痕；未知边缘键 404 PRJ_064、缺操作者 400 PRJ_034（同面守卫）
        signedPutEdge("compaction", OPERATOR_ID, OPERATOR_NAME, "{\"modelId\": \"pro\"}")
                .andExpect(status().isOk());
        signedPutEdge("compaction", OPERATOR_ID_2, OPERATOR_NAME_2, "{\"modelId\": \"pro\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID)); // 幂等：写者不漂移
        signedGet("edge/compaction/traces")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].oldModelId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].newModelId").value("pro"))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data[0].operatorName").value(OPERATOR_NAME));

        // 回滚＝旧值写回（一次新变更、留新痕）
        signedPutEdge("compaction", OPERATOR_ID_2, OPERATOR_NAME_2, "{\"modelId\": null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").value("flash"));
        signedGet("edge/compaction/traces")
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].newModelId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID_2));

        // 负例：未知边缘键三端点同 404 PRJ_064；边缘键不进三座端点（404 PRJ_033）
        for (String badKey : new String[]{"summarize", "reviewer"}) {
            signedGet("edge/" + badKey)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(EDGE_POINT_NOT_FOUND_CODE));
            signedGet("edge/" + badKey + "/traces")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(EDGE_POINT_NOT_FOUND_CODE));
            signedPutEdge(badKey, OPERATOR_ID, OPERATOR_NAME, "{\"modelId\": \"pro\"}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(EDGE_POINT_NOT_FOUND_CODE));
        }
        signedGet("naming")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(AGENT_CONFIG_NOT_FOUND_CODE));
        // 缺操作者同面守卫（PRJ_034）
        signedPutEdge("naming", null, null, "{\"modelId\": \"pro\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AGENT_CONFIG_OPERATOR_REQUIRED_CODE));
    }

    // ---------- 夹具 ----------

    /** 签名 GET（无体）。 */
    private ResultActions signedGet(String pathSuffix) throws Exception {
        String path = "/api/backoffice/agent-configs/" + pathSuffix;
        return mockMvc.perform(BackofficeSignatures.signed(get(path), path, null));
    }

    /** 签名 PUT 边缘点档位（JSON 体＋操作者透传头；operatorId 传 null 即缺头负例形制）。 */
    private ResultActions signedPutEdge(String edgeKey, String operatorId, String operatorName,
            String body) throws Exception {
        String path = "/api/backoffice/agent-configs/edge/" + edgeKey;
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(
                put(path).contentType(MediaType.APPLICATION_JSON).content(body), path, body);
        if (operatorId != null) {
            request.header("X-User-Id", operatorId);
        }
        if (operatorName != null) {
            request.header("X-User-Name", operatorName);
        }
        return mockMvc.perform(request);
    }

    /** 签名 PUT 配置（JSON 体＋操作者透传头；operatorId 传 null 即缺头负例形制）。 */
    private ResultActions signedPut(String agentKey, String operatorId, String operatorName,
            String body) throws Exception {
        String path = "/api/backoffice/agent-configs/" + agentKey;
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(
                put(path).contentType(MediaType.APPLICATION_JSON).content(body), path, body);
        if (operatorId != null) {
            request.header("X-User-Id", operatorId);
        }
        if (operatorName != null) {
            request.header("X-User-Name", operatorName);
        }
        return mockMvc.perform(request);
    }

    private Integer configRowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prj_agent_configs", Integer.class);
    }

    private Integer traceRowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prj_agent_config_traces", Integer.class);
    }
}

package com.semgravio.aiplatform.business.project.endpoints.controller;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.semgravio.aiplatform.backoffice.BackofficeSeamTest;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.ModelTierAppService;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模型清单手动拉取面（#368，spec #364-T4）在 {@code #152} seam 上全绿：真过滤链
 * （签名闸）＋真应用服务＋aiplatform_test 真库＋真拉取器打 JDK HttpServer 假上游
 * （spec Seam A 预约定「清单拉取（上游 mock）」——不打真供应商、不用真 key）。
 * fixture 用 {@code t-cat*} 前缀键（种子行是他测依赖不动，照 T3 口径只清自家行）。
 *
 * <p>验收面钉死（票 #368 AC）：</p>
 * <ul>
 * <li><b>对照正确</b>：远端有库里无（trackedTierKeys 空＝建议入库）；库内指向
 *     远端已无（staleTiers）；条目 status 如实转述（活跃无 status、Shutdown 有）；</li>
 * <li><b>勾选入库复用 T3 写口</b>：拉取结果里的 modelId 经 POST /model-tiers 入库
 *     新档位 → 绑定智能体 → 装配解析（resolve 半边）跟上——拉取面零新写路径；</li>
 * <li><b>上游不可用明确报错不静默</b>：非 200 / 连接拒绝 → 502 PRJ_077 带理由；</li>
 * <li><b>前置与鉴权</b>：通道 key 空 400 PRJ_076、未知通道 404 PRJ_065、
 *     停用通道可拉（巡检语义）、非签名 401。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeModelCatalogSeamTest {

    /** 信封数字业务码：PRJ 域码 4 × 1000＋序号（#368 拉取面 PRJ_076/077）。 */
    private static final int PROVIDER_NOT_FOUND_CODE = 4065;
    private static final int PULL_KEY_MISSING_CODE = 4076;
    private static final int PULL_FAILED_CODE = 4077;

    private static final String OPERATOR_ID = "700214";
    private static final String OPERATOR_NAME = "运营·清单管理员";

    private static final String PROVIDERS_PATH = "/api/backoffice/model-providers";
    private static final String TIERS_PATH = "/api/backoffice/model-tiers";

    /** 假上游回执（方舟实测形状的缩影：活跃无 status、下架有 Shutdown）。 */
    private static final String UPSTREAM_BODY = """
            {"data":[
              {"id":"m-current-2601","name":"m-current","object":"model","domain":"LLM"},
              {"id":"m-fresh-2602","name":"m-fresh","object":"model","domain":"LLM"},
              {"id":"m-old-2401","name":"m-old","object":"model","status":"Shutdown","domain":"LLM"}
            ]}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 装配读腿（入库→绑定→resolve 半边断言）。 */
    @Autowired
    private AgentConfigAppService agentConfigs;

    /** 档位解析单点（勾选入库后的装配半边——真 LLM 调用属 #369 活体冒烟）。 */
    @Autowired
    private ModelTierAppService modelTiers;

    private HttpServer upstream;

    /** 假上游回执旋钮（单 handler 注册一次——HttpServer 启动后同路径不可重复注册）。 */
    private volatile int upstreamStatus = 200;

    private volatile String upstreamBody = UPSTREAM_BODY;

    @BeforeEach
    void setUp() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 假上游默认回执（方舟实测形状缩影）；未注册路径时 HttpServer 自答 404（负例用）
        upstream.createContext("/models", exchange -> {
            byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(upstreamStatus, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        upstream.start();
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
        // 自家行归零（种子行不动）：绑定例写的 executor 覆盖行一并清（他测依赖
        // 「无行＝全回落」的缺省态）
        jdbcTemplate.update("DELETE FROM prj_agent_config_traces WHERE agent_key = 'executor'");
        jdbcTemplate.update("DELETE FROM prj_agent_configs WHERE agent_key = 'executor'");
        jdbcTemplate.update("DELETE FROM prj_model_tier_traces WHERE tier_key LIKE 't-cat%'");
        jdbcTemplate.update("DELETE FROM prj_model_provider_traces WHERE provider_key LIKE 't-cat%'");
        jdbcTemplate.update("DELETE FROM prj_model_tiers WHERE tier_key LIKE 't-cat%'");
        jdbcTemplate.update("DELETE FROM prj_model_providers WHERE provider_key LIKE 't-cat%'");
    }

    // ---------- 对照正确（AC）：远端/库内差异如实反映 ----------

    @Test
    void given_pulled_catalog_when_compared_then_tracked_untracked_and_stale_reflected()
            throws Exception {
        String providerKey = createProviderAt(upstreamBaseUrl(), "sk-cat-pull-0001");
        createTier("t-cat-current", providerKey, "m-current-2601");
        createTier("t-cat-stale", providerKey, "m-gone-2512", "过时档");

        signedPost(providersRemoteModelsPath(providerKey), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerKey").value(providerKey))
                // 远端全量三条（modelId 序稳定）：对照标记如实——已入库/建议入库/下架
                .andExpect(jsonPath("$.data.remoteModels", hasSize(3)))
                .andExpect(jsonPath("$.data.remoteModels[0].modelId").value("m-current-2601"))
                .andExpect(jsonPath("$.data.remoteModels[0].trackedTierKeys[0]").value("t-cat-current"))
                .andExpect(jsonPath("$.data.remoteModels[0].status").value(nullValue()))
                .andExpect(jsonPath("$.data.remoteModels[1].modelId").value("m-fresh-2602"))
                .andExpect(jsonPath("$.data.remoteModels[1].trackedTierKeys").value(empty()))
                .andExpect(jsonPath("$.data.remoteModels[2].modelId").value("m-old-2401"))
                .andExpect(jsonPath("$.data.remoteModels[2].status").value("Shutdown"))
                // 库内指向远端已无：t-cat-stale（m-gone 不在远端清单）——提示过时
                .andExpect(jsonPath("$.data.staleTiers", hasSize(1)))
                .andExpect(jsonPath("$.data.staleTiers[0].tierKey").value("t-cat-stale"))
                .andExpect(jsonPath("$.data.staleTiers[0].modelName").value("m-gone-2512"))
                .andExpect(jsonPath("$.data.staleTiers[0].displayName").value("过时档"))
                .andExpect(jsonPath("$.data.staleTiers[0].enabled").value(true));

        // 拉取是纯读动作：不落留痕、不写配置行
        assertThat(configTraceCount("executor")).isZero();
    }

    // ---------- 勾选入库复用 T3 写口（AC）：拉取→入库→绑定→装配解析跟上 ----------

    @Test
    void given_untracked_model_from_pull_when_imported_via_tier_create_then_bound_and_resolvable()
            throws Exception {
        String providerKey = createProviderAt(upstreamBaseUrl(), "sk-cat-pull-0002");

        // 拉取：建议入库条目（trackedTierKeys 空）
        String pullBody = signedPost(providersRemoteModelsPath(providerKey), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String freshModelId = new ObjectMapper()
                .readTree(pullBody).path("data").path("remoteModels").get(1)
                .path("modelId").asText();
        assertThat(freshModelId).isEqualTo("m-fresh-2602");

        // 勾选入库＝复用档位创建写口（语义键人工命名、modelName 填远端条目 modelId）
        signedPost(TIERS_PATH, """
                {"tierKey": "t-cat-imported", "providerKey": "%s",
                 "modelName": "%s", "displayName": "清单入库档"}"""
                .formatted(providerKey, freshModelId), OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelName").value("m-fresh-2602"));

        // 绑定一座智能体（运营配置面既有写口）＋装配读面跟上（resolve 半边——
        // 真调用属 #369 活体冒烟，此处钉装配链取到正确模型串与通道注入）
        signedPut("/api/backoffice/agent-configs/executor",
                "{\"modelId\": \"t-cat-imported\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        assertThat(agentConfigs.effectiveOf(AgentProfile.EXECUTOR).modelId())
                .isEqualTo("t-cat-imported");
        ModelTierAppService.ResolvedModel resolved = modelTiers.resolve("t-cat-imported");
        assertThat(resolved.modelString()).isEqualTo(providerKey + ":m-fresh-2602");
        assertThat(resolved.baseUrl()).isEqualTo(upstreamBaseUrl());

        // 再拉一次：对照翻转——刚入库的条目不再是「建议入库」
        signedPost(providersRemoteModelsPath(providerKey), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remoteModels[1].trackedTierKeys[0]")
                        .value("t-cat-imported"))
                .andExpect(jsonPath("$.data.staleTiers").value(empty()));
    }

    // ---------- 上游不可用明确报错不静默（AC）：非 200 / 连接拒绝 ----------

    @Test
    void given_upstream_500_when_pull_then_502_with_reason_not_silent() throws Exception {
        String providerKey = createProviderAt(upstreamBaseUrl(), "sk-cat-pull-0003");
        respondUpstream(500, "upstream exploded");

        signedPost(providersRemoteModelsPath(providerKey), null)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(PULL_FAILED_CODE))
                .andExpect(jsonPath("$.message").value(containsString("500")));
    }

    @Test
    void given_connection_refused_when_pull_then_502_with_reason() throws Exception {
        // 先占后放的死端口＝确定无监听
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        String providerKey = createProviderAt("http://127.0.0.1:" + deadPort, "sk-cat-pull-0004");

        signedPost(providersRemoteModelsPath(providerKey), null)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(PULL_FAILED_CODE))
                .andExpect(jsonPath("$.message").value(containsString("连接异常")));
    }

    // ---------- 前置与鉴权 ----------

    @Test
    void given_provider_without_key_when_pull_then_400_prj076() throws Exception {
        // 建通道不带 key（null＝暂不配置）
        signedPost(PROVIDERS_PATH,
                "{\"providerKey\": \"t-cat-nokey\", \"baseUrl\": \"%s\"}"
                        .formatted(upstreamBaseUrl()),
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());

        signedPost(providersRemoteModelsPath("t-cat-nokey"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PULL_KEY_MISSING_CODE))
                .andExpect(jsonPath("$.message").value(containsString("t-cat-nokey")));
    }

    @Test
    void given_disabled_provider_with_key_when_pull_then_ok_for_inspection() throws Exception {
        // 停用通道可拉（巡检语义——停用拦在装配面 resolve，不拦维护面读上游）
        String providerKey = createProviderAt(upstreamBaseUrl(), "sk-cat-pull-0005", false);

        signedPost(providersRemoteModelsPath(providerKey), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remoteModels", hasSize(3)));
    }

    @Test
    void given_unknown_provider_or_no_signature_when_pull_then_404_or_401() throws Exception {
        signedPost(providersRemoteModelsPath("t-cat-none"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(PROVIDER_NOT_FOUND_CODE));

        mockMvc.perform(post(providersRemoteModelsPath("volcengine")))
                .andExpect(status().isUnauthorized());
    }

    // ---------- fixture ----------

    private String upstreamBaseUrl() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    private static String providersRemoteModelsPath(String providerKey) {
        return PROVIDERS_PATH + "/" + providerKey + "/remote-models";
    }

    /** 假上游回执设置（旋钮式——负例各测自定状态与体）。 */
    private void respondUpstream(int status, String body) {
        upstreamStatus = status;
        upstreamBody = body;
    }

    /** 建通道夹具（指向假上游；enabled 缺省 true）。 */
    private String createProviderAt(String baseUrl, String apiKey) throws Exception {
        return createProviderAt(baseUrl, apiKey, true);
    }

    private String createProviderAt(String baseUrl, String apiKey, boolean enabled)
            throws Exception {
        String providerKey = "t-cat-pull-" + apiKey.substring(apiKey.length() - 4);
        signedPost(PROVIDERS_PATH, """
                {"providerKey": "%s", "baseUrl": "%s", "apiKey": "%s", "enabled": %s}"""
                .formatted(providerKey, baseUrl, apiKey, enabled),
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        return providerKey;
    }

    /** 建档位夹具（挂假上游通道——对照例的库内侧）。 */
    private void createTier(String tierKey, String providerKey, String modelName)
            throws Exception {
        createTier(tierKey, providerKey, modelName, null);
    }

    private void createTier(String tierKey, String providerKey, String modelName,
            String displayName) throws Exception {
        String body = "{\"tierKey\": \"%s\", \"providerKey\": \"%s\", \"modelName\": \"%s\""
                .formatted(tierKey, providerKey, modelName)
                + (displayName == null ? "}" : ", \"displayName\": \"" + displayName + "\"}");
        signedPost(TIERS_PATH, body, OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
    }

    /** 签名 POST（读动作无操作者头；写夹具带操作者）。 */
    private ResultActions signedPost(String path, String body) throws Exception {
        return signedPost(path, body, null, null);
    }

    private ResultActions signedPost(String path, String body, String operatorId,
            String operatorName) throws Exception {
        MockHttpServletRequestBuilder request = post(path);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MockHttpServletRequestBuilder signed =
                BackofficeSignatures.signed(request, path, body == null ? "" : body);
        if (operatorId != null) {
            signed.header("X-User-Id", operatorId);
        }
        if (operatorName != null) {
            signed.header("X-User-Name", operatorName);
        }
        return mockMvc.perform(signed);
    }

    private ResultActions signedPut(String path, String body, String operatorId,
            String operatorName) throws Exception {
        MockHttpServletRequestBuilder signed = BackofficeSignatures.signed(
                put(path).contentType(MediaType.APPLICATION_JSON).content(body), path, body);
        if (operatorId != null) {
            signed.header("X-User-Id", operatorId);
        }
        if (operatorName != null) {
            signed.header("X-User-Name", operatorName);
        }
        return mockMvc.perform(signed);
    }

    private Integer configTraceCount(String agentKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_agent_config_traces WHERE agent_key = ?",
                Integer.class, agentKey);
    }
}

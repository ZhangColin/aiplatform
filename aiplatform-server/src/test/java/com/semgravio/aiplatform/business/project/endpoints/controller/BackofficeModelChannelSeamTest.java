package com.semgravio.aiplatform.business.project.endpoints.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.semgravio.aiplatform.backoffice.BackofficeSeamTest;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.ModelTierAppService;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模型供应商/档位维护面（#367，spec #364-T3）在 {@code #152} seam 上全绿：真
 * 过滤链（签名闸）＋真应用服务＋aiplatform_test 真库。fixture 用 {@code t-}
 * 前缀键（V44 种子行是他测真链路依赖，整面清不可——照 {@code ModelTierAppServiceTest}
 * 口径只清自家行；绑定守卫例写 executor 配置行，随 teardown 归零）。
 *
 * <p>验收面钉死（票 #367 AC）：</p>
 * <ul>
 * <li><b>掩码回显</b>：种子与新建通道的 apiKey 在回执与留痕快照只出尾 4 位——
 * 原始响应体全文无明文 key（泄漏面零）；</li>
 * <li><b>apiKey 写语义</b>：创建收全量值（库内明文）；更新 null/掩码回显形＝
 * 保持现值（round-trip 安全）、全量新值＝换 key；</li>
 * <li><b>留痕＋回滚</b>：每次实际变更一痕（前后全量快照＋操作者＋倒序）、幂等
 * 重写不落痕；回滚＝旧值写回即一次新痕；创建痕 old 全 null、删除痕 new 全 null；</li>
 * <li><b>绑定即时校验</b>：改档写不存在的档位键 400 PRJ_074、停用档 400
 * PRJ_075（三座 modelId/路由强档＋边缘点同拦）；</li>
 * <li><b>缓存指纹</b>：通道换 key 后解析单点取新 key、通道指纹变（下一轮命令
 * 构建即新实例——工厂缓存键含指纹）；</li>
 * <li><b>删除守卫</b>：被引用的档位/通道删除 409（悬空引用不留静默坑）；</li>
 * <li><b>负例＋鉴权</b>：未知寻址 404、字段缺 400、键冲突 409、缺操作者 400
 * PRJ_034、非签名 401。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeModelChannelSeamTest {

    /** 信封数字业务码：PRJ 域码 4 × 1000＋序号（#367 维护面 PRJ_065~075）。 */
    private static final int PROVIDER_NOT_FOUND_CODE = 4065;
    private static final int TIER_NOT_FOUND_CODE = 4066;
    private static final int PROVIDER_EXISTS_CODE = 4067;
    private static final int TIER_EXISTS_CODE = 4068;
    private static final int PROVIDER_FIELDS_REQUIRED_CODE = 4069;
    private static final int TIER_FIELDS_REQUIRED_CODE = 4070;
    private static final int TIER_PROVIDER_UNKNOWN_CODE = 4071;
    private static final int PROVIDER_DELETE_REFERENCED_CODE = 4072;
    private static final int TIER_DELETE_REFERENCED_CODE = 4073;
    private static final int TIER_BINDING_NOT_FOUND_CODE = 4074;
    private static final int TIER_BINDING_DISABLED_CODE = 4075;
    private static final int OPERATOR_REQUIRED_CODE = 4034;

    private static final String OPERATOR_ID = "700212";
    private static final String OPERATOR_NAME = "运营·通道管理员";
    private static final String OPERATOR_ID_2 = "700213";
    private static final String OPERATOR_NAME_2 = "运营·小赵";

    private static final String PROVIDERS_PATH = "/api/backoffice/model-providers";
    private static final String TIERS_PATH = "/api/backoffice/model-tiers";

    /** 测试 key 明文（只在库内与断言局部变量出现，不进任何断言的响应面）。 */
    private static final String LIVE_KEY = "sk-live-abcdef123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 装配读腿（绑定即时校验后的生效面断言）。 */
    @Autowired
    private AgentConfigAppService agentConfigs;

    /** 档位解析单点（缓存指纹断言：换 key 后 resolve 携新值）。 */
    @Autowired
    private ModelTierAppService modelTiers;

    @AfterEach
    void tearDown() {
        // 自家行归零（种子行不动）：绑定守卫例写的 executor 覆盖行一并清（他测
        // 依赖「无行＝全回落」的缺省态）
        jdbcTemplate.update("DELETE FROM prj_agent_config_traces WHERE agent_key = 'executor'");
        jdbcTemplate.update("DELETE FROM prj_agent_configs WHERE agent_key = 'executor'");
        jdbcTemplate.update("DELETE FROM prj_model_tier_traces WHERE tier_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_provider_traces WHERE provider_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_tiers WHERE tier_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_providers WHERE provider_key LIKE 't-%'");
    }

    // ---------- 掩码回显：种子与新建通道的响应面零明文 key ----------

    @Test
    void given_seed_rows_when_list_and_get_then_api_key_masked_everywhere() throws Exception {
        // 清单读面：种子两通道皆掩码形（**** 前缀）、写者 null（种子写入）。volcengine
        // key 随测试环境（env 注入＝真值〔#369 真模型冒烟〕/未注入＝ModelTierSeed 补
        // 测试假值）——明文不泄漏断言取库内现值验，掩码形不钉 fixture 字面
        String volcKey = jdbcTemplate.queryForObject(
                "SELECT api_key FROM prj_model_providers WHERE provider_key = 'volcengine'",
                String.class);
        String listBody = signedRequest(get(PROVIDERS_PATH), PROVIDERS_PATH, null, null, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody)
                .contains("****")
                .doesNotContain(volcKey)
                .doesNotContain("sk-test-deepseek");

        signedRequest(get(PROVIDERS_PATH + "/volcengine"),
                        PROVIDERS_PATH + "/volcengine", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerKey").value("volcengine"))
                .andExpect(jsonPath("$.data.baseUrl").value("https://ark.cn-beijing.volces.com/api/v3"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value(startsWith("****")))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.operatorId").value(nullValue()));

        // 档位清单（V44/V45 种子形状）：语义键/通道/模型名/显示名/启用态
        signedRequest(get(TIERS_PATH), TIERS_PATH, null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tierKey").value("doubao-flagship"))
                .andExpect(jsonPath("$.data[0].displayName").value("豆包旗舰"))
                .andExpect(jsonPath("$.data[1].tierKey").value("flash"))
                .andExpect(jsonPath("$.data[1].providerKey").value("volcengine"))
                .andExpect(jsonPath("$.data[1].modelName").value("deepseek-v4-flash-ga-260731"))
                .andExpect(jsonPath("$.data[1].displayName").value("快模型档"))
                .andExpect(jsonPath("$.data[1].enabled").value(true))
                .andExpect(jsonPath("$.data[2].tierKey").value("pro"))
                .andExpect(jsonPath("$.data[2].displayName").value("强模型档"));

        // 未知寻址：通道/档位各自 404
        signedRequest(get(PROVIDERS_PATH + "/t-none"), PROVIDERS_PATH + "/t-none", null, null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(PROVIDER_NOT_FOUND_CODE));
        signedRequest(get(TIERS_PATH + "/t-none"), TIERS_PATH + "/t-none", null, null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(TIER_NOT_FOUND_CODE));
    }

    // ---------- 通道创建：apiKey 收全量值、明文只在库内、创建即留痕 ----------

    @Test
    void given_signed_create_when_read_back_then_masked_echo_and_plaintext_only_in_db()
            throws Exception {
        signedRequest(post(PROVIDERS_PATH), PROVIDERS_PATH, """
                {"providerKey": "t-ark", "baseUrl": "https://t.example/api/v3",
                 "apiKey": "%s", "enabled": true}""".formatted(LIVE_KEY),
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerKey").value("t-ark"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("****3456"))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.operatorName").value(OPERATOR_NAME));

        // 明文只在库内（唯一正本）；读回与留痕快照只出掩码
        assertThat(plaintextKeyOf("t-ark")).isEqualTo(LIVE_KEY);
        String traceBody = signedRequest(get(PROVIDERS_PATH + "/t-ark/traces"),
                        PROVIDERS_PATH + "/t-ark/traces", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                // 创建痕：old 全 null（行此前不存在）＋操作者
                .andExpect(jsonPath("$.data[0].oldBaseUrl").value(nullValue()))
                .andExpect(jsonPath("$.data[0].oldApiKeyMasked").value(nullValue()))
                .andExpect(jsonPath("$.data[0].oldEnabled").value(nullValue()))
                .andExpect(jsonPath("$.data[0].newBaseUrl").value("https://t.example/api/v3"))
                .andExpect(jsonPath("$.data[0].newApiKeyMasked").value("****3456"))
                .andExpect(jsonPath("$.data[0].newEnabled").value(true))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID))
                .andReturn().getResponse().getContentAsString();
        assertThat(traceBody).doesNotContain(LIVE_KEY);

        // 键名冲突：已存在即 409（改行走 PUT）
        signedRequest(post(PROVIDERS_PATH), PROVIDERS_PATH,
                "{\"providerKey\": \"t-ark\", \"baseUrl\": \"https://other\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(PROVIDER_EXISTS_CODE));
    }

    // ---------- 通道更新：apiKey 保形语义（null/掩码回显形＝保持，全量值＝换 key） ----------

    @Test
    void given_provider_update_when_api_key_facets_then_keep_rotate_and_masked_echo_kept()
            throws Exception {
        createProvider("t-rot", "https://r1.example", "sk-first-key-0001");

        // 换 key（全量新值）：库内换新、回执掩码新尾
        signedRequest(put(PROVIDERS_PATH + "/t-rot"), PROVIDERS_PATH + "/t-rot", """
                {"baseUrl": "https://r1.example", "apiKey": "sk-second-key-0002"}""",
                OPERATOR_ID_2, OPERATOR_NAME_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMasked").value("****0002"))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID_2));
        assertThat(plaintextKeyOf("t-rot")).isEqualTo("sk-second-key-0002");

        // round-trip 安全：不带 apiKey 的 PUT 不丢 key；值面无动＝幂等回执零写入
        // （不落痕、写者不漂移）
        signedRequest(put(PROVIDERS_PATH + "/t-rot"), PROVIDERS_PATH + "/t-rot",
                "{\"baseUrl\": \"https://r1.example\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID_2));
        assertThat(plaintextKeyOf("t-rot")).isEqualTo("sk-second-key-0002");
        assertThat(traceCountOf("t-rot")).isEqualTo(2); // 创建＋换 key，幂等未加痕

        // 掩码回显形直写（读面回显原样 PUT 回）：等同保持现值，不把掩码串当新 key 落库
        signedRequest(put(PROVIDERS_PATH + "/t-rot"), PROVIDERS_PATH + "/t-rot",
                "{\"baseUrl\": \"https://r1.example\", \"apiKey\": \"****0002\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        assertThat(plaintextKeyOf("t-rot")).isEqualTo("sk-second-key-0002");

        // 停用走 enabled=false（清除 key 无写面——下线通道即停用）
        signedRequest(put(PROVIDERS_PATH + "/t-rot"), PROVIDERS_PATH + "/t-rot",
                "{\"baseUrl\": \"https://r1.example\", \"enabled\": false}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        // 换 key 不带 enabled：缺省＝保持现值（停用通道换 key 不被意外复启——
        // 终态字段不因缺省翻转，显式 true/false 才动）
        signedRequest(put(PROVIDERS_PATH + "/t-rot"), PROVIDERS_PATH + "/t-rot",
                "{\"baseUrl\": \"https://r1.example\", \"apiKey\": \"sk-third-key-0003\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMasked").value("****0003"))
                .andExpect(jsonPath("$.data.enabled").value(false));

        // baseUrl 必填（维护面无部分更新语义）：缺失/空白即 400
        signedRequest(put(PROVIDERS_PATH + "/t-rot"), PROVIDERS_PATH + "/t-rot",
                "{\"apiKey\": \"sk-any\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PROVIDER_FIELDS_REQUIRED_CODE));
        signedRequest(put(PROVIDERS_PATH + "/t-none"), PROVIDERS_PATH + "/t-none",
                "{\"baseUrl\": \"https://x\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(PROVIDER_NOT_FOUND_CODE));
    }

    // ---------- 通道留痕：前后快照＋操作者＋倒序；回滚＝旧值写回即新痕 ----------

    @Test
    void given_provider_change_sequence_when_traces_then_snapshots_newest_first_and_rollback()
            throws Exception {
        createProvider("t-hist", "https://h1.example", "sk-hist-1111");
        // 变更二：换 baseUrl（保 key）；变更三：回滚＝痕旧值写回（一次新变更留新痕）
        putProvider("t-hist", "{\"baseUrl\": \"https://h2.example\"}", OPERATOR_ID_2, OPERATOR_NAME_2);
        putProvider("t-hist", "{\"baseUrl\": \"https://h1.example\"}", OPERATOR_ID, OPERATOR_NAME);

        signedRequest(get(PROVIDERS_PATH + "/t-hist/traces"),
                        PROVIDERS_PATH + "/t-hist/traces", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                // 痕三（最近）：回滚——old=h2，new=h1（key 全程保持，掩码同尾）
                .andExpect(jsonPath("$.data[0].oldBaseUrl").value("https://h2.example"))
                .andExpect(jsonPath("$.data[0].newBaseUrl").value("https://h1.example"))
                .andExpect(jsonPath("$.data[0].oldApiKeyMasked").value("****1111"))
                .andExpect(jsonPath("$.data[0].newApiKeyMasked").value("****1111"))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID))
                // 痕二：翻新——old=h1，new=h2
                .andExpect(jsonPath("$.data[1].oldBaseUrl").value("https://h1.example"))
                .andExpect(jsonPath("$.data[1].newBaseUrl").value("https://h2.example"))
                .andExpect(jsonPath("$.data[1].operatorId").value(OPERATOR_ID_2))
                .andExpect(jsonPath("$.data[1].operatorName").value(OPERATOR_NAME_2))
                // 痕一（最早）：创建——old 全 null
                .andExpect(jsonPath("$.data[2].oldBaseUrl").value(nullValue()))
                .andExpect(jsonPath("$.data[2].newBaseUrl").value("https://h1.example"))
                .andExpect(jsonPath("$.data[2].operatorId").value(OPERATOR_ID));
    }

    // ---------- 删除守卫：被引用即 409（不留悬空引用）；删成留痕 new 全 null ----------

    @Test
    void given_delete_when_referenced_then_409_and_after_unbind_cascade_ok() throws Exception {
        createProvider("t-del", "https://d.example", "sk-del-4444");
        createTier("t-bound", "t-del", "some-model-v1");

        // 通道被档位行引用：409（先删改档位行）
        signedRequest(delete(PROVIDERS_PATH + "/t-del"), PROVIDERS_PATH + "/t-del", null,
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(PROVIDER_DELETE_REFERENCED_CODE));

        // 档位被智能体运营配置绑定：409（绑定守卫例顺带产线——PUT executor 档位）
        putAgentConfig("executor", "{\"modelId\": \"t-bound\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        signedRequest(delete(TIERS_PATH + "/t-bound"), TIERS_PATH + "/t-bound", null,
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(TIER_DELETE_REFERENCED_CODE));

        // 解绑（清空回落缺省）→ 档位删成（留痕 new 全 null）→ 通道无引用随删
        putAgentConfig("executor", "{}", OPERATOR_ID, OPERATOR_NAME).andExpect(status().isOk());
        signedRequest(delete(TIERS_PATH + "/t-bound"), TIERS_PATH + "/t-bound", null,
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        signedRequest(get(TIERS_PATH + "/t-bound"), TIERS_PATH + "/t-bound", null, null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(TIER_NOT_FOUND_CODE));
        signedRequest(get(TIERS_PATH + "/t-bound/traces"), TIERS_PATH + "/t-bound/traces",
                null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                // 删除痕：new 全 null（行已不存在）、old＝删除前快照——留痕跨行存活
                .andExpect(jsonPath("$.data[0].newProviderKey").value(nullValue()))
                .andExpect(jsonPath("$.data[0].newModelName").value(nullValue()))
                .andExpect(jsonPath("$.data[0].newEnabled").value(nullValue()))
                .andExpect(jsonPath("$.data[0].oldProviderKey").value("t-del"))
                .andExpect(jsonPath("$.data[0].oldModelName").value("some-model-v1"))
                .andExpect(jsonPath("$.data[0].oldEnabled").value(true))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID));

        signedRequest(delete(PROVIDERS_PATH + "/t-del"), PROVIDERS_PATH + "/t-del", null,
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        signedRequest(get(PROVIDERS_PATH + "/t-del"), PROVIDERS_PATH + "/t-del", null, null, null)
                .andExpect(status().isNotFound());
    }

    // ---------- 档位 CRUD：字段守卫＋所属通道守卫＋冲突＋显示名 ----------

    @Test
    void given_tier_crud_when_fields_and_references_then_validated() throws Exception {
        createProvider("t-chan", "https://c.example", "sk-chan-7777");

        // 所属通道不存在（FK 裸错的读面化）：400 带通道键
        signedRequest(post(TIERS_PATH), TIERS_PATH, """
                {"tierKey": "t-x", "providerKey": "t-nochan", "modelName": "m"}""",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TIER_PROVIDER_UNKNOWN_CODE))
                .andExpect(jsonPath("$.message").value(containsString("t-nochan")));

        // 字段缺（modelName 空白＝缺）：400
        signedRequest(post(TIERS_PATH), TIERS_PATH,
                "{\"tierKey\": \"t-x\", \"providerKey\": \"t-chan\", \"modelName\": \"  \"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TIER_FIELDS_REQUIRED_CODE));

        // 建行即留痕（old 全 null）＋显示名落库
        signedRequest(post(TIERS_PATH), TIERS_PATH, """
                {"tierKey": "t-fast", "providerKey": "t-chan", "modelName": "m-v1",
                 "displayName": "测试快档"}""",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tierKey").value("t-fast"))
                .andExpect(jsonPath("$.data.providerKey").value("t-chan"))
                .andExpect(jsonPath("$.data.modelName").value("m-v1"))
                .andExpect(jsonPath("$.data.displayName").value("测试快档"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID));

        // 键冲突：409（改行走 PUT）
        signedRequest(post(TIERS_PATH), TIERS_PATH,
                "{\"tierKey\": \"t-fast\", \"providerKey\": \"t-chan\", \"modelName\": \"m\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(TIER_EXISTS_CODE));

        // 更新：换模型名＋清显示名（null＝回落语义键）；幂等重写不加痕
        signedRequest(put(TIERS_PATH + "/t-fast"), TIERS_PATH + "/t-fast", """
                {"providerKey": "t-chan", "modelName": "m-v2", "displayName": null}""",
                OPERATOR_ID_2, OPERATOR_NAME_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelName").value("m-v2"))
                .andExpect(jsonPath("$.data.displayName").value(nullValue()))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID_2));
        signedRequest(get(TIERS_PATH + "/t-fast/traces"), TIERS_PATH + "/t-fast/traces",
                null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].oldModelName").value("m-v1"))
                .andExpect(jsonPath("$.data[0].newModelName").value("m-v2"))
                .andExpect(jsonPath("$.data[0].oldDisplayName").value("测试快档"))
                .andExpect(jsonPath("$.data[0].newDisplayName").value(nullValue()))
                .andExpect(jsonPath("$.data[1].oldModelName").value(nullValue()));

        // 更新未知档位：404；更新引用未知通道：400
        signedRequest(put(TIERS_PATH + "/t-none"), TIERS_PATH + "/t-none",
                "{\"providerKey\": \"t-chan\", \"modelName\": \"m\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(TIER_NOT_FOUND_CODE));
        signedRequest(put(TIERS_PATH + "/t-fast"), TIERS_PATH + "/t-fast",
                "{\"providerKey\": \"t-nochan\", \"modelName\": \"m\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TIER_PROVIDER_UNKNOWN_CODE));

        // enabled 缺省＝保持现值：显式停用后，不带 enabled 的换模型名不意外复启
        signedRequest(put(TIERS_PATH + "/t-fast"), TIERS_PATH + "/t-fast",
                "{\"providerKey\": \"t-chan\", \"modelName\": \"m-v2\", \"enabled\": false}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        signedRequest(put(TIERS_PATH + "/t-fast"), TIERS_PATH + "/t-fast",
                "{\"providerKey\": \"t-chan\", \"modelName\": \"m-v3\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelName").value("m-v3"))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    // ---------- 绑定即时校验（AC）：三座/路由强档/边缘点改档写错值即拒 ----------

    @Test
    void given_binding_when_agent_config_put_unknown_or_disabled_tier_then_rejected()
            throws Exception {
        // 缺行档位键：三座 modelId 与路由强档、边缘点档位三面同拦
        putAgentConfig("executor", "{\"modelId\": \"t-nope\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TIER_BINDING_NOT_FOUND_CODE))
                .andExpect(jsonPath("$.message").value(containsString("t-nope")));
        putAgentConfig("executor", "{\"routingStrongModelId\": \"t-nope\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TIER_BINDING_NOT_FOUND_CODE));
        putAgentConfig("edge/naming", "{\"modelId\": \"t-nope\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TIER_BINDING_NOT_FOUND_CODE));

        // 停用档：不可作新绑定（既有绑定装配面报 PRJ_061 是另一腿）
        createProvider("t-offc", "https://o.example", "sk-off-9999");
        createTier("t-off", "t-offc", "off-model", false);
        putAgentConfig("main", "{\"modelId\": \"t-off\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TIER_BINDING_DISABLED_CODE))
                .andExpect(jsonPath("$.message").value(containsString("t-off")));

        // 清空（null＝回落缺省）不校验（无行语境＝幂等回执零写入）；合法新档
        // （建行即绑得上）放行且装配读面跟上
        putAgentConfig("executor", "{\"modelId\": null}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").value(AgentProfile.EXECUTOR.modelId()));
        createTier("t-live", "t-offc", "live-model");
        putAgentConfig("executor", "{\"modelId\": \"t-live\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").value("t-live"))
                .andExpect(jsonPath("$.data.modelIdOverridden").value(true));
        assertThat(agentConfigs.effectiveOf(AgentProfile.EXECUTOR).modelId()).isEqualTo("t-live");

        // 拒绝零写入：负例不留痕不落行（executor 痕只有 t-live 合法一写）
        assertThat(configTraceCount("executor")).isEqualTo(1);
    }

    // ---------- 缓存指纹（AC）：通道换 key 后解析携新值、指纹变＝新实例键 ----------

    @Test
    void given_provider_key_rotation_when_resolve_then_new_key_and_new_fingerprint()
            throws Exception {
        createProvider("t-fp", "https://fp.example/api/v3", "sk-fp-first-aaaa");
        createTier("t-fp-flash", "t-fp", "fp-model-1");

        ModelTierAppService.ResolvedModel before = modelTiers.resolve("t-fp-flash");
        assertThat(before.apiKey()).isEqualTo("sk-fp-first-aaaa");
        assertThat(before.modelString()).isEqualTo("t-fp:fp-model-1");

        // 换 key（后台维护面真链路）：下一轮解析即携新 key、通道指纹变——模型
        // 实例缓存键含指纹（工厂面已钉），指纹变＝新实例＝「改配置即生效」
        putProvider("t-fp", "{\"baseUrl\": \"https://fp.example/api/v3\", "
                + "\"apiKey\": \"sk-fp-second-bbbb\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        ModelTierAppService.ResolvedModel after = modelTiers.resolve("t-fp-flash");
        assertThat(after.apiKey()).isEqualTo("sk-fp-second-bbbb");
        assertThat(after.channel().fingerprint()).isNotEqualTo(before.channel().fingerprint());
        assertThat(after.channel().fingerprint()).doesNotContain("sk-fp-second-bbbb");

        // 换 baseUrl 同理（地址变更＝新指纹）
        putProvider("t-fp", "{\"baseUrl\": \"https://fp2.example/api/v3\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        assertThat(modelTiers.resolve("t-fp-flash").baseUrl()).isEqualTo("https://fp2.example/api/v3");
        assertThat(modelTiers.resolve("t-fp-flash").channel().fingerprint())
                .isNotEqualTo(after.channel().fingerprint());
    }

    // ---------- 鉴权与操作者 ----------

    @Test
    void given_no_signature_or_missing_operator_when_write_then_401_or_400() throws Exception {
        // 非签名：读面与写面全 401
        mockMvc.perform(get(PROVIDERS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
        mockMvc.perform(post(TIERS_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierKey\": \"t-x\"}"))
                .andExpect(status().isUnauthorized());

        // 缺操作者：写操作必留痕，缺头 400（零写入）
        signedRequest(post(PROVIDERS_PATH), PROVIDERS_PATH,
                "{\"providerKey\": \"t-op\", \"baseUrl\": \"https://op.example\"}", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(OPERATOR_REQUIRED_CODE));
        assertThat(providerRowCount("t-op")).isZero();
    }

    // ---------- 夹具 ----------

    /** 签名请求（读面 operator 传 null；写面写者透传头）。 */
    private ResultActions signedRequest(MockHttpServletRequestBuilder builder, String path,
            String body, String operatorId, String operatorName) throws Exception {
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MockHttpServletRequestBuilder signed =
                BackofficeSignatures.signed(builder, path, body == null ? "" : body);
        if (operatorId != null) {
            signed.header("X-User-Id", operatorId);
        }
        if (operatorName != null) {
            signed.header("X-User-Name", operatorName);
        }
        return mockMvc.perform(signed);
    }

    /** 建通道夹具（键 t- 前缀，teardown 归零）。 */
    private void createProvider(String providerKey, String baseUrl, String apiKey) throws Exception {
        signedRequest(post(PROVIDERS_PATH), PROVIDERS_PATH,
                "{\"providerKey\": \"%s\", \"baseUrl\": \"%s\", \"apiKey\": \"%s\"}"
                        .formatted(providerKey, baseUrl, apiKey),
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
    }

    /** 建档位夹具（enabled 缺省 true）。 */
    private void createTier(String tierKey, String providerKey, String modelName)
            throws Exception {
        signedRequest(post(TIERS_PATH), TIERS_PATH,
                "{\"tierKey\": \"%s\", \"providerKey\": \"%s\", \"modelName\": \"%s\"}"
                        .formatted(tierKey, providerKey, modelName),
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
    }

    /** 建停用档位夹具（绑定校验负例用）。 */
    private void createTier(String tierKey, String providerKey, String modelName, boolean enabled)
            throws Exception {
        signedRequest(post(TIERS_PATH), TIERS_PATH,
                "{\"tierKey\": \"%s\", \"providerKey\": \"%s\", \"modelName\": \"%s\", \"enabled\": %s}"
                        .formatted(tierKey, providerKey, modelName, enabled),
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
    }

    /** 通道 PUT 直发（JSON 体原样）。 */
    private ResultActions putProvider(String providerKey, String body,
            String operatorId, String operatorName) throws Exception {
        String path = PROVIDERS_PATH + "/" + providerKey;
        return signedRequest(put(path), path, body, operatorId, operatorName);
    }

    /** agent-configs 面 PUT（绑定校验跨面真链路：三座与边缘点同面）。 */
    private ResultActions putAgentConfig(String keySuffix, String body,
            String operatorId, String operatorName) throws Exception {
        String path = "/api/backoffice/agent-configs/" + keySuffix;
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

    private String plaintextKeyOf(String providerKey) {
        return jdbcTemplate.queryForObject(
                "SELECT api_key FROM prj_model_providers WHERE provider_key = ?",
                String.class, providerKey);
    }

    private Integer traceCountOf(String providerKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_model_provider_traces WHERE provider_key = ?",
                Integer.class, providerKey);
    }

    private Integer providerRowCount(String providerKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_model_providers WHERE provider_key = ?",
                Integer.class, providerKey);
    }

    private Integer configTraceCount(String agentKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_agent_config_traces WHERE agent_key = ?",
                Integer.class, agentKey);
    }
}

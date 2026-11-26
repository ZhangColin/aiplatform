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
import com.semgravio.aiplatform.business.project.application.ModelConfigAppService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 候选队列维护子资源（#559/T5，spec #552-T5，ADR-0053 ③ 后台写面）在
 * {@code #152} seam 上全绿：真过滤链（签名闸）＋真应用服务＋aiplatform_test
 * 真库。fixture 用 {@code t-} 前缀键（种子行是他测依赖，只清自家行）。
 *
 * <p>验收面钉死（票 #559 AC）：</p>
 * <ul>
 * <li><b>增删/排序/启停</b>：curl 全动词经 REST 面——追加/插位（顺移）、移动
 *     （排序）、行级启停、删除（补位压实）皆队列语义（priority 密集 1..n）；</li>
 * <li><b>留痕快照完整、可回滚</b>：候选身份（通道×实际模型名）为轴的前后快照
 *     ＋操作者；回滚＝旧值写回即一次新变更（不做版本树）；幂等重写零痕；</li>
 * <li><b>镜像不变式</b>：配置行现列恒＝队列头（priority 1）——移动/删头/换头
 *     后配置行跟随（软着陆回退腿不漂移）；</li>
 * <li><b>下一轮命令构建生效</b>：装配动态查库——候选变更后 resolveQueue 即取
 *     新队列（进行中 run 不定格，改配置只影响下一轮命令构建）；</li>
 * <li><b>守卫</b>：候选身份重复 409、通道不存在 400 PRJ_071、最后候选不可删
 *     409、寻址 priority 无行 404、缺操作者 400 PRJ_034。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeModelConfigCandidateSeamTest {

    /** 信封数字业务码：PRJ 域码 4 × 1000＋序号（#559/T5 新码 PRJ_088~092）。 */
    private static final int TIER_NOT_FOUND_CODE = 4066;
    private static final int TIER_PROVIDER_UNKNOWN_CODE = 4071;
    private static final int OPERATOR_REQUIRED_CODE = 4034;
    private static final int CANDIDATE_FIELDS_REQUIRED_CODE = 4088;
    private static final int CANDIDATE_NOT_FOUND_CODE = 4089;
    private static final int CANDIDATE_PRIORITY_INVALID_CODE = 4090;
    private static final int CANDIDATE_DUPLICATE_CODE = 4091;
    private static final int CANDIDATE_LAST_DELETE_FORBIDDEN_CODE = 4092;

    private static final String OPERATOR_ID = "700214";
    private static final String OPERATOR_NAME = "运营·候选队列管理员";
    private static final String OPERATOR_ID_2 = "700215";
    private static final String OPERATOR_NAME_2 = "运营·小钱";

    private static final String CONFIGS_PATH = "/api/backoffice/model-configs";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 配置解析单点（AC：候选变更下一轮命令构建即生效——resolveQueue 断言面）。 */
    @Autowired
    private ModelConfigAppService modelConfigs;

    @AfterEach
    void tearDown() {
        // 自家行归零（种子行不动）：先子后父（候选行 FK 指配置/通道），留痕表
        // 无 FK 但同前缀一并清
        jdbcTemplate.update("DELETE FROM prj_model_config_candidate_traces WHERE config_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_config_traces WHERE config_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_provider_traces WHERE provider_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_config_candidates WHERE config_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_configs WHERE config_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_providers WHERE provider_key LIKE 't-%'");
    }

    // ---------- 增（追加）：队列扩展＋留痕＋下一轮命令构建生效 ----------

    @Test
    void given_tier_with_primary_when_add_backup_then_queue_traced_and_resolvable()
            throws Exception {
        createProvider("t-cp1", "https://cp1.example", "sk-cp1-0001");
        createProvider("t-cp2", "https://cp2.example", "sk-cp2-0002");
        createConfig("t-cq", "t-cp1", "cq-model-1");

        // 建档即镜像首选候选（#556）：读面单行 priority 1
        signedRequest(get(candidatesPath("t-cq")), candidatesPath("t-cq"), null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].priority").value(1))
                .andExpect(jsonPath("$.data[0].providerKey").value("t-cp1"))
                .andExpect(jsonPath("$.data[0].modelName").value("cq-model-1"))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID));

        // 增备选（追加缺省队尾）：回执＝整队列（增删/排序/启停的回显统一面）
        signedRequest(post(candidatesPath("t-cq")), candidatesPath("t-cq"), """
                {"providerKey": "t-cp2", "modelName": "cq-model-2"}""",
                OPERATOR_ID_2, OPERATOR_NAME_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[1].priority").value(2))
                .andExpect(jsonPath("$.data[1].providerKey").value("t-cp2"))
                .andExpect(jsonPath("$.data[1].modelName").value("cq-model-2"))
                .andExpect(jsonPath("$.data[1].enabled").value(true))
                .andExpect(jsonPath("$.data[1].operatorId").value(OPERATOR_ID_2));

        // 下一轮命令构建即生效（AC）：装配动态查库——resolveQueue 即取双候选队列
        assertThat(modelConfigs.resolveQueue("t-cq").modelString()).isEqualTo("t-cp1:cq-model-1");
        assertThat(modelConfigs.resolveCandidates("t-cq"))
                .extracting(ModelConfigAppService.ResolvedModel::modelString)
                .containsExactly("t-cp1:cq-model-1", "t-cp2:cq-model-2");

        // 留痕：新增痕（身份轴——old 全 null、new 带队列序）＋操作者
        signedRequest(get(candidatesPath("t-cq") + "/traces"),
                        candidatesPath("t-cq") + "/traces", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].oldPriority").value(nullValue()))
                .andExpect(jsonPath("$.data[0].oldProviderKey").value(nullValue()))
                .andExpect(jsonPath("$.data[0].oldModelName").value(nullValue()))
                .andExpect(jsonPath("$.data[0].oldEnabled").value(nullValue()))
                .andExpect(jsonPath("$.data[0].newPriority").value(2))
                .andExpect(jsonPath("$.data[0].newProviderKey").value("t-cp2"))
                .andExpect(jsonPath("$.data[0].newModelName").value("cq-model-2"))
                .andExpect(jsonPath("$.data[0].newEnabled").value(true))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID_2))
                .andExpect(jsonPath("$.data[0].operatorName").value(OPERATOR_NAME_2));

        // 镜像不变式：队列头（priority 1）不动＝配置行现列不动
        assertThat(tierRowPointing("t-cq")).isEqualTo("t-cp1:cq-model-1");

        // 字段缺负例：providerKey/modelName 必填（PRJ_088）
        signedRequest(post(candidatesPath("t-cq")), candidatesPath("t-cq"),
                "{\"providerKey\": \"t-cp2\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CANDIDATE_FIELDS_REQUIRED_CODE));
        // 通道不存在（PRJ_071 同码——FK 裸错的读面化）
        signedRequest(post(candidatesPath("t-cq")), candidatesPath("t-cq"),
                "{\"providerKey\": \"t-none\", \"modelName\": \"m\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TIER_PROVIDER_UNKNOWN_CODE));
        // 候选身份重复（通道×实际模型名已在队列）＝409：同故障单元无备胎价值
        signedRequest(post(candidatesPath("t-cq")), candidatesPath("t-cq"),
                "{\"providerKey\": \"t-cp2\", \"modelName\": \"cq-model-2\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CANDIDATE_DUPLICATE_CODE));
        // 配置不存在：404 PRJ_066
        signedRequest(post(candidatesPath("t-none")), candidatesPath("t-none"),
                "{\"providerKey\": \"t-cp1\", \"modelName\": \"m\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(TIER_NOT_FOUND_CODE));
        // 缺操作者：400 PRJ_034（零写入）
        signedRequest(post(candidatesPath("t-cq")), candidatesPath("t-cq"),
                "{\"providerKey\": \"t-cp1\", \"modelName\": \"m3\"}", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(OPERATOR_REQUIRED_CODE));
        assertThat(candidateRowCount("t-cq")).isEqualTo(2);
    }

    // ---------- 启停/换指向/移动（排序）：缺省保持＋镜像跟随＋幂等 ----------

    @Test
    void given_two_candidates_when_update_then_disable_repoint_move_and_mirror_follows()
            throws Exception {
        createProvider("t-up1", "https://up1.example", "sk-up1-0011");
        createProvider("t-up2", "https://up2.example", "sk-up2-0022");
        createConfig("t-uq", "t-up1", "uq-model-1");
        addCandidate("t-uq", "t-up2", "uq-model-2");

        // 启停（行级）：缺省保持语义——只携 enabled 即可，不必重报指向
        signedRequest(put(candidatesPath("t-uq") + "/2"), candidatesPath("t-uq") + "/2",
                "{\"enabled\": false}", OPERATOR_ID_2, OPERATOR_NAME_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[1].priority").value(2))
                .andExpect(jsonPath("$.data[1].enabled").value(false))
                .andExpect(jsonPath("$.data[1].operatorId").value(OPERATOR_ID_2));
        // 装配面即生效：停用候选读面剔除（resolveQueue 单元素）、位次不动
        assertThat(modelConfigs.resolveCandidates("t-uq"))
                .extracting(ModelConfigAppService.ResolvedModel::modelString)
                .containsExactly("t-up1:uq-model-1");
        assertThat(candidateModelOf("t-uq", 2)).isEqualTo("t-up2:uq-model-2");

        // 留痕（倒序最近先）：痕一＝启停（同身份 old p2 true → new p2 false）、
        // 痕二＝备选新增（old 全 null）
        signedRequest(get(candidatesPath("t-uq") + "/traces"),
                        candidatesPath("t-uq") + "/traces", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].oldPriority").value(2))
                .andExpect(jsonPath("$.data[0].oldProviderKey").value("t-up2"))
                .andExpect(jsonPath("$.data[0].oldModelName").value("uq-model-2"))
                .andExpect(jsonPath("$.data[0].oldEnabled").value(true))
                .andExpect(jsonPath("$.data[0].newPriority").value(2))
                .andExpect(jsonPath("$.data[0].newProviderKey").value("t-up2"))
                .andExpect(jsonPath("$.data[0].newEnabled").value(false))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID_2))
                .andExpect(jsonPath("$.data[1].oldPriority").value(nullValue()))
                .andExpect(jsonPath("$.data[1].newModelName").value("uq-model-2"));

        // 幂等：同值重写零痕（启停/指向全保持）
        int tracesBefore = candidateTraceCount("t-uq");
        signedRequest(put(candidatesPath("t-uq") + "/2"), candidatesPath("t-uq") + "/2",
                "{\"enabled\": false}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        assertThat(candidateTraceCount("t-uq")).isEqualTo(tracesBefore);

        // 换指向（备选模型版本升级）：身份轴留痕＝旧身份删除痕＋新身份新增痕
        signedRequest(put(candidatesPath("t-uq") + "/2"), candidatesPath("t-uq") + "/2",
                "{\"providerKey\": \"t-up1\", \"modelName\": \"uq-model-2b\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].providerKey").value("t-up1"))
                .andExpect(jsonPath("$.data[1].modelName").value("uq-model-2b"));
        assertThat(modelConfigs.resolveCandidates("t-uq"))
                .extracting(ModelConfigAppService.ResolvedModel::modelString)
                // 备选仍是停用态（换指向缺省保持启停）——读面剔除不变
                .containsExactly("t-up1:uq-model-1");
        // 换指向负例：撞首选身份＝409；通道不存在＝400 PRJ_071
        signedRequest(put(candidatesPath("t-uq") + "/2"), candidatesPath("t-uq") + "/2",
                "{\"providerKey\": \"t-up1\", \"modelName\": \"uq-model-1\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CANDIDATE_DUPLICATE_CODE));
        signedRequest(put(candidatesPath("t-uq") + "/2"), candidatesPath("t-uq") + "/2",
                "{\"providerKey\": \"t-none\", \"modelName\": \"x\"}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TIER_PROVIDER_UNKNOWN_CODE));

        // 移动（排序）＋复启用一并（缺省保持语义下各肢独立）：备选上提 priority 1
        // ——区间顺移＋配置行镜像跟随（现列＝队列头）＋resolve 首选切换
        signedRequest(put(candidatesPath("t-uq") + "/2"), candidatesPath("t-uq") + "/2",
                "{\"enabled\": true, \"priority\": 1}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].providerKey").value("t-up1"))
                .andExpect(jsonPath("$.data[0].modelName").value("uq-model-2b"))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[1].modelName").value("uq-model-1"));
        assertThat(tierRowPointing("t-uq")).isEqualTo("t-up1:uq-model-2b");
        assertThat(modelConfigs.resolve("t-uq").modelString()).isEqualTo("t-up1:uq-model-2b");

        // 寻址/越界负例：priority 无行 404 PRJ_089、移动目标越界 400 PRJ_090
        signedRequest(put(candidatesPath("t-uq") + "/9"), candidatesPath("t-uq") + "/9",
                "{\"enabled\": true}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(CANDIDATE_NOT_FOUND_CODE));
        signedRequest(put(candidatesPath("t-uq") + "/1"), candidatesPath("t-uq") + "/1",
                "{\"priority\": 5}", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CANDIDATE_PRIORITY_INVALID_CODE));
    }

    // ---------- 删（补位压实）＋最后候选守卫＋回滚＝旧值写回即一次新变更 ----------

    @Test
    void given_three_candidates_when_delete_then_compact_last_guard_and_rollback()
            throws Exception {
        createProvider("t-dp1", "https://dp1.example", "sk-dp1-0011");
        createProvider("t-dp2", "https://dp2.example", "sk-dp2-0022");
        createConfig("t-dq", "t-dp1", "dq-model-1");
        addCandidate("t-dq", "t-dp2", "dq-model-2");
        addCandidate("t-dq", "t-dp1", "dq-model-3"); // 队列 [A@1, B@2, C@3]

        // 删中间候选：队列压实（C 3→2 补位，priority 密集无空洞）
        signedRequest(delete(candidatesPath("t-dq") + "/2"), candidatesPath("t-dq") + "/2",
                null, OPERATOR_ID_2, OPERATOR_NAME_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].modelName").value("dq-model-1"))
                .andExpect(jsonPath("$.data[1].priority").value(2))
                .andExpect(jsonPath("$.data[1].modelName").value("dq-model-3"));
        assertThat(candidateRowCount("t-dq")).isEqualTo(2);

        // 留痕（同一编排共享事务时间戳，次序腿＝id 倒序——生成序的逆：删除痕
        // 先、补位痕后）：痕三＝B 删除痕（old p2 → new 全 null）、痕四＝C 补位痕
        // （同身份 p3→p2）；痕一/二＝增 B/增 C（倒序更早）
        signedRequest(get(candidatesPath("t-dq") + "/traces"),
                        candidatesPath("t-dq") + "/traces", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[0].oldPriority").value(2))
                .andExpect(jsonPath("$.data[0].oldProviderKey").value("t-dp2"))
                .andExpect(jsonPath("$.data[0].oldModelName").value("dq-model-2"))
                .andExpect(jsonPath("$.data[0].oldEnabled").value(true))
                .andExpect(jsonPath("$.data[0].newPriority").value(nullValue()))
                .andExpect(jsonPath("$.data[0].newProviderKey").value(nullValue()))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID_2))
                .andExpect(jsonPath("$.data[1].oldPriority").value(3))
                .andExpect(jsonPath("$.data[1].oldModelName").value("dq-model-3"))
                .andExpect(jsonPath("$.data[1].newPriority").value(2))
                .andExpect(jsonPath("$.data[1].newModelName").value("dq-model-3"));

        // 寻址负例：priority 无行 404 PRJ_089（零写入）
        signedRequest(delete(candidatesPath("t-dq") + "/5"), candidatesPath("t-dq") + "/5",
                null, OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(CANDIDATE_NOT_FOUND_CODE));

        // 回滚（AC：旧值写回＝一次新变更）：按 B 删除痕旧值快照重建——原位插回 p2，
        // C 顺移回 3，队列复原 [A, B, C]，且新增一痕（不做版本树）
        signedRequest(post(candidatesPath("t-dq")), candidatesPath("t-dq"), """
                {"providerKey": "t-dp2", "modelName": "dq-model-2", "priority": 2}""",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[1].providerKey").value("t-dp2"))
                .andExpect(jsonPath("$.data[1].modelName").value("dq-model-2"))
                .andExpect(jsonPath("$.data[2].priority").value(3))
                .andExpect(jsonPath("$.data[2].modelName").value("dq-model-3"));
        assertThat(candidateTraceCount("t-dq")).isEqualTo(6); // 增B/增C/删B两痕/回滚两痕
        assertThat(modelConfigs.resolveCandidates("t-dq"))
                .extracting(ModelConfigAppService.ResolvedModel::modelString)
                .containsExactly("t-dp1:dq-model-1", "t-dp2:dq-model-2", "t-dp1:dq-model-3");

        // 最后候选守卫：删到剩一首选再删＝409（整队下线走配置停用，不删空供给定义）
        signedRequest(delete(candidatesPath("t-dq") + "/2"), candidatesPath("t-dq") + "/2",
                null, OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        signedRequest(delete(candidatesPath("t-dq") + "/2"), candidatesPath("t-dq") + "/2",
                null, OPERATOR_ID, OPERATOR_NAME) // 删 B 后 C 压实到 2——再删即剩首选
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
        signedRequest(delete(candidatesPath("t-dq") + "/1"), candidatesPath("t-dq") + "/1",
                null, OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CANDIDATE_LAST_DELETE_FORBIDDEN_CODE));
        assertThat(candidateRowCount("t-dq")).isEqualTo(1);
        assertThat(modelConfigs.resolve("t-dq").modelString()).isEqualTo("t-dp1:dq-model-1");
    }

    // ---------- 软着陆物化：无候选行的存量配置首次编排＝incumbent 入队不被顶掉 ----------

    @Test
    void given_legacy_tier_without_candidates_when_add_then_incumbent_materialized_first()
            throws Exception {
        createProvider("t-lp1", "https://lp1.example", "sk-lp1-0011");
        createProvider("t-lp2", "https://lp2.example", "sk-lp2-0022");
        createConfig("t-lq", "t-lp1", "lq-model-1");
        // 造 pre-T5 存量形态：候选行清空（配置行现列＝隐式首选候选）
        jdbcTemplate.update("DELETE FROM prj_model_config_candidates WHERE config_key = 't-lq'");
        assertThat(candidateRowCount("t-lq")).isZero();

        // 增备选：incumbent 物化为 priority 1（不被新行顶掉）＋新候选追加 p2
        signedRequest(post(candidatesPath("t-lq")), candidatesPath("t-lq"),
                "{\"providerKey\": \"t-lp2\", \"modelName\": \"lq-model-2\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].priority").value(1))
                .andExpect(jsonPath("$.data[0].providerKey").value("t-lp1"))
                .andExpect(jsonPath("$.data[0].modelName").value("lq-model-1"))
                .andExpect(jsonPath("$.data[1].providerKey").value("t-lp2"));
        // 配置行现列不动（镜像＝incumbent）＋装配取双候选队列
        assertThat(tierRowPointing("t-lq")).isEqualTo("t-lp1:lq-model-1");
        assertThat(modelConfigs.resolveCandidates("t-lq"))
                .extracting(ModelConfigAppService.ResolvedModel::modelString)
                .containsExactly("t-lp1:lq-model-1", "t-lp2:lq-model-2");

        // 与 incumbent 同身份的候选＝重复 409（物化身份参与判重）
        signedRequest(post(candidatesPath("t-lq")), candidatesPath("t-lq"),
                "{\"providerKey\": \"t-lp1\", \"modelName\": \"lq-model-1\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CANDIDATE_DUPLICATE_CODE));
        // 物化本身不落痕（表示层收敛非配置变更）：仅新候选一痕
        assertThat(candidateTraceCount("t-lq")).isEqualTo(1);
    }

    // ---------- 双写径不变式：配置直改指向不绕过候选身份判重 ----------

    @Test
    void given_tier_with_backup_when_put_tier_pointing_at_backup_identity_then_409()
            throws Exception {
        createProvider("t-2w1", "https://2w1.example", "sk-2w1-0011");
        createProvider("t-2w2", "https://2w2.example", "sk-2w2-0022");
        createConfig("t-2w", "t-2w1", "2w-model-1");
        addCandidate("t-2w", "t-2w2", "2w-model-2");

        // PUT 配置指向＝备选身份：409 PRJ_091（换首选走候选面 move——排序语义）
        signedRequest(put(CONFIGS_PATH + "/t-2w"), CONFIGS_PATH + "/t-2w",
                "{\"providerKey\": \"t-2w2\", \"modelName\": \"2w-model-2\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CANDIDATE_DUPLICATE_CODE));
        assertThat(tierRowPointing("t-2w")).isEqualTo("t-2w1:2w-model-1"); // 零写入

        // PUT 配置指向＝全新身份（不撞队列）：照常改配置（镜像 priority 1）
        signedRequest(put(CONFIGS_PATH + "/t-2w"), CONFIGS_PATH + "/t-2w",
                "{\"providerKey\": \"t-2w1\", \"modelName\": \"2w-model-1b\"}",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        assertThat(tierRowPointing("t-2w")).isEqualTo("t-2w1:2w-model-1b");
        assertThat(candidateModelOf("t-2w", 1)).isEqualTo("t-2w1:2w-model-1b");
        assertThat(candidateModelOf("t-2w", 2)).isEqualTo("t-2w2:2w-model-2");
    }

    // ---------- 夹具 ----------

    private static String candidatesPath(String configKey) {
        return CONFIGS_PATH + "/" + configKey + "/candidates";
    }

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

    private void createProvider(String providerKey, String baseUrl, String apiKey)
            throws Exception {
        signedRequest(post("/api/backoffice/model-providers"), "/api/backoffice/model-providers",
                "{\"providerKey\": \"%s\", \"baseUrl\": \"%s\", \"apiKey\": \"%s\"}"
                        .formatted(providerKey, baseUrl, apiKey),
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
    }

    private void createConfig(String configKey, String providerKey, String modelName)
            throws Exception {
        signedRequest(post(CONFIGS_PATH), CONFIGS_PATH,
                "{\"configKey\": \"%s\", \"providerKey\": \"%s\", \"modelName\": \"%s\"}"
                        .formatted(configKey, providerKey, modelName),
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
    }

    /** 增候选夹具（追加缺省队尾）。 */
    private void addCandidate(String configKey, String providerKey, String modelName)
            throws Exception {
        signedRequest(post(candidatesPath(configKey)), candidatesPath(configKey),
                "{\"providerKey\": \"%s\", \"modelName\": \"%s\"}"
                        .formatted(providerKey, modelName),
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
    }

    /** 候选行指向读（provider:model@priority）。 */
    private String candidateModelOf(String configKey, int priority) {
        return jdbcTemplate.queryForObject(
                "SELECT provider_key || ':' || model_name FROM prj_model_config_candidates "
                        + "WHERE config_key = ? AND priority = ?",
                String.class, configKey, priority);
    }

    private Integer candidateTraceCount(String configKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_model_config_candidate_traces WHERE config_key = ?",
                Integer.class, configKey);
    }

    /** 配置行现列指向读（镜像不变式断言面）。 */
    private String tierRowPointing(String configKey) {
        return jdbcTemplate.queryForObject(
                "SELECT provider_key || ':' || model_name FROM prj_model_configs WHERE config_key = ?",
                String.class, configKey);
    }

    private Integer candidateRowCount(String configKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_model_config_candidates WHERE config_key = ?",
                Integer.class, configKey);
    }
}

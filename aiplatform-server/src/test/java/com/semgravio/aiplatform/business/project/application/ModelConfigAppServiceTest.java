package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.ModelChannel;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.business.project.application.ModelConfigAppService.ResolvedModel;
import com.cartisan.core.exception.ApplicationException;

/**
 * Seam B（#365／spec #364 测试决议）：配置解析服务层——配置键 → 配置行 → 通道行 →
 * 模型串（前缀:实际模型名）＋创建上下文（baseUrl/apiKey/enableThinking）的解析
 * 单点。只经 {@link ModelConfigAppService#resolve} 公共面观测（外部可观测行为＝解析
 * 结果与报错路径），不打真 API。fixture 用独立键（t- 前缀）不与 V44 种子行
 * （deepseek/flash——其他测试的真链路依赖）串场。
 */
@IntegrationTest
class ModelConfigAppServiceTest {

    private static final String BASE_URL = "https://model.example/api";

    @Autowired
    private ModelConfigAppService modelConfigs;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        // 先子后父：候选行 FK 指配置/通道，清序反了撞外键
        jdbcTemplate.update("DELETE FROM prj_model_config_candidates WHERE config_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_configs WHERE config_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_providers WHERE provider_key LIKE 't-%'");
    }

    @Test
    void given_tier_row_and_enabled_channel_when_resolve_then_model_string_and_context() {
        seedChannel("t-chan", BASE_URL, "sk-chan-test", true);
        seedTier("t-flash", "t-chan", "deepseek-flash", true, null);

        ResolvedModel resolved = modelConfigs.resolve("t-flash");

        assertThat(resolved.modelString()).isEqualTo("t-chan:deepseek-flash");
        assertThat(resolved.baseUrl()).isEqualTo(BASE_URL);
        assertThat(resolved.apiKey()).isEqualTo("sk-chan-test");
        // 深度思考不配置＝显式关（#370：缺省 false 与 #369 活体结论一致——不配置
        // ＝显式关，「深度思考不默认拖慢首 token」承诺不破）
        assertThat(resolved.thinkingEnabled()).isFalse();
        // 命令构建消费面：base 层通道注入件（baseUrl/key/thinking＋vendor——seed 行
        // 未配 vendor，解析归一 provider_key 同值）
        assertThat(resolved.channel())
                .isEqualTo(new ModelChannel(BASE_URL, "sk-chan-test", false, "t-chan"));
    }

    @Test
    void given_tier_row_thinking_enabled_when_resolve_then_context_carries_tier_value() {
        // #370：thinking 终值位从编译常量改配置行——跟配置/模型走（换模型行为
        // 自动跟随、各配置可不同设），装配单点读行值
        seedChannel("t-chan", BASE_URL, "sk-chan-test", true);
        seedTier("t-think", "t-chan", "deepseek-v4-pro", true, true);

        ResolvedModel resolved = modelConfigs.resolve("t-think");

        assertThat(resolved.thinkingEnabled()).isTrue();
        assertThat(resolved.channel())
                .isEqualTo(new ModelChannel(BASE_URL, "sk-chan-test", true, "t-chan"));
        // 开关腿进通道指纹：改开关＝新指纹＝新实例（改配置即生效、进行中 run 不定格）
        assertThat(resolved.channel().fingerprint())
                .isNotEqualTo(new ModelChannel(BASE_URL, "sk-chan-test", false).fingerprint());
    }

    @Test
    void given_missing_tier_when_resolve_then_explicit_error_no_invented_default() {
        seedChannel("t-chan", BASE_URL, "sk-chan-test", true);

        assertThatThrownBy(() -> modelConfigs.resolve("t-nonexistent"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("t-nonexistent");
    }

    @Test
    void given_disabled_tier_when_resolve_then_explicit_error() {
        seedChannel("t-chan", BASE_URL, "sk-chan-test", true);
        seedTier("t-flash", "t-chan", "deepseek-flash", false, null);

        assertThatThrownBy(() -> modelConfigs.resolve("t-flash"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void given_disabled_provider_when_resolve_then_explicit_error_no_silent_rechannel() {
        seedChannel("t-chan", BASE_URL, "sk-chan-test", false);
        seedTier("t-flash", "t-chan", "deepseek-flash", true, null);

        assertThatThrownBy(() -> modelConfigs.resolve("t-flash"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void given_blank_provider_api_key_when_resolve_then_explicit_error_no_env_fallback() {
        seedChannel("t-chan", BASE_URL, null, true);
        seedTier("t-flash", "t-chan", "deepseek-flash", true, null);

        assertThatThrownBy(() -> modelConfigs.resolve("t-flash"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void given_candidate_rows_when_resolve_candidates_then_ordered_queue_first_wins() {
        // ADR-0053 ③：候选表非空＝队列数据源（priority 升序），首候选＝主选——
        // 配置行现列被压过（现列只在候选表空时兜底，见下一用例）
        seedChannel("t-chan", BASE_URL, "sk-chan-test", true);
        seedChannel("t-chan2", "https://model2.example/api", "sk-chan2-test", true);
        seedTier("t-queue", "t-chan2", "config-row-model", true, null);
        seedCandidate("t-queue", 2, "t-chan2", "backup-model");
        seedCandidate("t-queue", 1, "t-chan", "primary-model");

        List<ResolvedModel> queue = modelConfigs.resolveCandidates("t-queue");

        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).modelString()).isEqualTo("t-chan:primary-model");
        assertThat(queue.get(0).apiKey()).isEqualTo("sk-chan-test");
        assertThat(queue.get(1).modelString()).isEqualTo("t-chan2:backup-model");
        assertThat(queue.get(1).apiKey()).isEqualTo("sk-chan2-test");
        // 单点读面（五个 resolve 消费面不变）：resolve＝首候选
        assertThat(modelConfigs.resolve("t-queue").modelString())
                .isEqualTo("t-chan:primary-model");
    }

    @Test
    void given_no_candidate_rows_when_resolve_candidates_then_single_element_from_tier_row() {
        // 软着陆（AC：单候选回退下行为不变）：候选表空 → 配置行现列单元素队列
        seedChannel("t-chan", BASE_URL, "sk-chan-test", true);
        seedTier("t-flash", "t-chan", "deepseek-flash", true, null);

        List<ResolvedModel> queue = modelConfigs.resolveCandidates("t-flash");

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).modelString()).isEqualTo("t-chan:deepseek-flash");
        assertThat(queue.get(0).thinkingEnabled()).isFalse();
    }

    @Test
    void given_candidate_rows_when_resolve_candidates_then_tier_thinking_applies_to_all() {
        // thinking 是配置行为属性（#370）：候选行不带开关列——全队列继承配置行值
        seedChannel("t-chan", BASE_URL, "sk-chan-test", true);
        seedTier("t-queue", "t-chan", "config-row-model", true, true);
        seedCandidate("t-queue", 1, "t-chan", "primary-model");

        List<ResolvedModel> queue = modelConfigs.resolveCandidates("t-queue");

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).thinkingEnabled()).isTrue();
    }

    @Test
    void given_candidate_rows_when_resolve_queue_then_base_shaped_ordered_queue() {
        // #557 底座消费形：配置键 → ModelCandidateQueue（configKey＋有序候选，逐候选
        // 通道注入件携带）——failover 装配面的读腿；首候选与双字段同源
        seedChannel("t-chan", BASE_URL, "sk-chan-test", true);
        seedChannel("t-chan2", "https://model2.example/api", "sk-chan2-test", true);
        seedTier("t-queue", "t-chan", "config-row-model", true, null);
        seedCandidate("t-queue", 2, "t-chan2", "backup-model");
        seedCandidate("t-queue", 1, "t-chan", "primary-model");

        ModelCandidateQueue queue = modelConfigs.resolveQueue("t-queue");

        assertThat(queue.configKey()).isEqualTo("t-queue");
        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.first().modelString()).isEqualTo("t-chan:primary-model");
        assertThat(queue.first().channel().apiKey()).isEqualTo("sk-chan-test");
        assertThat(queue.candidates().get(1).modelString()).isEqualTo("t-chan2:backup-model");
        // 派生双字段单点（命令面以此填 modelString/modelChannel）
        assertThat(queue.modelString()).isEqualTo("t-chan:primary-model");
        assertThat(queue.channel().baseUrl()).isEqualTo(BASE_URL);
        // 整队列指纹：候选序与内容全量入键（任一变＝指纹变）
        String fingerprint = queue.fingerprint();
        assertThat(queue.fingerprint()).isEqualTo(fingerprint);
        assertThat(ModelCandidateQueue.single("t-chan:primary-model",
                queue.first().channel()).fingerprint()).isNotEqualTo(fingerprint);
    }

    @Test
    void given_all_candidates_disabled_when_resolve_then_explicit_error_no_tier_row_fallback() {
        // 全候选停用＝运营显式下线（无静默回落配置现列——与通道停用同 Doctrine）
        seedChannel("t-chan", BASE_URL, "sk-chan-test", true);
        seedTier("t-queue", "t-chan", "config-row-model", true, null);
        seedCandidate("t-queue", 1, "t-chan", "primary-model", false);

        assertThatThrownBy(() -> modelConfigs.resolve("t-queue"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("候选");
    }

    @Test
    void given_backup_candidate_provider_disabled_when_resolve_then_explicit_error() {
        // 读面无静默跳过：任一启用候选的通道坏＝明确报错（候选内运行期故障的
        // 切换是 T3 FailoverModel 的职责，配置态坏走响亮失败）
        seedChannel("t-chan", BASE_URL, "sk-chan-test", true);
        seedChannel("t-chan2", "https://model2.example/api", "sk-chan2-test", false);
        seedTier("t-queue", "t-chan", "config-row-model", true, null);
        seedCandidate("t-queue", 1, "t-chan", "primary-model");
        seedCandidate("t-queue", 2, "t-chan2", "backup-model");

        assertThatThrownBy(() -> modelConfigs.resolveCandidates("t-queue"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void given_v79_seed_rows_when_resolve_then_deepseek_single_channel_shape() {
        // V79 收敛后种子链路（deepseek 单通道 + flash 档候选）——主链路的真实形状
        // 钉死：三座缺省档 flash 经真解析产 deepseek 官方模型串（火山下线收尾，
        // ADR-0053 ④；全新库 V44 种火山→V79 收敛，净效果＝deepseek 无火山依赖）。
        // key 值随测试环境（placeholder 注入＝假值/live 环境注入真值）——断言守
        // 「非空不回落＋明文不入指纹」，不钉 fixture 字面
        ModelConfigSeed.ensureChannelKey(jdbcTemplate);

        ResolvedModel resolved = modelConfigs.resolve("flash");

        assertThat(resolved.modelString()).isEqualTo("deepseek:deepseek-flash");
        assertThat(resolved.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(resolved.apiKey()).isNotBlank();
        assertThat(resolved.channel().fingerprint()).doesNotContain(resolved.apiKey());
        // 候选种子：flash 一现役候选（主选单元素队列——与配置行现列同值）
        assertThat(modelConfigs.resolveCandidates("flash"))
                .extracting(ResolvedModel::modelString)
                .containsExactly("deepseek:deepseek-flash");
    }

    @Test
    void given_provider_vendor_when_resolve_then_channel_and_queue_carry_vendor() {
        // ADR-0053 ②：vendor 随通道行走解析产物——通道注入件携 vendor 进计量归因
        // （按账号 provider＋供应商 vendor 双维度）；主备两候选各自带各自通道行的
        // vendor（failover 切候选后计量随实际命中通道行归因）
        seedChannel("t-ds2", "t-vendor", "https://api.deepseek.com", "sk-ds2", true);
        seedChannel("t-ds3", "t-vendor", "https://api.deepseek.com", "sk-ds3", true);
        seedTier("t-queue", "t-ds2", "primary-model", true, null);
        seedCandidate("t-queue", 1, "t-ds2", "primary-model");
        seedCandidate("t-queue", 2, "t-ds3", "backup-model");

        ResolvedModel resolved = modelConfigs.resolve("t-queue");
        ModelCandidateQueue queue = modelConfigs.resolveQueue("t-queue");

        assertThat(resolved.vendor()).isEqualTo("t-vendor");
        assertThat(resolved.channel().vendor()).isEqualTo("t-vendor");
        assertThat(queue.candidates()).extracting(c -> c.channel().vendor())
                .containsExactly("t-vendor", "t-vendor");
    }

    @Test
    void given_provider_vendor_unconfigured_when_resolve_then_falls_back_to_provider_key() {
        // pre-T5 写面建的行无 vendor（列可空）→ 解析归一 vendor＝provider_key（V79
        // 历史同值回填同款语义，软着陆——写面带 vendor 受控值随 #559）
        seedChannel("t-novendor", null, BASE_URL, "sk-chan-test", true);
        seedTier("t-flash", "t-novendor", "deepseek-flash", true, null);

        ResolvedModel resolved = modelConfigs.resolve("t-flash");

        assertThat(resolved.vendor()).isEqualTo("t-novendor");
        assertThat(resolved.channel().vendor()).isEqualTo("t-novendor");
    }

    @Test
    void given_vendor_change_when_channel_fingerprint_then_new_instance_key() {
        // vendor 进通道指纹（生效配置语义：改 vendor＝新实例，进行中 run 不定格
        // ——缓存的 MeteredModel 不滞留旧 vendor 归因）
        ModelChannel vendorA = new ModelChannel(BASE_URL, "sk-a", false, "t-vendor");
        ModelChannel vendorB = new ModelChannel(BASE_URL, "sk-a", false, "t-vendor-x");

        assertThat(vendorA.fingerprint()).isNotEqualTo(vendorB.fingerprint());
    }

    private void seedChannel(String providerKey, String baseUrl, String apiKey, boolean enabled) {
        seedChannel(providerKey, null, baseUrl, apiKey, enabled);
    }

    private void seedChannel(String providerKey, String vendor, String baseUrl, String apiKey,
            boolean enabled) {
        jdbcTemplate.update(
                "INSERT INTO prj_model_providers (provider_key, vendor, base_url, api_key, enabled) "
                        + "VALUES (?, ?, ?, ?, ?)",
                providerKey, vendor, baseUrl, apiKey, enabled);
    }

    /** 建配置夹具（thinkingEnabled null＝不写列——走库缺省，缺省腿断言语境）。 */
    private void seedTier(String configKey, String providerKey, String modelName, boolean enabled,
            Boolean thinkingEnabled) {
        if (thinkingEnabled == null) {
            jdbcTemplate.update(
                    "INSERT INTO prj_model_configs (config_key, provider_key, model_name, enabled) "
                            + "VALUES (?, ?, ?, ?)",
                    configKey, providerKey, modelName, enabled);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO prj_model_configs (config_key, provider_key, model_name, enabled, "
                        + "thinking_enabled) VALUES (?, ?, ?, ?, ?)",
                configKey, providerKey, modelName, enabled, thinkingEnabled);
    }

    /** 建候选行夹具（enabled 缺省 true）。 */
    private void seedCandidate(String configKey, int priority, String providerKey, String modelName) {
        seedCandidate(configKey, priority, providerKey, modelName, true);
    }

    private void seedCandidate(String configKey, int priority, String providerKey, String modelName,
            boolean enabled) {
        jdbcTemplate.update(
                "INSERT INTO prj_model_config_candidates "
                        + "(config_key, priority, provider_key, model_name, enabled) "
                        + "VALUES (?, ?, ?, ?, ?)",
                configKey, priority, providerKey, modelName, enabled);
    }
}

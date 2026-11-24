package com.semgravio.aiplatform.business.project.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.semgravio.aiplatform.base.agentscope.ModelCandidate;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.base.agentscope.ModelChannel;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.ModelProvider;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfig;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigCandidate;
import com.semgravio.aiplatform.business.project.domain.repository.ModelChannelStore;
import com.cartisan.core.exception.ApplicationException;

/**
 * 配置解析服务层（#365，ADR-0031 装配链的业务半边，解析单点；#556 起候选
 * 队列语义，ADR-0053 ③）：绑定配置键 → 配置行 → 有序候选队列（{@code
 * prj_model_config_candidates}，priority 升序；主备＝队列前两名）→ 每候选
 * 通道行 → 模型串（前缀:实际模型名）＋创建上下文（baseUrl/apiKey/
 * enableThinking）。两座智能体（{@code AgentConfigAppService} 生效配置）与
 * 边缘调用点的命令构建一律经本服务解析——供应商/配置/凭证三层配置改一处，全部
 * 引用自动跟上（下一轮命令构建即生效，进行中 run 不定格）。
 *
 * <p><b>队列数据源（软着陆）</b>：配置<b>未配置</b>候选行时回退配置行现列
 * （单元素队列——既有引用零迁移即等价）；配置了候选行则候选表是唯一队列源
 * （含全停用＝显式下线，报错不回落现列）。运行期故障的候选内重试/切换收口在
 * 模型包装层（#557 {@code FailoverModel} 已落地——本读面只做配置态校验）。</p>
 *
 * <p><b>报错语义（无静默回落）</b>：配置缺行、配置停用、无启用候选、任一启用
 * 候选的通道停用/key 空皆明确报错——apiKey 落库为唯一正本，不回落环境变量
 * （换 key 改库即生效，故障一眼可查）。动态查库、不缓存（对话轮本身是秒级
 * LLM 调用，候选级主键 SELECT 的代价可忽略——同 {@code AgentConfigAppService}
 * 口径）。</p>
 *
 * <p>enableThinking 配置行运营配置（#370）：深度思考是模型行为属性，跟配置/
 * 模型走——候选行不自带开关列，全队列继承配置行值；缺省 false＝显式关
 * （#369 活体验收结论「维持 false」——不配置不默认拖慢首 token）。开关
 * 改配置维护面（不发版不重启），本服务每轮命令构建读行值；通道指纹含 thinking
 * 腿，改开关＝新指纹＝新实例（进行中 run 不定格）。volcengine 平台工厂与 SDK
 * 内置 provider 都从创建上下文读该值——装配不留给模型默认。</p>
 */
@Slf4j
@Service
public class ModelConfigAppService {

    private final ModelChannelStore configStore;

    public ModelConfigAppService(ModelChannelStore configStore) {
        this.configStore = configStore;
    }

    /**
     * 首选候选解析（T2 读面契约保留：首候选＝主选）。语义＝{@link #resolveCandidates}
     * 队列首元素；装配面（#557 起）一律走 {@link #resolveQueue}（整队列 failover 面）。
     *
     * @param configKey 配置语义键（绑定面引用值；空＝调用方缺省腿未收编，属编程错误）
     */
    @Transactional(readOnly = true)
    public ResolvedModel resolve(String configKey) {
        return resolveCandidates(configKey).get(0);
    }

    /**
     * 整队列解析的底座消费形（#557，ADR-0053 ③）：配置键 → 候选队列
     * （{@link ModelCandidateQueue}——failover 装配面）。主链调用面
     * （三座对话轮/生成尝试/派发/边缘点）一律经此携队列：多候选＝运行期链式
     * 切换（FailoverModel 收口模型包装层，五个 resolve 面免费获得）、单候选＝
     * 既有装配行为不变（软着陆）。首候选与模型串/通道双字段同源（调用面从
     * {@link ModelCandidateQueue#modelString()}/{@link ModelCandidateQueue#channel()}
     * 派生双字段——构建面单点保证不漂移）。
     */
    @Transactional(readOnly = true)
    public ModelCandidateQueue resolveQueue(String configKey) {
        List<ResolvedModel> queue = resolveCandidates(configKey);
        return new ModelCandidateQueue(configKey, queue.stream()
                .map(ResolvedModel::asCandidate)
                .toList());
    }

    /**
     * 整队列解析（#556 候选队列读面）：配置键 → 启用候选有序列表（priority
     * 升序）。候选行未配置时回退配置行现列单元素（软着陆——单候选路径行为
     * 不变）。每个候选独立校验其通道行（启用态＋key 非空）——任一启用候选
     * 配置态坏＝响亮报错（运行期切换是 FailoverModel 职责，读面无静默跳过）。
     */
    @Transactional(readOnly = true)
    public List<ResolvedModel> resolveCandidates(String configKey) {
        if (configKey == null || configKey.isBlank()) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_NOT_FOUND, "配置键为空");
        }
        ModelConfig config = configStore.findConfig(configKey);
        if (config == null) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_NOT_FOUND, configKey);
        }
        if (!config.enabled()) {
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_DISABLED, configKey);
        }
        List<ModelConfigCandidate> candidates = configStore.findConfigCandidates(configKey);
        // 软着陆：未配置候选行 → 配置行现列单元素队列（现列＝首选候选）
        List<ResolvedModel> queue = candidates.isEmpty()
                ? List.of(resolveCandidate(configKey, config.providerKey(), config.modelName(),
                        config.thinkingEnabled()))
                : candidates.stream()
                        .filter(ModelConfigCandidate::enabled)
                        .map(candidate -> resolveCandidate(configKey, candidate.providerKey(),
                                candidate.modelName(), config.thinkingEnabled()))
                        .toList();
        if (queue.isEmpty()) {
            // 配置了候选行但全停用＝运营显式下线（不静默回落现列——与通道停用同 Doctrine）
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_NO_ENABLED_CANDIDATES, configKey);
        }
        ResolvedModel first = queue.get(0);
        // 装配可证（#366）：配置键 → 实际模型串的单点日志——三座与边缘三点的每次
        // 命令构建/装配经此留迹，后台改配置后的生效链路 grep 可查（key 不入日志）；
        // #556 起带队列深度（首候选即主选，主备＝队列前两名）
        log.info("[model-config] 配置解析 {} → {}（通道 {}，thinking={}，候选 {}/{}）", configKey,
                first.modelString(), first.providerKey(), first.thinkingEnabled(), 1, queue.size());
        return queue;
    }

    /**
     * 单候选解析：通道行校验（缺行 FK 兜底/停用/key 空）＋解析束组装。vendor 归一
     * （ADR-0053 ②）：行有 vendor 用行值（deepseek/deepseek-2 同填 deepseek），
     * 空（pre-T5 写面建的行）回落 provider_key 同值——V79 历史回填同款语义，
     * 解析产物 vendor 恒非空。
     */
    private ResolvedModel resolveCandidate(String configKey, String providerKey, String modelName,
            boolean thinkingEnabled) {
        ModelProvider provider = configStore.findProvider(providerKey);
        if (provider == null) {
            // FK 兜底（配置/候选行的 provider_key 有外键约束，常态不可达——行被并发删的窗口面）
            throw new ApplicationException(ProjectMessage.MODEL_CONFIG_NOT_FOUND,
                    configKey + "（通道行缺失: " + providerKey + "）");
        }
        if (!provider.enabled()) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_DISABLED, provider.providerKey());
        }
        if (provider.apiKey() == null || provider.apiKey().isBlank()) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_KEY_MISSING,
                    provider.providerKey());
        }
        String vendor = ModelChannel.normalizedVendor(provider.providerKey(), provider.vendor());
        return new ResolvedModel(configKey, provider.providerKey(), vendor, modelName,
                provider.baseUrl(), provider.apiKey(), thinkingEnabled);
    }

    /**
     * 生效解析束：配置键、通道键、归一供应商、实际模型名、通道注入面
     * （baseUrl/apiKey/thinking）。
     */
    public record ResolvedModel(String configKey, String providerKey, String vendor, String modelName,
            String baseUrl, String apiKey, boolean thinkingEnabled) {

        /** 对话轨道模型串（前缀:实际模型名——前缀即协议路由）。 */
        public String modelString() {
            return providerKey + ":" + modelName;
        }

        /** base 层通道注入件（随命令透传：创建上下文＋实例缓存键指纹＋vendor 计量腿）。 */
        public ModelChannel channel() {
            return new ModelChannel(baseUrl, apiKey, thinkingEnabled, vendor);
        }

        /** 底座候选元素形（#557 队列装配腿：resolveQueue 映射单点）。 */
        public ModelCandidate asCandidate() {
            return new ModelCandidate(providerKey, modelName, channel());
        }
    }
}

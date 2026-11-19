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
import com.semgravio.aiplatform.business.project.domain.model.ModelTier;
import com.semgravio.aiplatform.business.project.domain.model.ModelTierCandidate;
import com.semgravio.aiplatform.business.project.domain.repository.ModelChannelStore;
import com.cartisan.core.exception.ApplicationException;

/**
 * 档位解析服务层（#365，ADR-0031 装配链的业务半边，解析单点；#556 起候选
 * 队列语义，ADR-0053 ③）：绑定档位键 → 档位行 → 有序候选队列（{@code
 * prj_model_tier_candidates}，priority 升序；主备＝队列前两名）→ 每候选
 * 通道行 → 模型串（前缀:实际模型名）＋创建上下文（baseUrl/apiKey/
 * enableThinking）。三座智能体（{@code AgentConfigAppService} 生效档位）与模型
 * 路由强档的命令构建一律经本服务解析——供应商/档位/凭证三层配置改一处，全部
 * 引用自动跟上（下一轮命令构建即生效，进行中 run 不定格）。
 *
 * <p><b>队列数据源（软着陆）</b>：档位<b>未配置</b>候选行时回退档位行现列
 * （单元素队列——既有引用零迁移即等价）；配置了候选行则候选表是唯一队列源
 * （含全停用＝显式下线，报错不回落现列）。运行期故障的候选内重试/切换收口在
 * 模型包装层（#557 {@code FailoverModel} 已落地——本读面只做配置态校验）。</p>
 *
 * <p><b>报错语义（无静默回落）</b>：档位缺行、档位停用、无启用候选、任一启用
 * 候选的通道停用/key 空皆明确报错——apiKey 落库为唯一正本，不回落环境变量
 * （换 key 改库即生效，故障一眼可查）。动态查库、不缓存（对话轮本身是秒级
 * LLM 调用，候选级主键 SELECT 的代价可忽略——同 {@code AgentConfigAppService}
 * 口径）。</p>
 *
 * <p>enableThinking 档位行运营配置（#370）：深度思考是模型行为属性，跟档位/
 * 模型走——候选行不自带开关列，全队列继承档位行值；缺省 false＝显式关
 * （#369 活体验收结论「维持 false」——不配置不默认拖慢首 token）。开关
 * 改档位维护面（不发版不重启），本服务每轮命令构建读行值；通道指纹含 thinking
 * 腿，改开关＝新指纹＝新实例（进行中 run 不定格）。volcengine 平台工厂与 SDK
 * 内置 provider 都从创建上下文读该值——装配不留给模型默认。</p>
 */
@Slf4j
@Service
public class ModelTierAppService {

    private final ModelChannelStore tierStore;

    public ModelTierAppService(ModelChannelStore tierStore) {
        this.tierStore = tierStore;
    }

    /**
     * 首选候选解析（T2 读面契约保留：首候选＝主选）。语义＝{@link #resolveCandidates}
     * 队列首元素；装配面（#557 起）一律走 {@link #resolveQueue}（整队列 failover 面）。
     *
     * @param tierKey 档位语义键（绑定面引用值；空＝调用方缺省腿未收编，属编程错误）
     */
    @Transactional(readOnly = true)
    public ResolvedModel resolve(String tierKey) {
        return resolveCandidates(tierKey).get(0);
    }

    /**
     * 整队列解析的底座消费形（#557，ADR-0053 ③）：档位键 → 候选队列
     * （{@link ModelCandidateQueue}——failover 装配面）。主链调用面
     * （三座对话轮/生成尝试/派发/边缘点）一律经此携队列：多候选＝运行期链式
     * 切换（FailoverModel 收口模型包装层，五个 resolve 面免费获得）、单候选＝
     * 既有装配行为不变（软着陆）。首候选与模型串/通道双字段同源（调用面从
     * {@link ModelCandidateQueue#modelString()}/{@link ModelCandidateQueue#channel()}
     * 派生双字段——构建面单点保证不漂移）。
     */
    @Transactional(readOnly = true)
    public ModelCandidateQueue resolveQueue(String tierKey) {
        List<ResolvedModel> queue = resolveCandidates(tierKey);
        return new ModelCandidateQueue(tierKey, queue.stream()
                .map(ResolvedModel::asCandidate)
                .toList());
    }

    /**
     * 整队列解析（#556 候选队列读面）：档位键 → 启用候选有序列表（priority
     * 升序）。候选行未配置时回退档位行现列单元素（软着陆——单候选路径行为
     * 不变）。每个候选独立校验其通道行（启用态＋key 非空）——任一启用候选
     * 配置态坏＝响亮报错（运行期切换是 FailoverModel 职责，读面无静默跳过）。
     */
    @Transactional(readOnly = true)
    public List<ResolvedModel> resolveCandidates(String tierKey) {
        if (tierKey == null || tierKey.isBlank()) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_NOT_FOUND, "档位键为空");
        }
        ModelTier tier = tierStore.findTier(tierKey);
        if (tier == null) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_NOT_FOUND, tierKey);
        }
        if (!tier.enabled()) {
            throw new ApplicationException(ProjectMessage.MODEL_TIER_DISABLED, tierKey);
        }
        List<ModelTierCandidate> candidates = tierStore.findTierCandidates(tierKey);
        // 软着陆：未配置候选行 → 档位行现列单元素队列（现列＝首选候选）
        List<ResolvedModel> queue = candidates.isEmpty()
                ? List.of(resolveCandidate(tierKey, tier.providerKey(), tier.modelName(),
                        tier.thinkingEnabled()))
                : candidates.stream()
                        .filter(ModelTierCandidate::enabled)
                        .map(candidate -> resolveCandidate(tierKey, candidate.providerKey(),
                                candidate.modelName(), tier.thinkingEnabled()))
                        .toList();
        if (queue.isEmpty()) {
            // 配置了候选行但全停用＝运营显式下线（不静默回落现列——与通道停用同 Doctrine）
            throw new ApplicationException(ProjectMessage.MODEL_TIER_NO_ENABLED_CANDIDATES, tierKey);
        }
        ResolvedModel first = queue.get(0);
        // 装配可证（#366）：档位键 → 实际模型串的单点日志——三座与边缘四点的每次
        // 命令构建/装配经此留迹，后台改档后的生效链路 grep 可查（key 不入日志）；
        // #556 起带队列深度（首候选即主选，主备＝队列前两名）
        log.info("[model-tier] 档位解析 {} → {}（通道 {}，thinking={}，候选 {}/{}）", tierKey,
                first.modelString(), first.providerKey(), first.thinkingEnabled(), 1, queue.size());
        return queue;
    }

    /**
     * 单候选解析：通道行校验（缺行 FK 兜底/停用/key 空）＋解析束组装。vendor 归一
     * （ADR-0053 ②）：行有 vendor 用行值（deepseek/deepseek-2 同填 deepseek），
     * 空（pre-T5 写面建的行）回落 provider_key 同值——V79 历史回填同款语义，
     * 解析产物 vendor 恒非空。
     */
    private ResolvedModel resolveCandidate(String tierKey, String providerKey, String modelName,
            boolean thinkingEnabled) {
        ModelProvider provider = tierStore.findProvider(providerKey);
        if (provider == null) {
            // FK 兜底（档位/候选行的 provider_key 有外键约束，常态不可达——行被并发删的窗口面）
            throw new ApplicationException(ProjectMessage.MODEL_TIER_NOT_FOUND,
                    tierKey + "（通道行缺失: " + providerKey + "）");
        }
        if (!provider.enabled()) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_DISABLED, provider.providerKey());
        }
        if (provider.apiKey() == null || provider.apiKey().isBlank()) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_KEY_MISSING,
                    provider.providerKey());
        }
        String vendor = ModelChannel.normalizedVendor(provider.providerKey(), provider.vendor());
        return new ResolvedModel(tierKey, provider.providerKey(), vendor, modelName,
                provider.baseUrl(), provider.apiKey(), thinkingEnabled);
    }

    /**
     * 生效解析束：档位键、通道键、归一供应商、实际模型名、通道注入面
     * （baseUrl/apiKey/thinking）。
     */
    public record ResolvedModel(String tierKey, String providerKey, String vendor, String modelName,
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

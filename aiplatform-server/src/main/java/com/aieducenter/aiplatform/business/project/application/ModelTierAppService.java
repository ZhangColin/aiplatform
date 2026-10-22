package com.aieducenter.aiplatform.business.project.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aieducenter.aiplatform.base.agentscope.ModelChannel;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ModelProvider;
import com.aieducenter.aiplatform.business.project.domain.model.ModelTier;
import com.aieducenter.aiplatform.business.project.domain.repository.ModelTierStore;
import com.cartisan.core.exception.ApplicationException;

/**
 * 档位解析服务层（#365，ADR-0031 装配链的业务半边，解析单点）：绑定档位键 →
 * 档位行 → 通道行 → 模型串（前缀:实际模型名）＋创建上下文（baseUrl/apiKey/
 * enableThinking）。三座智能体（{@code AgentConfigAppService} 生效档位）与模型
 * 路由强档的命令构建一律经本服务解析——供应商/档位/凭证三层配置改一处，全部
 * 引用自动跟上（下一轮命令构建即生效，进行中 run 不定格）。
 *
 * <p><b>报错语义（无静默回落）</b>：档位缺行、档位/通道停用、通道 key 空皆明确
 * 报错——apiKey 落库为唯一正本，不回落环境变量（换 key 改库即生效，故障一眼
 * 可查）。动态查库、不缓存（对话轮本身是秒级 LLM 调用，两次主键 SELECT 的
 * 代价可忽略——同 {@code AgentConfigAppService} 口径）。</p>
 *
 * <p>enableThinking 显式关（{@link #THINKING_ENABLED}）：方舟 DeepSeek V4 系均标
 * 深度思考、不传参数时默认开/关未确证——装配不留给模型默认；终值冒烟实测开/关
 * 的延迟与质量后定（#369 活体验收）。</p>
 */
@Service
public class ModelTierAppService {

    /**
     * 深度思考开关终值位（当前 false＝显式关）：变更走此处单点——volcengine
     * 平台工厂与 SDK 内置 deepseek provider 都从创建上下文读该值。
     */
    static final boolean THINKING_ENABLED = false;

    private final ModelTierStore tierStore;

    public ModelTierAppService(ModelTierStore tierStore) {
        this.tierStore = tierStore;
    }

    /**
     * 解析产物（命令构建消费面）：模型串（前缀:实际模型名——前缀即协议路由，
     * 结构化日志/计量/单价表按前缀归因）＋通道注入件（base 层 {@link ModelChannel}
     ——进创建上下文与实例缓存键指纹）。
     *
     * @param tierKey 档位语义键（绑定面引用值；空＝调用方缺省腿未收编，属编程错误）
     */
    @Transactional(readOnly = true)
    public ResolvedModel resolve(String tierKey) {
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
        ModelProvider provider = tierStore.findProvider(tier.providerKey());
        if (provider == null) {
            // FK 兜底（档位行的 provider_key 有外键约束，常态不可达——行被并发删的窗口面）
            throw new ApplicationException(ProjectMessage.MODEL_TIER_NOT_FOUND,
                    tierKey + "（通道行缺失: " + tier.providerKey() + "）");
        }
        if (!provider.enabled()) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_DISABLED, provider.providerKey());
        }
        if (provider.apiKey() == null || provider.apiKey().isBlank()) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_KEY_MISSING,
                    provider.providerKey());
        }
        return new ResolvedModel(tierKey, provider.providerKey(), tier.modelName(),
                provider.baseUrl(), provider.apiKey(), THINKING_ENABLED);
    }

    /**
     * 生效解析束：档位键、通道键、实际模型名、通道注入面（baseUrl/apiKey/
     * thinking）。
     */
    public record ResolvedModel(String tierKey, String providerKey, String modelName,
            String baseUrl, String apiKey, boolean thinkingEnabled) {

        /** 对话轨道模型串（前缀:实际模型名——前缀即协议路由）。 */
        public String modelString() {
            return providerKey + ":" + modelName;
        }

        /** base 层通道注入件（随命令透传：创建上下文＋实例缓存键指纹）。 */
        public ModelChannel channel() {
            return new ModelChannel(baseUrl, apiKey, thinkingEnabled);
        }
    }
}

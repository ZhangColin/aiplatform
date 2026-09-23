package com.aieducenter.aiplatform.business.project.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aieducenter.aiplatform.business.project.domain.model.AgentOperationalConfig;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.repository.AgentConfigStore;

/**
 * 智能体运营配置装配读面（#251，ADR-0021「库值优先、缺省回落」解析单点）：
 * {@code MainAgentAppService}（主智能体命令/续跑构建）与 {@code CoderRunAttempts}
 * （执行体命令构建）装配 systemPrompt／模型串时一律经本服务取<b>生效值</b>——库
 * 有覆盖值用库值，无覆盖（行不存在或列 null）回落 {@link AgentProfile} 枚举
 * 默认。枚举仍是身份与缺省正本，本服务只是覆盖态的读腿。
 *
 * <p>动态查库、不缓存：每次 {@code effectiveOf} 一次主键 SELECT，配置变更后
 * 下一轮命令构建自然反映（对话轮本身是秒级 LLM 调用，读取代价可忽略）；智能体
 * 实例层不必感知——工厂缓存键已含 sysPrompt 与模型串（{@code AgentscopeAgentClient#
 * prepareFor}），新值即新实例，进行中 run 不定格。classify/naming 等一次性判定
 * 不走本面（提示词与档位各自独立，不属智能体身份面）。</p>
 *
 * <p><b>工具面规格（#252）</b>：增强工具开关（web_search/fetch_url）同取生效值，
 * {@link EffectiveConfig#toolSpec()} 编成规格串随命令入底座——进工厂实例缓存键
 * （开关变更＝规格变＝新实例新装配）并透传工具装配（{@code ProfileToolkitSupplier}
 * 按规格发放，关即不注册）。规格串编解码都在本类（business 单源，底座不解释）。</p>
 */
@Service
public class AgentConfigAppService {

    private final AgentConfigStore configStore;

    public AgentConfigAppService(AgentConfigStore configStore) {
        this.configStore = configStore;
    }

    /**
     * 生效配置束（一次查库同取——prompt 与档位在命令构建处恒结伴，工具开关
     * #252 同批）：生效 systemPrompt、生效档位裸名、生效对话轨道模型串（provider
     * 前缀单源拼装）、增强工具开关生效态。
     */
    public record EffectiveConfig(String systemPrompt, String modelId, String chatModelString,
            boolean webSearchEnabled, boolean fetchUrlEnabled) {

        /** 增强工具开关规格串（命令透传底座：进缓存键＋装配判据；编解码单源本类）。 */
        public String toolSpec() {
            return "ws=" + webSearchEnabled + ",fu=" + fetchUrlEnabled;
        }
    }

    /**
     * 智能体生效配置（库值优先、缺省回落枚举默认——缺省腿单点）。
     */
    @Transactional(readOnly = true)
    public EffectiveConfig effectiveOf(AgentProfile profile) {
        AgentOperationalConfig override = configStore.find(profile.key());
        AgentOperationalConfig config =
                override != null ? override : AgentOperationalConfig.defaults(profile.key());
        String modelId = config.effectiveModelId(profile.modelId());
        return new EffectiveConfig(config.effectiveSystemPrompt(profile.systemPrompt()),
                modelId, AgentProfile.chatModelStringOf(modelId),
                config.webSearchEnabled(), config.fetchUrlEnabled());
    }

    /**
     * 规格串解码（装配腿，与 {@link EffectiveConfig#toolSpec()} 对偶的解析单点）：
     * null/空/缺件/不可解析＝该件缺省开（{@code AgentOperationalConfig#defaults}
     * 同义——一次性判定等无规格语境的宽容腿，坏规格不炸装配面）。
     */
    public static boolean toolEnabled(String toolSpec, String key) {
        if (toolSpec == null) {
            return true;
        }
        for (String facet : toolSpec.split(",")) {
            String[] pair = facet.split("=", 2);
            if (pair.length == 2 && pair[0].trim().equals(key)) {
                return !pair[1].trim().equals("false");
            }
        }
        return true;
    }
}

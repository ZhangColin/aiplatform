package com.semgravio.aiplatform.business.project.application;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;
import com.semgravio.aiplatform.business.project.domain.model.AgentEdgePoint;
import com.semgravio.aiplatform.business.project.domain.model.AgentOperationalConfig;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.repository.AgentConfigStore;

/**
 * 智能体运营配置装配读面（#251，ADR-0021「库值优先、缺省回落」解析单点）：
 * {@code MainAgentAppService}（主智能体命令/续跑构建）与 {@code CodingRunDispatcher}
 * （执行体命令构建）装配 systemPrompt／模型配置键时一律经本服务取<b>生效值</b>
 * ——库有覆盖值用库值，无覆盖（行不存在或列 null）回落 {@link AgentProfile} 枚举
 * 默认。枚举仍是身份与缺省正本，本服务只是覆盖态的读腿；配置键 → 模型串与通道
 * 注入的解析在 {@code ModelConfigAppService}（ADR-0031 装配链）。
 *
 * <p>动态查库、不缓存：每次 {@code effectiveOf} 一次主键 SELECT，配置变更后
 * 下一轮命令构建自然反映（对话轮本身是秒级 LLM 调用，读取代价可忽略）；智能体
 * 实例层不必感知——工厂缓存键已含 sysPrompt 与解析后的模型串＋通道指纹
 * （{@code AgentscopeAgentClient#prepareFor}），新值即新实例，进行中 run 不定格。
 * 边缘调用点（取名/压缩/分类/预检）的<b>模型配置</b>同走本面
 * （{@link #effectiveEdgeModelConfigKey}，#366 同表同机制）；其提示词仍不属配置面
 * （一次性判定的编排内建）。</p>
 *
 * <p><b>工具面规格（#252；#545 gi 件随 #604 拆除）</b>：增强工具开关
 * （web_search/fetch_url）同取生效值，
 * {@link EffectiveConfig#toolSpec()} 编成规格串随命令入底座——进工厂实例缓存键
 * （开关变更＝规格变＝新实例新装配）并透传工具装配（{@code ProfileToolkitSupplier}
 * 按规格发放，关即不注册）。规格串编解码都在本类（business 单源，底座不解释）。</p>
 */
@Service
public class AgentConfigAppService {

    /**
     * 单场验证轮次预算代码缺省（#424 片轻验）：3＝首轮验证＋至多两轮修复复验
     * （修复预算与 #406 终验缺省两轮同量级）；库覆盖（executor 行
     * verify_round_budget，1-20）优先于此值。
     */
    public static final int DEFAULT_VERIFY_ROUND_BUDGET = 3;

    /**
     * 整轨终验修复预算代码缺省（#427，#406 裁决「缺省两轮」）：终验 fail 自动起
     * fix run 修→复走终验的轮数上限，耗尽版本照成＋已知缺陷如实呈现；库覆盖
     * （executor 行 final_verify_fix_budget，0-10——0＝不起修复 run 的运营选择）
     * 优先于此值。
     */
    public static final int DEFAULT_FINAL_VERIFY_FIX_BUDGET = 2;

    private final AgentConfigStore configStore;

    public AgentConfigAppService(AgentConfigStore configStore) {
        this.configStore = configStore;
    }

    /**
     * 生效配置束（一次查库同取——prompt 与配置在命令构建处恒结伴，工具开关
     * #252 同批；模型路由策略 #354 同批——路由判定消费，阈值/强档的代码缺省
     * 回落归 {@code ModelRoutingAppService}；验证面两件 #424 同批——verify 配置
     * 覆盖（null＝随执行体生效档）与轮次预算生效值）：生效 systemPrompt、生效
     * 配置键（ADR-0031：语义键，模型串与通道注入由 {@code ModelConfigAppService
     * #resolve} 在命令构建处解析——本服务只管覆盖态读腿，不越层）、增强工具开关
     * 生效态、配置覆盖标记（路由协同口径的兜底/钉死判据）、路由策略四件存储态、
     * 结构性信号两开关 #454 同批——文件面/规模标签信号缺省 false＝不参与升档判定
     * （失败重试信号常开不受影响），路由判定处随生效配置同取；verify 配置覆盖与
     * 轮次预算生效值；收口闸开关 #433 同批——在场闸/整轨终验
     * 判定处动态读，
     * #435 起缺省 false＝闸关＝砍验证税落地态（运营可一键切回 true＝闸开）；
     * 基座 lint 强制档阻断开关 #541 同批——缺省 false＝让路
     * （强制档违规收口照扫、只随收尾卡标注，不喂修轮不触发失败链；true＝恢复
     * #425 原阻断形态＝回滚保险），尝试环收口判定处随每次尝试读取
     * （{@code CodingRunDispatcher}），仅 run 执行体行消费。
     */
    public record EffectiveConfig(String systemPrompt, String modelConfigKey,
            boolean webSearchEnabled, boolean fetchUrlEnabled,
            boolean modelConfigKeyOverridden, boolean routingEnabled,
            String routingStrongModelConfigKey, Integer routingFileCountThreshold,
            Set<SliceSizeLabel> routingEscalatingSizeLabels,
            boolean routingFileCountSignalEnabled, boolean routingSizeLabelSignalEnabled,
            String verifyModelConfigKey, int verifyRoundBudget, int finalVerifyFixBudget,
            boolean verificationPresenceGateEnabled,
            boolean finalVerificationGateEnabled,
            boolean robustLintBlockingEnabled) {

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
        return new EffectiveConfig(config.effectiveSystemPrompt(profile.systemPrompt()),
                config.effectiveModelConfigKey(profile.modelConfigKey()),
                config.webSearchEnabled(), config.fetchUrlEnabled(),
                config.modelConfigKey() != null, config.routingEnabled(),
                config.routingStrongModelConfigKey(), config.routingFileCountThreshold(),
                config.routingEscalatingSizeLabels(),
                config.routingFileCountSignalEnabled(), config.routingSizeLabelSignalEnabled(),
                config.verifyModelConfigKey(),
                config.verifyRoundBudget() != null
                        ? config.verifyRoundBudget() : DEFAULT_VERIFY_ROUND_BUDGET,
                config.finalVerifyFixBudget() != null
                        ? config.finalVerifyFixBudget() : DEFAULT_FINAL_VERIFY_FIX_BUDGET,
                config.verificationPresenceGateEnabled(),
                config.finalVerificationGateEnabled(),
                config.robustLintBlockingEnabled());
    }

    /**
     * 边缘调用点生效配置键（#366，库值优先、缺省回落 {@link AgentEdgePoint#modelConfigKey()}
     * ——缺省腿单点，三座同机制同表）：值面仅模型配置（边缘行其余覆盖列结构性
     * 缺省），模型串与通道注入由 {@code ModelConfigAppService#resolve} 在调用侧解析。
     */
    @Transactional(readOnly = true)
    public String effectiveEdgeModelConfigKey(AgentEdgePoint point) {
        AgentOperationalConfig override = configStore.find(point.key());
        AgentOperationalConfig config =
                override != null ? override : AgentOperationalConfig.defaults(point.key());
        return config.effectiveModelConfigKey(point.modelConfigKey());
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

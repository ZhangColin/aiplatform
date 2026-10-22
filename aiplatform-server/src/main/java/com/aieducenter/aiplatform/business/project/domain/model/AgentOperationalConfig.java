package com.aieducenter.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 智能体运营配置覆盖态（#251，ADR-0021 修订 ADR-0006 边界——身份与配置分治）：
 * {@code prj_agent_configs} 一行的读模型。智能体身份（有哪些智能体、职能、寻址
 * 键）仍以 {@link AgentProfile} 枚举为正本；本配置只是<b>覆盖态</b>——systemPrompt
 * /modelId 列 null＝无覆盖，装配回落枚举默认（「库值优先、缺省回落」的缺省腿）。
 * 增强工具开关两列已生效装配（#252）：默认 true＝开；关＝该工具退出槽位装配面
 * （生效值经 {@code AgentConfigAppService#effectiveOf} 编入工具面规格串随命令透传）。
 *
 * <p><b>模型路由策略四列（#354，ADR-0030；片复杂度信号 #361 重定）</b>：run
 * 执行体的模型档位路由策略——路由开关（缺省开＝路由判定优先）、强模型档与两
 * 阈值（null＝回落代码缺省：strongModelId=deepseek-v4-pro、文件面 60、片复杂
 * 度 4 项功能条目）。协同口径：路由判定
 * 优先、人工覆盖兜底——路由开＝信号升强档优先，无信号回落人工档兜底
 * （{@code modelId} 覆盖 → 枚举缺省——覆盖即基础档，快档位可经覆盖配置、不关
 * 路由）；路由关＝钉死位（显式关断即钉死，人工档恒生效）。主智能体/设计执行体
 * 不参与路由（Demo 阶段模型档＝run 执行体的模型档），其行同列恒无消费。</p>
 *
 * <p>行不存在与「行存在但覆盖列全 null」语义等价（全回落）；行经 upsert 持续
 * 存在（清空覆盖不删行——操作者列留最近写者，运营可见「谁最后动过」）。</p>
 *
 * @param agentKey                   智能体稳定键（{@link AgentProfile#key()}，寻址腿）
 * @param systemPrompt               运营覆盖 systemPrompt（null＝回落枚举默认）
 * @param modelId                    运营覆盖模型档位（null＝回落枚举默认；裸档位名——provider
 *                                   前缀由 {@link AgentProfile#chatModelStringOf} 统一拼）
 * @param webSearchEnabled           增强工具开关：联网搜索（关即退出槽位装配面，#252）
 * @param fetchUrlEnabled            增强工具开关：网页抓取（同上）
 * @param routingEnabled             模型路由开关（缺省开＝路由判定优先；关＝既有人工定档口径）
 * @param routingStrongModelId       强模型档（路由升档落点；null＝回落代码缺省）
 * @param routingFileCountThreshold  文件面阈值：工作区已跟踪文件数 ≥ 此值升强档（null＝回落代码缺省）
 * @param routingSliceFeatureThreshold 片复杂度阈值：切片功能条目数（枚举段数）≥ 此值升强档（null＝回落代码缺省；#361 重定——原字数信号恒燃废）
 * @param operatorId                 最近写者 id（admin 侧 TSID；null＝从未配置）
 * @param operatorName               最近写者名（直读展示）
 * @param updatedAt                  最近写入时刻（读模型列——回读填充；写路径构造时 null，
 *                                   落库时刻由库定，同 {@code SkillUpdateTrace#createdAt} 房规）
 */
public record AgentOperationalConfig(
        String agentKey,
        String systemPrompt,
        String modelId,
        boolean webSearchEnabled,
        boolean fetchUrlEnabled,
        boolean routingEnabled,
        String routingStrongModelId,
        Integer routingFileCountThreshold,
        Integer routingSliceFeatureThreshold,
        String operatorId,
        String operatorName,
        LocalDateTime updatedAt) {

    /**
     * 无覆盖缺省态（行不存在的等价形）：覆盖列 null、开关全开（路由开）、无写者——
     * 装配解析与留痕前态共用的归一形（null 行先落此形再走生效拼装）。
     */
    public static AgentOperationalConfig defaults(String agentKey) {
        return new AgentOperationalConfig(agentKey, null, null, true, true, true, null, null,
                null, null, null, null);
    }

    /** 生效 systemPrompt：本覆盖非空用覆盖，否则回落缺省参（枚举默认）——单点。 */
    public String effectiveSystemPrompt(String fallback) {
        return systemPrompt != null ? systemPrompt : fallback;
    }

    /** 生效模型档位：本覆盖非空用覆盖，否则回落缺省参（枚举默认）——单点。 */
    public String effectiveModelId(String fallback) {
        return modelId != null ? modelId : fallback;
    }
}

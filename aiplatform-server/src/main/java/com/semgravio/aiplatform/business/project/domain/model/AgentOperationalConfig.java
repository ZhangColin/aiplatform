package com.semgravio.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;
import java.util.Set;

import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;

/**
 * 智能体运营配置覆盖态（#251，ADR-0021 修订 ADR-0006 边界——身份与配置分治）：
 * {@code prj_agent_configs} 一行的读模型。智能体身份（有哪些智能体、职能、寻址
 * 键）仍以 {@link AgentProfile} 枚举为正本；本配置只是<b>覆盖态</b>——systemPrompt
 * /modelId 列 null＝无覆盖，装配回落枚举默认（「库值优先、缺省回落」的缺省腿）。
 * 增强工具开关两列已生效装配（#252）：默认 true＝开；关＝该工具退出槽位装配面
 * （生效值经 {@code AgentConfigAppService#effectiveOf} 编入工具面规格串随命令透传）。
 *
 * <p><b>模型路由策略四列（#354，ADR-0030；信号面 #426 修订）</b>：run
 * 执行体的模型档位路由策略——路由开关（缺省开＝路由判定优先）、强模型档、
 * 文件面阈值与升档规模标签映射（null＝回落代码缺省：strongModelId=pro、
 * 文件面 30〔应用文件面——排除基座基数后重校〕、升档映射「大」）。协同口径：
 * 路由判定优先、人工覆盖兜底——路由开＝信号升强档优先，无信号回落人工档兜底
 * （{@code modelId} 覆盖 → 枚举缺省——覆盖即基础档，快档位可经覆盖配置、不关
 * 路由）；路由关＝钉死位（显式关断即钉死，人工档恒生效）。主智能体/设计执行体
 * 不参与路由（Demo 阶段模型档＝run 执行体的模型档），其行同列恒无消费。</p>
 *
 * <p>行不存在与「行存在但覆盖列全 null」语义等价（全回落）；行经 upsert 持续
 * 存在（清空覆盖不删行——操作者列留最近写者，运营可见「谁最后动过」）。</p>
 *
 * <p><b>验证面两列（#424 片轻验）</b>：run 执行体行的 verify 子智能体档位覆盖与
 * 单场验证轮次预算——档位 null＝随执行体生效档（含路由升档，缺省同执行体档不降
 * ＝#406 裁决）；预算 null＝回落代码缺省（首轮＋两轮复验）。主智能体/设计执行体
 * 行同列恒无消费（不委派 verify）。</p>
 *
 * @param agentKey                   智能体稳定键（{@link AgentProfile#key()}，寻址腿）
 * @param systemPrompt               运营覆盖 systemPrompt（null＝回落枚举默认）
 * @param modelId                    运营覆盖模型档位键（null＝回落枚举默认；ADR-0031 语义键
 *                                   ——模型串与通道注入由 ModelTierAppService 统一解析）
 * @param webSearchEnabled           增强工具开关：联网搜索（关即退出槽位装配面，#252）
 * @param fetchUrlEnabled            增强工具开关：网页抓取（同上）
 * @param routingEnabled             模型路由开关（缺省开＝路由判定优先；关＝既有人工定档口径）
 * @param routingStrongModelId       强模型档（路由升档落点；null＝回落代码缺省）
 * @param routingFileCountThreshold  文件面阈值：应用文件面（已跟踪文件数扣基座基数）
 *                                   ≥ 此值升强档（null＝回落代码缺省 30，#426 重校）
 * @param routingEscalatingSizeLabels 升档规模标签映射：集内标签的片升强档（null＝回落
 *                                   代码缺省「大」；空集＝规模标签信号整体关停——
 *                                   合法运营选择；#426 替换原枚举阈值列语义）
 * @param verifyModelId              verify 子智能体模型档位键（#424：null＝随执行体生效档；
 *                                   非空即时校验同 modelId〔#367〕）
 * @param verifyRoundBudget          单场验证轮次预算（#424 防无限复验：report_verification
 *                                   登记达预算仍有未过项即停修复—复验；null＝回落代码缺省 3）
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
        Set<SliceSizeLabel> routingEscalatingSizeLabels,
        String verifyModelId,
        Integer verifyRoundBudget,
        String operatorId,
        String operatorName,
        LocalDateTime updatedAt) {

    /**
     * 无覆盖缺省态（行不存在的等价形）：覆盖列 null、开关全开（路由开）、无写者——
     * 装配解析与留痕前态共用的归一形（null 行先落此形再走生效拼装）。
     */
    public static AgentOperationalConfig defaults(String agentKey) {
        return new AgentOperationalConfig(agentKey, null, null, true, true, true, null, null,
                null, null, null, null, null, null);
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

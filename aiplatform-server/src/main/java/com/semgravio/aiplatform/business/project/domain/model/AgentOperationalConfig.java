package com.semgravio.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;
import java.util.Set;

import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;

/**
 * 智能体运营配置覆盖态（#251，ADR-0021 修订 ADR-0006 边界——身份与配置分治）：
 * {@code prj_agent_configs} 一行的读模型。智能体身份（有哪些智能体、职能、寻址
 * 键）仍以 {@link AgentProfile} 枚举为正本；本配置只是<b>覆盖态</b>——systemPrompt
 * /modelConfigKey 列 null＝无覆盖，装配回落枚举默认（「库值优先、缺省回落」的缺省腿）。
 * 增强工具开关两列已生效装配（#252）：默认 true＝开；关＝该工具退出槽位装配面
 * （生效值经 {@code AgentConfigAppService#effectiveOf} 编入工具面规格串随命令透传）。
 *
 * <p><b>模型路由策略四列（#354，ADR-0030；信号面 #426 修订）</b>：run
 * 执行体的模型配置路由策略——路由开关（缺省开＝路由判定优先）、强模型档、
 * 文件面阈值与升档规模标签映射（null＝回落代码缺省：strongModelConfigKey=pro、
 * 文件面 30〔应用文件面——排除基座基数后重校〕、升档映射「大」）。协同口径：
 * 路由判定优先、人工覆盖兜底——路由开＝信号升强档优先，无信号回落人工档兜底
 * （{@code modelConfigKey} 覆盖 → 枚举缺省——覆盖即基础档，快档位可经覆盖配置、不关
 * 路由）；路由关＝钉死位（显式关断即钉死，人工档恒生效）。主智能体/设计执行体
 * 不参与路由（Demo 阶段模型档＝run 执行体的模型档），其行同列恒无消费。</p>
 *
 * <p><b>路由结构性信号开关两列（#454，ADR-0037——用户裁决）</b>：文件面信号
 * 开关与规模标签信号开关——缺省 false＝关＝对应信号不参与升档判定（观测值照留
 * 进判定快照、标未参与）；true＝恢复对应判据（阈值/映射旋钮照旧生效）。失败重试
 * 信号不受两开关影响、常开。理由：文件面只增不减、「越大越强模」激励错位，规模
 * 标签同理是静态自报；生命周期口径＝先都关掉观察，效果不好随时开回来。仅 run
 * 执行体行消费（判定全部发生在路由判定单点），主智能体/设计执行体行同列恒无消费。</p>
 *
 * <p>行不存在与「行存在但覆盖列全 null」语义等价（全回落）；行经 upsert 持续
 * 存在（清空覆盖不删行——操作者列留最近写者，运营可见「谁最后动过」）。</p>
 *
 * <p><b>验证面两列（#424 片轻验）</b>：run 执行体行的 verify 子智能体配置覆盖与
 * 单场验证轮次预算——档位 null＝随执行体生效档（含路由升档，缺省同执行体档不降
 * ＝#406 裁决）；预算 null＝回落代码缺省（首轮＋两轮复验）。主智能体/设计执行体
 * 行同列恒无消费（不委派 verify）。</p>
 *
 * <p><b>终验修复预算列（#427 整轨终验）</b>：run 执行体行的整轨终验修复 run 预算
 * ——null＝回落代码缺省（两轮）；主智能体/设计执行体行同列恒无消费。</p>
 *
 * <p><b>收口闸开关列（#433 开关承载面，#435 翻转缺省；#604 读法顺延）</b>：验证
 * 在场闸（#424）／整轨终验拦「生成完成」（#427）的独立开关——缺省 false＝闸关
 * ＝#432 Step 1 砍验证税落地态（秒级确定性探活三件套〔8081 探活＋入口文件完整
 * ＋git 成版〕为唯一收口要件）；true＝该闸生效（运营可一键切回旧行为）。空转
 * 产出证明闸列（#420）随 #604 拆除（proof_of_output_gate_enabled 经 V83 迁移
 * 删列）。仅 run 执行体行消费（闸判定全部发生在 run 收口路径），主智能体/设计
 * 执行体行同列恒无消费。闸判定处动态查库不缓存——运营面改完下一次闸判定即
 * 生效，不发版、进行中 run 不定格。</p>
 *
 * <p><b>生成会话形态两开关列（#436）已随 #602 拆除</b>：单会话连续编码开关
 * 与切片并行机器开关随切片/轨道机器整体退役（prj_agent_configs 两列＋留痕四面
 * 经 V82 迁移拆除）——判定消费面（生成轨道派发处）已不存在。</p>
 *
 * <p><b>决策时微指令注入四列（#437）与 verify 自决开关列（#442）已随 #604 拆除</b>：
 * 两者只被按 executor 装配键挂的旧栈中间件（决策提示／verify 自决）消费——生成
 * 与更新 run 全部经内核装配形态派发（装配键＝generation-kernel，#601／#604），
 * executor 键不再进装配面，中间件与开关列（prj_agent_configs 五列＋留痕十面，经
 * V83 迁移拆除）一并退役。</p>
 *
 * @param agentKey                   智能体稳定键（{@link AgentProfile#key()}，寻址腿）
 * @param systemPrompt               运营覆盖 systemPrompt（null＝回落枚举默认）
 * @param modelConfigKey                    运营覆盖模型配置键（null＝回落枚举默认；ADR-0031 语义键
 *                                   ——模型串与通道注入由 ModelConfigAppService 统一解析）
 * @param webSearchEnabled           增强工具开关：联网搜索（关即退出槽位装配面，#252）
 * @param fetchUrlEnabled            增强工具开关：网页抓取（同上）
 * @param routingEnabled             模型路由开关（缺省开＝路由判定优先；关＝既有人工定档口径）
 * @param routingStrongModelConfigKey       强模型档（路由升档落点；null＝回落代码缺省）
 * @param routingFileCountThreshold  文件面阈值：应用文件面（已跟踪文件数扣基座基数）
 *                                   ≥ 此值升强档（null＝回落代码缺省 30，#426 重校）
 * @param routingEscalatingSizeLabels 升档规模标签映射：集内标签的片升强档（null＝回落
 *                                   代码缺省「大」；空集＝规模标签信号整体关停——
 *                                   合法运营选择；#426 替换原枚举阈值列语义）
 * @param routingFileCountSignalEnabled 文件面信号开关（#454；缺省 false＝关＝文件面
 *                                   不参与升档判定——观测值照留；true＝恢复阈值判据）
 * @param routingSizeLabelSignalEnabled 规模标签信号开关（#454；缺省 false＝关＝规模
 *                                   标签不参与升档判定；true＝恢复标签映射判据）
 * @param verifyModelConfigKey              verify 子智能体模型配置键（#424：null＝随执行体生效档；
 *                                   非空即时校验同 modelConfigKey〔#367〕）
 * @param verifyRoundBudget          单场验证轮次预算（#424 防无限复验：report_verification
 *                                   登记达预算仍有未过项即停修复—复验；null＝回落代码缺省 3）
 * @param finalVerifyFixBudget       整轨终验修复预算（#427：终验 fail 自动起 fix run 修→
 *                                   复走，耗尽版本照成＋已知缺陷如实呈现；null＝回落代码
 *                                   缺省 2；0＝不起修复 run——合法运营选择）
 * @param verificationPresenceGateEnabled 验证在场闸开关（#433；#435 起缺省 false＝闸关——
 *                                   本 run 无验证登记不收口仅在该闸被运营打开时生效）
 * @param finalVerificationGateEnabled 整轨终验拦「生成完成」开关（#433；#435 起缺省 false＝
 *                                   闸关——终验不起 run、不拦完成叙事）
 * @param robustLintBlockingEnabled  基座 lint 强制档阻断开关（#541，ADR-0051 lint 拆半
 *                                   让路的回滚保险；缺省 false＝让路——强制档违规收口
 *                                   照扫、只随收尾卡标注，不喂修轮、不触发失败链、不
 *                                   间接升档，修轮触发面收窄至样式合规；true＝恢复
 *                                   #425 原阻断形态〔强制档喂修轮＋修不完结构性拒绝＋
 *                                   耗尽段级回滚重跑〕。仅 run 执行体行消费）
 * @param operatorId                 最近写者 id（admin 侧 TSID；null＝从未配置）
 * @param operatorName               最近写者名（直读展示）
 * @param updatedAt                  最近写入时刻（读模型列——回读填充；写路径构造时 null，
 *                                   落库时刻由库定，同 {@code SkillUpdateTrace#createdAt} 房规）
 */
public record AgentOperationalConfig(
        String agentKey,
        String systemPrompt,
        String modelConfigKey,
        boolean webSearchEnabled,
        boolean fetchUrlEnabled,
        boolean routingEnabled,
        String routingStrongModelConfigKey,
        Integer routingFileCountThreshold,
        Set<SliceSizeLabel> routingEscalatingSizeLabels,
        boolean routingFileCountSignalEnabled,
        boolean routingSizeLabelSignalEnabled,
        String verifyModelConfigKey,
        Integer verifyRoundBudget,
        Integer finalVerifyFixBudget,
        boolean verificationPresenceGateEnabled,
        boolean finalVerificationGateEnabled,
        boolean robustLintBlockingEnabled,
        String operatorId,
        String operatorName,
        LocalDateTime updatedAt) {

    /**
     * 无覆盖缺省态（行不存在的等价形）：覆盖列 null、增强工具开关开、路由开、
     * 路由结构性信号两开关关（#454 缺省——文件面/规模标签不参与升档判定）、
     * 收口两闸关（#435 翻转缺省——#432 Step 1 砍验证税落地态；产出证明闸随 #604
     * 拆除）、基座 lint 强制档阻断关（#541 让路
     * 缺省——违规只标注）、无写者——装配解析与留痕前态共用的归一形（null 行先落
     * 此形再走生效拼装）。
     */
    public static AgentOperationalConfig defaults(String agentKey) {
        return new AgentOperationalConfig(agentKey, null, null, true, true, true, null, null,
                null, false, false, null, null, null, false, false, false, null, null, null);
    }

    /** 生效 systemPrompt：本覆盖非空用覆盖，否则回落缺省参（枚举默认）——单点。 */
    public String effectiveSystemPrompt(String fallback) {
        return systemPrompt != null ? systemPrompt : fallback;
    }

    /** 生效模型配置：本覆盖非空用覆盖，否则回落缺省参（枚举默认）——单点。 */
    public String effectiveModelConfigKey(String fallback) {
        return modelConfigKey != null ? modelConfigKey : fallback;
    }
}

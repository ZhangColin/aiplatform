package com.semgravio.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 智能体配置变更留痕（#251，append-only）：一次实际变更（值面有动）的前后全量
 * 值快照＋操作者。旧值快照＝回滚依据——回滚＝把旧值写回（即一次新变更、留新痕，
 * 不做版本树，ADR-0021）。值面八件（systemPrompt、modelConfigKey、两工具开关、路由
 * 策略四件〔#354〕）全量留痕：每痕都是完整状态对，与 #252 工具开关变更共表共
 * 机制。决策时注入四件与 verify 自决开关的前后面已随 #604 退役（executor 装配键
 * 旧中间件出局，留痕列经 V83 迁移拆除）；验证面／收口闸／基座 lint 阻断的留痕
 * 面随审查与收口组退役（#605，V84 删列）。
 *
 * @param id                            留痕标识（TSID，写痕用例生成）
 * @param agentKey                      智能体稳定键（无 FK——留痕跨配置行存活）
 * @param oldSystemPrompt               变更前 systemPrompt（null＝此前即缺省回落）
 * @param oldModelConfigKey                    变更前模型配置（null 同上）
 * @param oldWebSearchEnabled           变更前联网搜索开关
 * @param oldFetchUrlEnabled            变更前网页抓取开关
 * @param oldRoutingEnabled             变更前模型路由开关
 * @param oldRoutingStrongModelConfigKey       变更前强模型档（null＝回落代码缺省）
 * @param oldRoutingFileCountThreshold  变更前文件面阈值（null 同上）
 * @param oldRoutingEscalatingSizeLabels 变更前升档规模标签映射（存储形逗号接 token，
 *                                       如「中,大」；null 同上；空串＝空映射，#426）
 * @param oldRoutingFileCountSignalEnabled 变更前文件面信号开关（#454；恒有值——缺省
 *                                       态恒 false＝不参与升档判定）
 * @param oldRoutingSizeLabelSignalEnabled 变更前规模标签信号开关（#454；同上）
 * @param newSystemPrompt               变更后 systemPrompt（null＝本次清空回落）
 * @param newModelConfigKey                    变更后模型配置（null 同上）
 * @param newWebSearchEnabled           变更后联网搜索开关
 * @param newFetchUrlEnabled            变更后网页抓取开关
 * @param newRoutingEnabled             变更后模型路由开关
 * @param newRoutingStrongModelConfigKey       变更后强模型档（null＝本次清空回落代码缺省）
 * @param newRoutingFileCountThreshold  变更后文件面阈值（null 同上）
 * @param newRoutingEscalatingSizeLabels 变更后升档规模标签映射（存储形逗号接 token；
 *                                       null 同上；空串＝空映射）
 * @param newRoutingFileCountSignalEnabled 变更后文件面信号开关（#454；恒有值）
 * @param newRoutingSizeLabelSignalEnabled 变更后规模标签信号开关（#454；恒有值）
 * @param operatorId                    变更操作者 id（写操作必留痕）
 * @param operatorName                  变更操作者名（直读展示）
 * @param createdAt                     留痕时刻（＝变更动作时刻）
 */
public record AgentConfigTrace(
        long id,
        String agentKey,
        String oldSystemPrompt,
        String oldModelConfigKey,
        boolean oldWebSearchEnabled,
        boolean oldFetchUrlEnabled,
        boolean oldRoutingEnabled,
        String oldRoutingStrongModelConfigKey,
        Integer oldRoutingFileCountThreshold,
        String oldRoutingEscalatingSizeLabels,
        boolean oldRoutingFileCountSignalEnabled,
        boolean oldRoutingSizeLabelSignalEnabled,
        String newSystemPrompt,
        String newModelConfigKey,
        boolean newWebSearchEnabled,
        boolean newFetchUrlEnabled,
        boolean newRoutingEnabled,
        String newRoutingStrongModelConfigKey,
        Integer newRoutingFileCountThreshold,
        String newRoutingEscalatingSizeLabels,
        boolean newRoutingFileCountSignalEnabled,
        boolean newRoutingSizeLabelSignalEnabled,
        String operatorId,
        String operatorName,
        LocalDateTime createdAt) {
}

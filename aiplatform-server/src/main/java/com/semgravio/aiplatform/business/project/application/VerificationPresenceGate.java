package com.semgravio.aiplatform.business.project.application;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

import lombok.extern.slf4j.Slf4j;

/**
 * 验证在场硬门（#424 片轻验常开，#406 裁决「验证在场升为平台硬要件」）：收口
 * 判据在既有硬门（服务探活＋入口文件完整＋产出证明）上新增「验证在场」维度——
 * <b>本 run 无验证登记不准收口</b>（常开、不智能触发：verify 子智能体经
 * report_verification 登记过任何一轮即可，不看结果好坏——结果面走修复—复验
 * 回路与收尾卡如实呈现，不静默）。锚案 s5/s8 式「该片零验证」自此结构性不可能。
 *
 * <p><b>豁免自洽</b>：产出证明闸的豁免路径（验证确认「能力已存在」）本身即是
 * 验证登记——豁免片天然在场，两门不冲突；次序上产出证明在前（零 diff＋无确认
 * 的拒绝回执同时点名补实现/补验证两出路，是更全的那张回执）。</p>
 *
 * <p><b>拒绝回路（与 {@link ProofOfOutputGate} 同形）</b>：判定件抛出
 * {@link IllegalStateException}，被共用尝试环当作该次尝试失败，拒绝回执经重试
 * 现场喂回执行体<b>同 run 补做</b>（把本片验收要点自报给 verify 起验并登记）；
 * 有限重试耗尽走段级自愈重跑（#353 既有回滚重跑机制，重跑轮验证事实从零起算
 * ——回滚抹掉的工作上旧验证已失真，重验才如实）。</p>
 *
 * <p><b>生命周期咬合</b>：验证事实按工作区键（fork 片各跑各的）、run 起跑与
 * 重跑轮起跑清残留（{@link VerificationFacts#clear}）——「本 run」即当前尝试
 * 环的整个生命周期，跨尝试连续（首试登记后流中断、重试正常收口时统计仍在）。
 * 本门只读不消费：收口统计仍由收口载荷拼装处 {@link VerificationFacts#consume}
 * 取走，两读不互斥。</p>
 *
 * <p><b>开关化（#433，#432 Step 1 开关承载面）</b>：本闸接入运营配置面开关
 * （executor 行 {@code verification_presence_gate_enabled}，缺省 true＝闸开＝
 * 现行为）——闸关即放行（无登记不拦），判定处动态查库、不发版、进行中 run
 * 不定格（改完下一次收口判定即生效）。</p>
 */
@Component
@Slf4j
class VerificationPresenceGate {

    private final VerificationFacts verificationFacts;
    private final AgentConfigAppService agentConfigs;

    VerificationPresenceGate(VerificationFacts verificationFacts,
            AgentConfigAppService agentConfigs) {
        this.verificationFacts = verificationFacts;
        this.agentConfigs = agentConfigs;
    }

    /**
     * 验证在场核验（收口判据增补件，装配位与产出证明同位点——探活/入口完整/产出
     * 证明之后、种子/预览/片落表等副作用之前）：本 run 有任一轮验证登记即过；
     * 无登记抛拒绝回执（喂回执行体同 run 补验）。闸关（运营配置面 executor 行
     * {@code verification_presence_gate_enabled}=false，#433 开关化——缺省 true＝
     * 闸开＝现行为）即放行；判定处动态查库，改完下一次收口判定即生效。
     */
    void requirePresence(String workspaceId) {
        if (!agentConfigs.effectiveOf(AgentProfile.EXECUTOR).verificationPresenceGateEnabled()) {
            log.info("[presence] 工作区 {} 验证在场闸关（运营面开关），无登记也放行",
                    workspaceId);
            return;
        }
        if (!verificationFacts.hasRegistration(workspaceId)) {
            throw new IllegalStateException(rejectionReceipt());
        }
    }

    /** 拒绝回执（重试现场的携带物——起验动作与自报验收要点口径都点名）。 */
    private static String rejectionReceipt() {
        return "验证在场未过：本 run 无任何 report_verification 验证登记——片轻验是收口"
                + "硬要件，不跑验证不准收口。请把本片验证委派 verify 子智能体（agent_spawn，"
                + "agent_id 传 verify），任务说明里逐条自报本片验收要点（本片「用户能 X」"
                + "的主径），由它以真浏览器主径验证（含刷新一次验真落库）并 report_verification"
                + " 登记后再收口；活已被前片干完的项，由它确认「能力已存在」（existing=true"
                + " 登记）。";
    }
}

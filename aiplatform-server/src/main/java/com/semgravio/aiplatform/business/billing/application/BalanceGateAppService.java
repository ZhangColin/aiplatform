package com.semgravio.aiplatform.business.billing.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.business.billing.application.BillingAppService.BillingTrack;

/**
 * 段边界余额门判定（#537 计费③，ADR-0047 段间硬门段中自由）：余额检查只发生在
 * 段边界（主智能体一轮/一场 run/一场设计会话各段的起跑前）——试用项目恒放行
 * （计费边界＝翻转时点，翻转前零扣减无门可言）；付费项目源池余额&gt;0 放行下
 * 一段、&le;0 拦下出余额提醒（段内绝不检查绝不中断，扣成负照跑——负余额上界
 * &asymp;一段实耗）。判定读点＝{@link BillingAppService#trackOf} 两轨判定单读点
 * （派发门/拦截闸/体验提示同源的同源口径）。
 *
 * <p>消费面（project 编排缝接线）：入口派发（意见/咨询/作用域改稿被拦输入留存
 * ＋充值回正自动放行）、run 派发核心（修正/生成/设计——被拦 intent 暂存＋充值
 * 回正自动补派）、恢复出口（「继续生成/继续更新」按钮路径 409 指路）。</p>
 */
@Service
public class BalanceGateAppService {

    private final BillingAppService billingAppService;

    public BalanceGateAppService(BillingAppService billingAppService) {
        this.billingAppService = billingAppService;
    }

    /**
     * 段边界判定（只读）：试用或源池余额&gt;0 → 放行；付费且余额&le;0（池缺席的
     * 防御 anomaly 视同不放行）→ 拦下（携当前余额供提醒卡显负额）。
     */
    @Transactional(readOnly = true)
    public Verdict assess(Long projectId) {
        BillingTrack track = billingAppService.trackOf(projectId);
        if (track.trial() || track.sourcePoolPositive()) {
            return Verdict.allow();
        }
        return Verdict.deny(track.sourcePoolBalance());
    }

    /** 段边界判定结果：pass＝放行（试用/余额为正）；blocked＝拦下（携余额快照）。 */
    public record Verdict(boolean pass, Long blockedBalanceFen) {

        static Verdict allow() {
            return new Verdict(true, null);
        }

        static Verdict deny(Long balanceFen) {
            return new Verdict(false, balanceFen);
        }

        public boolean blocked() {
            return !pass;
        }
    }
}

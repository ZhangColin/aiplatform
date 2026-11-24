package com.semgravio.aiplatform.business.billing.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.business.billing.application.BillingAppService.BillingTrack;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BalanceHold;
import com.semgravio.aiplatform.business.billing.domain.enums.BalanceHoldLane;
import com.semgravio.aiplatform.business.billing.domain.enums.BalanceHoldStatus;
import com.semgravio.aiplatform.business.billing.domain.repository.BalanceHoldRepository;

/**
 * 余额提醒面用例（#537 计费③，ADR-0047 段间硬门）：付费项目段边界余额检查不过
 * 时被拦输入的留存（{@code bil_balance_holds}）与放行——充值回正自动恢复受理
 * （「充值到账后下一段自动放行〔不手动重试〕」：hold 留存即恢复面，不放飞用户
 * 输入）；新拦取代旧待决（一项目至多一条待决）。
 *
 * <p><b>留存（{@link #hold}）</b>：被拦输入原文＋附件随卡落待决行（受理前的拦下
 * 不进对话面，原文随卡呈现与恢复）；走充值引导或中途关闭后输入不丢失、卡可回
 * （{@link #pendingCard} 读面——重载/回访经 GET 重建，余额随读点现取）。</p>
 *
 * <p><b>放行（{@link #claimPassable}）</b>：入账事件（{@code PoolToppedUp}，
 * AFTER_COMMIT 编排监听）触发全待决重判门——过门即认领（落定已放行）并返回
 * 载荷供编排按车道恢复受理（意见回意见轮/咨询回答询轮，各恢复受理的天然守卫
 * 照拦不误）；恢复受理失败的留存由编排调 {@link #discard} 退场（用户重发即新段）。
 * #610 设计线整条退役：作用域改稿车道与件序/发散度留存列拆除。</p>
 *
 * <p>与 {@link TrialInterceptAppService} 对偶同构：拦截拦「未付费」（确认＝
 * 用户点确认扣减），本服务拦「已欠费」（确认＝充值到账）。</p>
 */
@Service
public class BalanceReminderAppService {

    private final BalanceHoldRepository holdRepository;
    private final BillingAppService billingAppService;
    private final BalanceGateAppService balanceGate;

    public BalanceReminderAppService(BalanceHoldRepository holdRepository,
            BillingAppService billingAppService, BalanceGateAppService balanceGate) {
        this.holdRepository = holdRepository;
        this.billingAppService = billingAppService;
        this.balanceGate = balanceGate;
    }

    /**
     * 待决余额提醒卡读面（卡可回：重载/回访经 GET 重建，前端无需事件在场）。
     * 已付费项目的待决留存＋源池余额随读（显负额素材）；试用项目不返回（门只拦
     * 付费项目，试用无待决行——防御性照查）。
     */
    @Transactional(readOnly = true)
    public Optional<BalanceReminderCard> pendingCard(Long projectId) {
        return pendingOf(projectId)
                .map(hold -> cardOf(hold, billingAppService.trackOf(projectId)));
    }

    /**
     * 拦下留存：新待决行落库、既有待决顶替（被取代——输入由新留存承接）；返回
     * 卡面（当前负额随读点同取）。
     */
    @Transactional
    public BalanceReminderCard hold(Long projectId, BalanceHoldLane lane, String prompt,
            List<Map<String, Object>> attachments, String runId) {
        // 旧待决先冲刷落库再插新行：同 flush 内 insert 先于 update，部分唯一索引
        // （uk_bil_balance_holds_pending）会在旧行仍 PENDING 时撞新行 insert
        pendingOf(projectId).ifPresent(old -> {
            old.supersede();
            holdRepository.saveAndFlush(old);
        });
        BalanceHold saved = holdRepository.save(BalanceHold.pendingOf(
                projectId, lane, prompt, attachments, runId));
        return cardOf(saved, billingAppService.trackOf(projectId));
    }

    /**
     * 充值回正放行认领（入账事件编排监听的排空口）：全部待决留存逐条重判门——
     * 过门即认领（落定已放行同事务）并返回恢复载荷；不过门（入账的是别项目的池/
     * 账户的别项目源池未回正）保持待决等下一次入账。认领先于恢复受理执行——
     * 重复入账事件的二次排空不再认领同一行（幂等）。
     */
    @Transactional
    public List<ResumableHold> claimPassable() {
        List<ResumableHold> claimed = new ArrayList<>();
        for (BalanceHold hold : holdRepository.findAllByStatus(BalanceHoldStatus.PENDING)) {
            if (!balanceGate.assess(hold.getProjectId()).pass()) {
                continue;
            }
            hold.resume();
            holdRepository.save(hold);
            claimed.add(new ResumableHold(
                    hold.getId() == null ? null : hold.getId().toString(),
                    hold.getProjectId(),
                    hold.getLane(), hold.getPrompt(), hold.getAttachments(),
                    hold.getRunId()));
        }
        return claimed;
    }

    /**
     * 恢复受理失败退场（编排侧：项目已归档等无法恢复受理的面）：取代式退场——
     * 认领已落定（RESUMED）的行也如实定格（{@link BalanceHold#retire}），被拦
     * 输入不再自动恢复（用户重发即新段，段边界门判定自然接管）。
     */
    @Transactional
    public void discard(String holdId) {
        if (holdId == null || holdId.isBlank()) {
            return;
        }
        holdRepository.findById(Long.parseLong(holdId)).ifPresent(hold -> {
            hold.retire();
            holdRepository.save(hold);
        });
    }

    private Optional<BalanceHold> pendingOf(Long projectId) {
        return holdRepository.findFirstByProjectIdAndStatusOrderByIdDesc(
                projectId, BalanceHoldStatus.PENDING);
    }

    private BalanceReminderCard cardOf(BalanceHold hold, BillingTrack track) {
        return new BalanceReminderCard(
                hold.getId() == null ? null : hold.getId().toString(),
                hold.getLane().name(),
                hold.getPrompt(),
                track.sourcePoolBalance(),
                hold.getRunId());
    }

    /**
     * 余额提醒卡面（前端呈现与 GET 读面同形；id 字符串化防 JS 精度丢失，同对话史
     * 口径）。
     *
     * @param holdId            留存 id（卡锚）
     * @param lane              被拦车道（OPINION=意见 / INQUIRY=咨询）
     * @param prompt            被拦输入原文（卡上引用呈现）
     * @param sourcePoolBalance 源池余额快照（分；显负额素材——读面现取，卡显当前负额）
     * @param runId             余额提醒卡事件锚（挂 /api/events?runId=）
     */
    public record BalanceReminderCard(String holdId, String lane, String prompt,
            Long sourcePoolBalance, String runId) {

        /**
         * run 场拦截卡面（#537——生成/修正等 run 场派发被段边界门拦下）：
         * run intent 走编排侧进程内暂存＋充值回正排空（不留存 bil_balance_holds），
         * 故 holdId/lane/prompt 全空——卡面只携当前负额＋事件锚。
         */
        public static BalanceReminderCard forRunBlock(Long sourcePoolBalance, String runId) {
            return new BalanceReminderCard(null, null, null, sourcePoolBalance, runId);
        }
    }

    /**
     * 已认领放行载荷（恢复受理的原被拦输入：车道＋原文＋附件）。
     *
     * @param holdId      留存 id（退场锚——恢复受理失败时编排调 {@link #discard})
     * @param projectId   项目（编排恢复受理的寻址——billing 弱引用透传）
     * @param lane        被拦车道（恢复受理的分岔）
     * @param prompt      被拦输入原文（不重输的恢复面）
     * @param attachments 随拦附件载荷原样（可空）
     * @param runId       余额提醒卡事件锚（旧卡的链路锚）
     */
    public record ResumableHold(String holdId, Long projectId, BalanceHoldLane lane, String prompt,
            List<Map<String, Object>> attachments, String runId) {
    }
}

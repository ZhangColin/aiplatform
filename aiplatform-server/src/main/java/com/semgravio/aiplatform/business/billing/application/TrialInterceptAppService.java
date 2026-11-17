package com.semgravio.aiplatform.business.billing.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.billing.application.BillingAppService.BillingTrack;
import com.semgravio.aiplatform.business.billing.domain.aggregate.InterceptHold;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.enums.InterceptLane;
import com.semgravio.aiplatform.business.billing.domain.enums.InterceptStatus;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.repository.InterceptHoldRepository;

/**
 * 试用拦截面用例（#533 计费②，ADR-0047「试用拦截面＝平台结构闸」）：入口车道
 * 判定之后、受理之前——意见与兜底两道拦（宁严勿漏）、咨询放行、下单意图旧轨
 * （编排归 DispatchAppService，本服务承载留存与确认扣减事实）。
 *
 * <p><b>留存（{@link #block}）</b>：被拦输入原文＋附件随卡落 {@code bil_intercepts}
 * 待决行——确认扣减后按留存车道恢复受理（不重输）；走充值引导或中途关闭后输入
 * 不丢失、卡可回（{@link #pendingCard} 读面）；新拦取代旧待决（一项目至多一条
 * 待决）。</p>
 *
 * <p><b>确认扣减（{@link #confirm}）＝翻转门②</b>：扣减源未定则随卡完成首次
 * 选定（源选定＋翻转同事务）；翻转走 {@link BillingAppService#flipToPaid} 同一
 * 语义（{@code FlipGate.INTERCEPT} 门别留痕，BILL_010/011/012 前置不变）。后台
 * 已充项目池的项目源已选定（入账即选择，#531）——免登录直接确认是载体卡的编排
 * 差异，本层无分支。已付费项目（他路翻转的竞态）跳过翻转、只落定留存并返回载荷
 * 供编排恢复受理。</p>
 */
@Service
public class TrialInterceptAppService {

    private final InterceptHoldRepository holdRepository;
    private final BillingAppService billingAppService;

    public TrialInterceptAppService(InterceptHoldRepository holdRepository,
            BillingAppService billingAppService) {
        this.holdRepository = holdRepository;
        this.billingAppService = billingAppService;
    }

    /**
     * 待决拦截卡读面（卡可回：重载/回访经 GET 重建，前端无需事件在场）：已付费
     * 项目不返回（卡随翻转熄灭——他路翻转后待决行不再呈现）。
     */
    @Transactional(readOnly = true)
    public Optional<InterceptCard> pendingCard(Long projectId) {
        BillingTrack track = billingAppService.trackOf(projectId);
        if (track.paid()) {
            return Optional.empty();
        }
        return pendingOf(projectId).map(hold -> cardOf(hold, track));
    }

    /**
     * 拦截落留存：新待决行落库、既有待决顶替（被取代——输入由新留存承接）；
     * 返回卡面（源池显式素材随读点同取）。
     */
    @Transactional
    public InterceptCard block(Long projectId, InterceptLane lane, String prompt,
            List<Map<String, Object>> attachments, String runId) {
        // 旧待决先冲刷落库再插新行：同 flush 内 insert 先于 update，部分唯一索引
        // （uk_bil_intercepts_pending）会在旧行仍 PENDING 时撞新行 insert
        pendingOf(projectId).ifPresent(hold -> {
            hold.supersede();
            holdRepository.saveAndFlush(hold);
        });
        InterceptHold hold = holdRepository
                .save(InterceptHold.pendingOf(projectId, lane, prompt, attachments, runId));
        return cardOf(hold, billingAppService.trackOf(projectId));
    }

    /**
     * 确认扣减＝翻转门②：源未定随卡首次选定（{@code source} 缺省且源未定＝
     * BILL_010，与翻转前置同语义）；翻转不可逆等前置全走 {@link BillingAppService}
     * 不变；留存同事务落定已确认。返回留存载荷供编排立即受理原被拦输入。
     *
     * @throws ApplicationException BILL_010 源未定且未随卡选定；BILL_011 源池余额
     *                              非正（充值引导语义）；BILL_009 账户源须先登录
     *                              绑定
     */
    @Transactional
    public PendingHold confirm(Long projectId, DeductionSource source, Long accountId) {
        InterceptHold hold = pendingOf(projectId)
                .orElseThrow(() -> new ApplicationException(BillingMessage.INTERCEPT_NOT_PENDING));
        BillingTrack track = billingAppService.trackOf(projectId);
        if (!track.paid()) {
            if (track.deductionSource() == null) {
                if (source == null) {
                    throw new ApplicationException(BillingMessage.FLIP_SOURCE_REQUIRED);
                }
                billingAppService.selectSource(projectId, source, accountId);
            }
            billingAppService.flipToPaid(projectId, FlipGate.INTERCEPT);
        }
        hold.confirm();
        holdRepository.save(hold);
        return new PendingHold(hold.getLane(), hold.getPrompt(), hold.getAttachments(), hold.getRunId());
    }

    /**
     * 当前待决留存（确认编排的前置读：车道决定受理面守卫——意见链才验订单冻结/
     * 挂起问答）。无待决＝BILL_017（可能已被更新的拦截取代）。
     */
    @Transactional(readOnly = true)
    public PendingHold requirePending(Long projectId) {
        return pendingOf(projectId)
                .map(hold -> new PendingHold(hold.getLane(), hold.getPrompt(),
                        hold.getAttachments(), hold.getRunId()))
                .orElseThrow(() -> new ApplicationException(BillingMessage.INTERCEPT_NOT_PENDING));
    }

    private Optional<InterceptHold> pendingOf(Long projectId) {
        return holdRepository.findFirstByProjectIdAndStatusOrderByIdDesc(
                projectId, InterceptStatus.PENDING);
    }

    private InterceptCard cardOf(InterceptHold hold, BillingTrack track) {
        return new InterceptCard(
                hold.getId() == null ? null : hold.getId().toString(),
                hold.getLane().name(),
                hold.getPrompt(),
                track.deductionSource() != null,
                track.deductionSource() == null ? null : track.deductionSource().getCode(),
                track.sourcePoolBalance(),
                hold.getRunId());
    }

    /**
     * 拦截卡面（前端呈现与 GET 读面同形；id 字符串化防 JS 精度丢失，同对话史
     * 口径）。
     *
     * @param interceptId       留存 id（卡锚）
     * @param lane              被拦车道（OPINION/FALLBACK——恢复受理的分岔）
     * @param prompt            被拦输入原文（卡上引用呈现）
     * @param sourceSelected    扣减源是否已选定（未定在卡上完成首次选源）
     * @param deductionSource   已选定源的码位（1=账户余额 2=项目余额；未定 null）
     * @param sourcePoolBalance 源池余额快照（分；池未建 null——免登录直接确认的
     *                          判定素材：项目源＋余额为正）
     * @param runId             拦截卡事件锚
     */
    public record InterceptCard(String interceptId, String lane, String prompt,
            boolean sourceSelected, Integer deductionSource, Long sourcePoolBalance, String runId) {
    }

    /**
     * 待决留存载荷（确认后恢复受理的原被拦输入：车道＋原文＋附件）。
     *
     * @param lane        被拦车道（OPINION→意见轮；FALLBACK→定型轻引导）
     * @param prompt      被拦输入原文（不重输的恢复面）
     * @param attachments 随拦附件载荷原样（可空）
     * @param runId       拦截卡事件锚（旧卡的链路锚）
     */
    public record PendingHold(InterceptLane lane, String prompt,
            List<Map<String, Object>> attachments, String runId) {
    }
}

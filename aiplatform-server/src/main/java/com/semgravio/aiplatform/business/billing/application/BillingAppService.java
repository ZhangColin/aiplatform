package com.semgravio.aiplatform.business.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;

import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingParam;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingPool;
import com.semgravio.aiplatform.business.billing.domain.aggregate.ProjectBilling;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.enums.PoolKind;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingParamRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingPoolRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.ProjectBillingRepository;

/**
 * 计费态用例（#531 计费①）：两轨判定单读点、扣减源选定（用户一次/后台调整）、
 * 付费状态翻转（门①/门②）与运营参数（全局折算率）。
 *
 * <p><b>两轨判定单读点</b>（{@link #trackOf}）：「扣减源未设＋未翻转＝试用；
 * 源选定＋翻转＝付费」——拦截闸、派发门、协议分轨、体验提示四消费面（系列②-⑤
 * 票接线）同本读点取用，不各自查表。行缺席＝试用开局（新项目一律试用）。</p>
 *
 * <p><b>翻转</b>（{@link #flipToPaid}）：门①（岔路口）与门②（拦截卡）同一翻转
 * 语义（门别只留痕）；翻转前置＝源已选定＋源池余额为正（钱的承诺事实——余额
 * 面须读池，聚合读不到故校验在本层）；<b>不可逆</b>。门②的免登录口径（后台已充
 * 项目池直接确认）是载体卡的编排差异，服务层翻转同形。</p>
 */
@Service
public class BillingAppService {

    private final ProjectBillingRepository projectBillingRepository;
    private final BillingPoolRepository poolRepository;
    private final BillingParamRepository paramRepository;

    public BillingAppService(ProjectBillingRepository projectBillingRepository,
            BillingPoolRepository poolRepository, BillingParamRepository paramRepository) {
        this.projectBillingRepository = projectBillingRepository;
        this.poolRepository = poolRepository;
        this.paramRepository = paramRepository;
    }

    /**
     * 两轨判定单读点（只读）：行缺席＝试用开局（源未设、无池）。付费项目的
     * 源池余额随读（段边界放行判定与余额提醒的素材，#537 消费）。
     */
    @Transactional(readOnly = true)
    public BillingTrack trackOf(Long projectId) {
        return projectBillingRepository.findByProjectId(projectId)
                .map(this::trackOfRow)
                .orElseGet(() -> new BillingTrack(false, null, null, null, null, null));
    }

    private BillingTrack trackOfRow(ProjectBilling billing) {
        Long sourcePoolId = null;
        Long sourcePoolBalance = null;
        if (billing.sourceSelected()) {
            BillingPool pool = sourcePoolOf(billing).orElse(null);
            if (pool != null) {
                sourcePoolId = pool.getId();
                sourcePoolBalance = pool.getBalance();
            }
        }
        return new BillingTrack(billing.paid(), billing.getDeductionSource(),
                sourcePoolId, sourcePoolBalance, billing.getFlippedAt(), billing.getFlipGate());
    }

    /**
     * 用户一次性选定扣减源（载体卡在后续票）：源重复选定拒绝（BILL_008）；
     * 账户源须带账户定格（BILL_009——访客先登录绑定，编排归载体票）。
     */
    @Transactional
    public void selectSource(Long projectId, DeductionSource source, Long accountId) {
        billingOf(projectId).selectSource(source, accountId);
    }

    /**
     * 后台运营调整扣减源（救济通道，#151 留痕口径）：可覆盖既有选定，操作者
     * 两列落痕（后台端点随系列⑤票）。
     */
    @Transactional
    public void reselectSourceByBackoffice(Long projectId, DeductionSource source,
            Long accountId, Operator operator) {
        billingOf(projectId).reselectSourceByBackoffice(source, accountId, operator);
    }

    /**
     * 翻转为付费（门①岔路口/门②拦截卡同一语义，门别留痕）：不可逆（BILL_012）
     * ；源未选定拒绝（BILL_010）；<b>源池余额为正</b>才翻（BILL_011——充值引导
     * 语义，到账即翻转）。翻转落 {@code flippedAt}＝计费边界（此后一切智能体
     * 调用走扣减；成本观测按此切片，#536）。
     */
    @Transactional
    public void flipToPaid(Long projectId, FlipGate gate) {
        ProjectBilling billing = billingOf(projectId);
        billing.flipToPaid(gate); // 聚合守卫先行：已付费/源未选定的错误优先且精确
        BillingPool sourcePool = sourcePoolOf(billing)
                .orElseThrow(() -> new ApplicationException(BillingMessage.FLIP_BALANCE_NOT_POSITIVE));
        if (!sourcePool.positive()) {
            throw new ApplicationException(BillingMessage.FLIP_BALANCE_NOT_POSITIVE);
        }
    }

    /**
     * 全局折算率现值（token 项用户扣减＝成本单价×折算率）：行缺席＝缺省 1
     * （种子行由迁移落，本兜底为迁移前/异常态的弹性面）。
     */
    @Transactional(readOnly = true)
    public BigDecimal conversionRate() {
        return paramRepository.findByParamKey(BillingParam.KEY_CONVERSION_RATE)
                .map(BillingParam::getParamValue)
                .orElse(BigDecimal.ONE);
    }

    /**
     * 调整计费参数（后台运营面，端点随系列⑤票）：值须为正数（BILL_016）、
     * 键不存在拒绝（BILL_015）。调整只影响此后结算——既有扣减行快照不漂移。
     */
    @Transactional
    public void adjustParam(String paramKey, BigDecimal value, Operator operator) {
        BillingParam param = paramRepository.findByParamKey(paramKey)
                .orElseThrow(() -> new ApplicationException(BillingMessage.PARAM_KEY_UNKNOWN));
        param.updateTo(value, operator);
    }

    private ProjectBilling billingOf(Long projectId) {
        return projectBillingRepository.findByProjectId(projectId)
                .orElseGet(() -> projectBillingRepository.save(ProjectBilling.trialOf(projectId)));
    }

    /** 源池解析：项目源→项目池；账户源→选定账户定格名下的账户池。 */
    private Optional<BillingPool> sourcePoolOf(ProjectBilling billing) {
        if (billing.getDeductionSource() == DeductionSource.PROJECT) {
            return poolRepository.findByKindAndProjectId(PoolKind.PROJECT, billing.getProjectId());
        }
        return poolRepository.findByKindAndAccountId(PoolKind.ACCOUNT, billing.getSourceAccountId());
    }

    /**
     * 两轨判定读点结果（四消费面的同源接口形态）。
     *
     * @param paid             付费状态（翻转过即 true；false＝试用）
     * @param deductionSource  扣减源（null＝未选）
     * @param sourcePoolId     源池 id（源未设或池未建为 null——池随首笔入账出现）
     * @param sourcePoolBalance 源池余额快照（分；null 同上）
     * @param flippedAt        付费状态翻转时点（计费边界锚；null＝试用）
     * @param flipGate         翻转门留痕（null＝未翻转）
     */
    public record BillingTrack(boolean paid, DeductionSource deductionSource, Long sourcePoolId,
            Long sourcePoolBalance, Instant flippedAt, FlipGate flipGate) {

        /** 试用态（两轨判定的读法单点）。 */
        public boolean trial() {
            return !paid;
        }

        /** 源池余额是否为正（段边界放行/翻转前置同口径——大于 0 才放行）。 */
        public boolean sourcePoolPositive() {
            return sourcePoolBalance != null && sourcePoolBalance > 0;
        }
    }
}

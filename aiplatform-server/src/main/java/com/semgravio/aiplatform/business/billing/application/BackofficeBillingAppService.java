package com.semgravio.aiplatform.business.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.billing.application.PoolTopupAppService.TopupReceipt;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingEntry;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingPool;
import com.semgravio.aiplatform.business.billing.domain.aggregate.OperatorAction;
import com.semgravio.aiplatform.business.billing.domain.aggregate.ProjectBilling;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.OperatorActionType;
import com.semgravio.aiplatform.business.billing.domain.enums.PoolKind;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingEntryRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingPoolRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.OperatorActionRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.ProjectBillingRepository;

/**
 * 后台账户运营用例（#535 计费⑤，ADR-0047「后台账户运营：机机签名＋操作者
 * 留痕」）：赠送／手动充值录入／余额调整／扣减源调整四类动作，全部
 * <b>append-only 操作者留痕</b>（{@code bil_operator_actions} 变更前后快照与池
 * 变动/计费态变动<b>同事务</b>成对落）。入账类动作复用
 * {@link PoolTopupAppService} 同一入账口（流水/池/动作三行同事务，原子无半截
 * 账）；向项目池入账即选定项目扣减源（动作即选择，前台零呈现）。端点挂
 * {@code /api/backoffice/billing}（机机签名五头 HMAC，类级
 * {@code @RequireSignature}）。
 *
 * <p>手动充值录入<b>仿凭证核验留痕</b>：实到金额/到账时间/依据三字段必填
 * （BILL_021——缺失即拒、零副作用），核验字段随动作明细（detail JSONB）落痕。
 * 余额调整＝平台故障退款返还与运营纠错的承载（ADR-0047 退款两线的人工线），
 * 目标池必须已存在（BILL_022——调整不是入账，池缺席＝从未入账，先走入账）。
 * 扣减源调整＝用户选错池的后台救济通道（覆盖语义与留痕同
 * {@link BillingAppService#reselectSourceByBackoffice}，动作行另落旧/新源
 * 快照）。</p>
 */
@Service
public class BackofficeBillingAppService {

    /** 手动充值默认备注（凭证未带备注时的留痕面措辞）。 */
    private static final String MANUAL_TOPUP_NOTE = "对公转账手动充值";

    private final PoolTopupAppService topupAppService;
    private final BillingAppService billingAppService;
    private final BillingPoolRepository poolRepository;
    private final BillingEntryRepository entryRepository;
    private final ProjectBillingRepository projectBillingRepository;
    private final OperatorActionRepository actionRepository;

    public BackofficeBillingAppService(PoolTopupAppService topupAppService,
            BillingAppService billingAppService,
            BillingPoolRepository poolRepository,
            BillingEntryRepository entryRepository,
            ProjectBillingRepository projectBillingRepository,
            OperatorActionRepository actionRepository) {
        this.topupAppService = topupAppService;
        this.billingAppService = billingAppService;
        this.poolRepository = poolRepository;
        this.entryRepository = entryRepository;
        this.projectBillingRepository = projectBillingRepository;
        this.actionRepository = actionRepository;
    }

    /**
     * 赠送（默认入账户池、可指定项目池——目标恰一由入账口 BILL_001 守卫）：
     * 入账＋流水＋动作留痕三行同事务；向项目池入账即选定项目扣减源。
     *
     * @param accountId  入账账户（与 projectId 恰选其一）
     * @param projectId  入账项目（与 accountId 恰选其一）
     * @param amountFen  赠送金额（分，恒正——BILL_002）
     * @param note       备注（可空）
     * @param operator   后台操作者（留痕两列）
     */
    @Transactional
    public ActionReceipt grant(Long accountId, Long projectId, long amountFen,
            String note, Operator operator) {
        TopupReceipt receipt = topupAppService.topup(accountId, projectId, amountFen,
                operator, note);
        OperatorAction action = actionRepository.save(OperatorAction.topup(
                OperatorActionType.GRANT, accountId, projectId, receipt.poolId(),
                amountFen, receipt.balanceAfter() - amountFen, receipt.balanceAfter(),
                null, operator));
        return new ActionReceipt(action.getId(), receipt.entryId(), receipt.poolId(),
                receipt.balanceAfter());
    }

    /**
     * 手动充值录入（对公转账——仿凭证核验留痕）：实到金额/到账时间/依据三字段
     * 必填（任一缺失即拒 BILL_021、零副作用——「必填字段缺失即拒」单一口径）；
     * 核验字段随动作明细落痕。入账口径与赠送同（复用入账口——项目池入账即选源）。
     * 实到金额非正（显式给了非正值）：BILL_002。
     *
     * @param receivedAmountFen 实到金额（分，必填恒正——缺失 BILL_021、非正 BILL_002）
     * @param receivedAt        实到时间（必填——BILL_021）
     * @param evidence          依据（必填——银行回单号/转账备注等，BILL_021）
     */
    @Transactional
    public ActionReceipt recordManualTopup(Long accountId, Long projectId,
            Long receivedAmountFen, Instant receivedAt, String evidence,
            String note, Operator operator) {
        if (receivedAmountFen == null || receivedAt == null
                || evidence == null || evidence.isBlank()) {
            throw new ApplicationException(BillingMessage.VOUCHER_FIELDS_INCOMPLETE);
        }
        if (receivedAmountFen <= 0) {
            throw new ApplicationException(BillingMessage.TOPUP_AMOUNT_INVALID);
        }
        TopupReceipt receipt = topupAppService.topup(accountId, projectId,
                receivedAmountFen, operator,
                note == null || note.isBlank() ? MANUAL_TOPUP_NOTE : note);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("receivedAmount", receivedAmountFen);
        detail.put("receivedAt", receivedAt.toString());
        detail.put("evidence", evidence.trim());
        OperatorAction action = actionRepository.save(OperatorAction.topup(
                OperatorActionType.MANUAL_TOPUP, accountId, projectId, receipt.poolId(),
                receivedAmountFen, receipt.balanceAfter() - receivedAmountFen,
                receipt.balanceAfter(), detail, operator));
        return new ActionReceipt(action.getId(), receipt.entryId(), receipt.poolId(),
                receipt.balanceAfter());
    }

    /**
     * 余额调整（平台故障退款返还与运营纠错同载体）：目标池必须已存在（BILL_022——
     * 调整不是入账）；行锁串行化下池变动＋流水调整行＋动作留痕三行同事务。
     * {@code delta} 非零带符号（正＝补入/返还、负＝追回，BILL_003），原因必填
     * （BILL_005）。
     */
    @Transactional
    public ActionReceipt adjustBalance(Long accountId, Long projectId, long delta,
            String reason, Operator operator) {
        boolean accountTarget = accountId != null;
        if (accountTarget == (projectId != null)) {
            throw new ApplicationException(BillingMessage.POOL_TARGET_INVALID);
        }
        Optional<BillingPool> pool = accountTarget
                ? poolRepository.findByKindAndAccountId(PoolKind.ACCOUNT, accountId)
                : poolRepository.findByKindAndProjectId(PoolKind.PROJECT, projectId);
        if (pool.isEmpty()) {
            throw new ApplicationException(BillingMessage.POOL_NOT_FOUND);
        }
        BillingPool locked = poolRepository.lockById(pool.get().getId())
                .orElseThrow(IllegalStateException::new);
        long balanceBefore = locked.getBalance();
        locked.adjust(delta); // 聚合守卫：非零带符号（BILL_003）
        BillingEntry entry = entryRepository.save(BillingEntry.adjustment(
                locked.getId(), projectId, delta, locked.getBalance(), operator, reason));
        OperatorAction action = actionRepository.save(OperatorAction.balanceAdjust(
                accountId, projectId, locked.getId(), delta,
                balanceBefore, locked.getBalance(), reason, operator));
        return new ActionReceipt(action.getId(), entry.getId(), locked.getId(),
                locked.getBalance());
    }

    /**
     * 扣减源调整（用户选错池的后台救济通道）：覆盖语义与操作者留痕复用
     * {@link BillingAppService#reselectSourceByBackoffice}；动作行另落旧/新扣减源
     * 快照（旧源从动作前计费态行读出——行缺席＝试用开局未选，旧源落 NULL）。
     */
    @Transactional
    public Long reselectSource(Long projectId, DeductionSource source,
            Long accountId, Operator operator) {
        ProjectBilling billing = projectBillingRepository.findByProjectId(projectId)
                .orElse(null);
        DeductionSource oldSource = billing == null ? null : billing.getDeductionSource();
        Long oldAccountId = billing == null ? null : billing.getSourceAccountId();
        billingAppService.reselectSourceByBackoffice(projectId, source, accountId, operator);
        return actionRepository.save(OperatorAction.sourceReselect(
                projectId, oldSource, oldAccountId, source, accountId, operator)).getId();
    }

    /**
     * 计费运营参数调整（全局折算率——#531 落服务、端点随本票）：键不存在
     * BILL_015、值非正 BILL_016；参数行 UPDATE 自带操作者留痕（bil_params 非
     * append-only，参数非流水）。
     */
    @Transactional
    public void adjustParam(String paramKey, BigDecimal value, Operator operator) {
        billingAppService.adjustParam(paramKey, value, operator);
    }

    /** 动作回执（留痕行锚点＋流水行锚点＋池现值——admin 对账三锚一次带回）。 */
    public record ActionReceipt(Long actionId, Long entryId, Long poolId, long balanceAfter) {
    }
}

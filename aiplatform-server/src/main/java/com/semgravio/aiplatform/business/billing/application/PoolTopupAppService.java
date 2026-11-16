package com.semgravio.aiplatform.business.billing.application;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingEntry;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingPool;
import com.semgravio.aiplatform.business.billing.domain.aggregate.ProjectBilling;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.PoolKind;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingEntryRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingPoolRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.ProjectBillingRepository;

/**
 * 双池入账用例（#531 计费①）：充值/赠送入账（模拟收银台自助与后台入账共用本口
 * ——收银台无操作者、后台带操作者留痕，端点随系列④/⑤票）；<b>向项目池入账即
 * 选定项目扣减源</b>（动作即选择，前台零呈现——销售演示动线：提前为客户项目充值，
 * 客户免登录直接确认扣减使用）。池惰性建（首笔入账才出现）；并发建池撞唯一索引
 * 由本口吸收（重查既有池续作）。流水 append-only：每笔入账恰一行带行后余额。
 */
@Service
public class PoolTopupAppService {

    private final BillingPoolRepository poolRepository;
    private final BillingEntryRepository entryRepository;
    private final ProjectBillingRepository projectBillingRepository;

    public PoolTopupAppService(BillingPoolRepository poolRepository,
            BillingEntryRepository entryRepository,
            ProjectBillingRepository projectBillingRepository) {
        this.poolRepository = poolRepository;
        this.entryRepository = entryRepository;
        this.projectBillingRepository = projectBillingRepository;
    }

    /**
     * 入账（账户与项目恰选其一，BILL_001）：池惰性建＋行锁串行变动，落 TOPUP
     * 流水行（金额恒正）。目标为项目池时<b>同步选定/对齐该项目扣减源＝项目余额
     * （动作即选择）</b>——已选项目源为幂等空过；已选账户源被后台动作对齐为项目
     * 源（后台可调整语义，操作者随行留痕；无操作者头时两列落空）。
     *
     * @param accountId 入账账户（与 projectId 恰选其一）
     * @param projectId 入账项目（与 accountId 恰选其一）
     * @param amountFen 金额（分，恒正——BILL_002）
     * @param operator  后台操作者（模拟收银台自助充值传 null 落空）
     * @param note      备注（充值/赠送留痕面，可空）
     */
    @Transactional
    public TopupReceipt topup(Long accountId, Long projectId, long amountFen,
            Operator operator, String note) {
        if (amountFen <= 0) {
            throw new ApplicationException(BillingMessage.TOPUP_AMOUNT_INVALID);
        }
        boolean accountTarget = accountId != null;
        if (accountTarget == (projectId != null)) {
            throw new ApplicationException(BillingMessage.POOL_TARGET_INVALID);
        }
        BillingPool pool = accountTarget
                ? lockedOrCreate(BillingPool.forAccount(accountId))
                : lockedOrCreate(BillingPool.forProject(projectId));
        pool.deposit(amountFen);
        BillingEntry entry = entryRepository.save(BillingEntry.topup(
                pool.getId(), projectId, amountFen, pool.getBalance(), operator, note));
        if (!accountTarget) {
            selectProjectSource(projectId, operator);
        }
        return new TopupReceipt(pool.getId(), pool.getBalance(), entry.getId());
    }

    /** 账户池现值（无池＝empty——未充过值的账户）。 */
    @Transactional(readOnly = true)
    public Optional<PoolSnapshot> accountPoolOf(Long accountId) {
        return poolRepository.findByKindAndAccountId(PoolKind.ACCOUNT, accountId)
                .map(PoolSnapshot::of);
    }

    /** 项目池现值（无池＝empty——从未入账的项目）。 */
    @Transactional(readOnly = true)
    public Optional<PoolSnapshot> projectPoolOf(Long projectId) {
        return poolRepository.findByKindAndProjectId(PoolKind.PROJECT, projectId)
                .map(PoolSnapshot::of);
    }

    /** 取既有池（行锁）或惰性建池（并发撞唯一索引＝对手已建，重取续作）。 */
    private BillingPool lockedOrCreate(BillingPool fresh) {
        Optional<BillingPool> existing = fresh.getKind() == PoolKind.ACCOUNT
                ? poolRepository.findByKindAndAccountId(fresh.getKind(), fresh.getAccountId())
                : poolRepository.findByKindAndProjectId(fresh.getKind(), fresh.getProjectId());
        if (existing.isPresent()) {
            return poolRepository.lockById(existing.get().getId())
                    .orElseThrow(IllegalStateException::new);
        }
        try {
            return poolRepository.save(fresh);
        } catch (DataIntegrityViolationException e) {
            // 并发首笔入账撞部分唯一索引：对手已建池，重取续作（入账不丢）
            return fresh.getKind() == PoolKind.ACCOUNT
                    ? poolRepository.findByKindAndAccountId(fresh.getKind(), fresh.getAccountId())
                            .orElseThrow(() -> e)
                    : poolRepository.findByKindAndProjectId(fresh.getKind(), fresh.getProjectId())
                            .orElseThrow(() -> e);
        }
    }

    /**
     * 项目池入账即选择（ADR-0047 动作即选择）：源未设→选定项目源（后台动作带
     * 操作者留痕、自助充值落用户面选定语义）；已选项目→空过（幂等，不刷新留痕）
     * ；已选<b>账户</b>→仅后台动作可对齐（操作者留痕）——自助充值（收银台，
     * operator=null）不改源（「选定后不可自助更改」的服务层防线，BILL_008 指路
     * 后台）。
     */
    private void selectProjectSource(Long projectId, Operator operator) {
        ProjectBilling billing = projectBillingRepository.findByProjectId(projectId)
                .orElseGet(() -> ProjectBilling.trialOf(projectId));
        if (billing.getDeductionSource() == DeductionSource.PROJECT) {
            projectBillingRepository.save(billing);
            return;
        }
        if (billing.sourceSelected() && operator == null) {
            throw new ApplicationException(BillingMessage.SOURCE_ALREADY_SELECTED);
        }
        if (operator != null) {
            billing.reselectSourceByBackoffice(DeductionSource.PROJECT, null, operator);
        } else {
            billing.selectSource(DeductionSource.PROJECT, null);
        }
        projectBillingRepository.save(billing);
    }

    /** 入账回执（池现值与流水行锚点）。 */
    public record TopupReceipt(Long poolId, long balanceAfter, Long entryId) {
    }

    /** 池现值快照（余额可负——负余额软模型）。 */
    public record PoolSnapshot(Long poolId, PoolKind kind, long balance) {

        private static PoolSnapshot of(BillingPool pool) {
            return new PoolSnapshot(pool.getId(), pool.getKind(), pool.getBalance());
        }
    }
}

package com.semgravio.aiplatform.business.billing.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.business.billing.application.dto.response.AccountBillingView;
import com.semgravio.aiplatform.business.billing.application.dto.response.BillingEntryView;
import com.semgravio.aiplatform.business.billing.application.dto.response.PoolView;
import com.semgravio.aiplatform.business.billing.application.dto.response.ProjectBillingView;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingEntry;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingPool;
import com.semgravio.aiplatform.business.billing.domain.aggregate.ProjectBilling;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.PoolKind;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingEntryRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingPoolRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.ProjectBillingRepository;

/**
 * 账单读面（#534 计费④「我的账户」）：账户视图（账户池现值＋流水）与项目视图
 * （项目池现值＋计费态投影＋流水＋收银台充值目标）——append-only 账本经此读，
 * 流水新先序（TSID 倒序）。「双池余额」的另一池（项目余额）由前端拿既有项目
 * 清单逐项目读本视图并联——billing 保持弱引用不跨 BC join 项目名（账本可
 * 分离缝不被读面焊死）。
 */
@Service
public class BillingQueryAppService {

    private final BillingPoolRepository poolRepository;
    private final BillingEntryRepository entryRepository;
    private final ProjectBillingRepository projectBillingRepository;

    public BillingQueryAppService(BillingPoolRepository poolRepository,
            BillingEntryRepository entryRepository,
            ProjectBillingRepository projectBillingRepository) {
        this.poolRepository = poolRepository;
        this.entryRepository = entryRepository;
        this.projectBillingRepository = projectBillingRepository;
    }

    /** 我的账户视图：账户池现值（从未充值＝null）＋账户池流水（新先序）。 */
    @Transactional(readOnly = true)
    public AccountBillingView accountView(Long accountId) {
        Optional<BillingPool> pool = poolRepository.findByKindAndAccountId(
                PoolKind.ACCOUNT, accountId);
        return new AccountBillingView(pool.map(this::poolView).orElse(null),
                pool.map(p -> entriesView(entryRepository.findByPoolIdOrderByIdAsc(p.getId())))
                        .orElse(List.of()));
    }

    /**
     * 项目账单视图：项目池现值＋计费态投影（两轨判定单读点）＋流水＋收银台充值
     * 目标（只充当前扣减源池的服务端推导——源未选/项目源＝PROJECT；账户源＝
     * ACCOUNT）。
     */
    @Transactional(readOnly = true)
    public ProjectBillingView projectView(Long projectId) {
        Optional<BillingPool> pool = poolRepository.findByKindAndProjectId(
                PoolKind.PROJECT, projectId);
        ProjectBilling billing = projectBillingRepository.findByProjectId(projectId)
                .orElse(null);
        DeductionSource source = billing == null ? null : billing.getDeductionSource();
        boolean sourceSelected = source != null;
        // 充值目标＝扣减源池（码位与 DeductionSource 对齐：1=我的账户 2=本项目）；
        // 源未选（试用）＝项目池——充值入账即选定项目源
        DeductionSource target = source == DeductionSource.ACCOUNT
                ? DeductionSource.ACCOUNT : DeductionSource.PROJECT;
        return new ProjectBillingView(pool.map(this::poolView).orElse(null), sourceSelected,
                source == null ? null : source.getCode(),
                source == null ? null : source.getName(),
                billing != null && billing.paid(), target.getCode(), target.getName(),
                pool.map(p -> entriesView(entryRepository.findByPoolIdOrderByIdAsc(p.getId())))
                        .orElse(List.of()));
    }

    private PoolView poolView(BillingPool pool) {
        return new PoolView(String.valueOf(pool.getId()), pool.getBalance());
    }

    /** 流水升序读 → 新先序视图（TSID 时间有序——倒序即最新在前）。 */
    private List<BillingEntryView> entriesView(List<BillingEntry> asc) {
        List<BillingEntryView> views = new ArrayList<>(
                asc.stream().map(BillingEntryView::of).toList());
        Collections.reverse(views);
        return views;
    }
}

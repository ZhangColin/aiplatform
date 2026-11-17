package com.semgravio.aiplatform.business.project.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.semgravio.aiplatform.business.billing.application.event.PoolToppedUp;

import lombok.extern.slf4j.Slf4j;

/**
 * 充值回正排空编排（#537 计费③，ADR-0047「充值到账后下一段自动放行〔不手动
 * 重试〕」）：入账事件（{@link PoolToppedUp}，充值事务提交后）触发余额提醒的全
 * 面放行——
 *
 * <ol>
 * <li>被拦输入留存的认领恢复（{@link DispatchAppService#resumeBalanceHolds}——
 * 意见回意见轮／咨询回答询轮／作用域改稿回设计会话）；</li>
 * <li>run 场派发 intent 暂存的排空重派（生成／修正／设计三口——
 * {@code drainBalanceBlocked} 各自按门重判，未回正项目保持暂存待下一次入账）。</li>
 * </ol>
 *
 * <p>两口各自独立 try-catch：单口失败（恢复受理被拒/重派失败）不上抛——监听
 * 面失败不反噬入账事实（事件机制与 PreviewReadyRelay 同先例）；恢复受理失败的
 * 留存由 {@code resumeBalanceHolds} 内退场（discard），intent 由排空内丢弃，
 * 口径均为「用户重发/重提即新段，段边界门判定自然接管」。</p>
 */
@Component
@Slf4j
public class BalanceGateDrainHandler {

    private final DispatchAppService dispatchAppService;
    private final GenerationAppService generationAppService;
    private final IterationAppService iterationAppService;
    private final DesignProcessAppService designProcessAppService;

    public BalanceGateDrainHandler(DispatchAppService dispatchAppService,
            GenerationAppService generationAppService,
            IterationAppService iterationAppService,
            DesignProcessAppService designProcessAppService) {
        this.dispatchAppService = dispatchAppService;
        this.generationAppService = generationAppService;
        this.iterationAppService = iterationAppService;
        this.designProcessAppService = designProcessAppService;
    }

    /**
     * 入账回正监听（AFTER_COMMIT：充值事务落定后放行——入账不可见时放行无意义）：
     * 留存认领恢复＋三口 run 场 intent 排空，逐口隔离失败。
     *
     * <p><b>REQUIRES_NEW（#537 实测定案）</b>：AFTER_COMMIT 回调期线程仍绑定已提交
     * 事务的资源——监听内 REQUIRED 新事务会被「悬挂」不真提交（认领的 resume 落库
     * 与恢复受理的对话史落库实测丢失、留存仍 PENDING 被二次认领）；REQUIRES_NEW
     * 挂起残留绑定、开干净事务，认领与恢复同事务落定（认领先于恢复受理的幂等语义
     * 不变）。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPoolToppedUp(PoolToppedUp event) {
        try {
            dispatchAppService.resumeBalanceHolds();
        }
        catch (RuntimeException e) {
            log.warn("[balance-drain] 余额提醒认领恢复失败（留存保持待决/已退场）：{}",
                    e.toString());
        }
        drainEach("生成", generationAppService::drainBalanceBlocked);
        drainEach("修正", iterationAppService::drainBalanceBlocked);
        drainEach("设计", designProcessAppService::drainBalanceBlocked);
    }

    /** 单口排空隔离：失败只记日志——不拖垮其余车道的放行。 */
    private void drainEach(String lane, Runnable drain) {
        try {
            drain.run();
        }
        catch (RuntimeException e) {
            log.warn("[balance-drain] {} 车道余额回正排空失败（intent 保持/丢弃见车道内日志）：{}",
                    lane, e.toString());
        }
    }
}

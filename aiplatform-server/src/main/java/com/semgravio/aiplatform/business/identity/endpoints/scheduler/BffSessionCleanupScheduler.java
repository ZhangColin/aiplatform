package com.semgravio.aiplatform.business.identity.endpoints.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.business.identity.infrastructure.session.BffSessionStore;

import lombok.extern.slf4j.Slf4j;

/**
 * 过期会话定时清扫（#329，定时任务归北向适配器层——式样照 WorkspaceHibernationScheduler）：
 * 定期清 {@code idn_sessions} 过期行。正确性不依赖本清扫（get 谓词已拒绝过期行），
 * 本轮只做空间回收——读路径零写，删行收敛归这里（清行能力在 store，本类只挂节奏）。
 *
 * <p>节奏 {@code app.session.cleanup-interval}（默认 1h；initialDelay 同值——启动后
 * 一个间隔才首轮，过期行留一拍无碍）。纯 DELETE 幂等：单实例不加锁、多实例并发
 * 清扫安全（后到者 0 行）。尽力而为：本轮失败不炸定时器，下轮再收敛。</p>
 */
@Component
@Slf4j
public class BffSessionCleanupScheduler {

    private final BffSessionStore sessionStore;

    public BffSessionCleanupScheduler(BffSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /** 清扫轮：清过期会话行（有清除才落日志）。 */
    @Scheduled(fixedDelayString = "${app.session.cleanup-interval:1h}",
            initialDelayString = "${app.session.cleanup-interval:1h}")
    public void sweepRound() {
        try {
            int swept = sessionStore.purgeExpired();
            if (swept > 0) {
                log.info("[session] 过期会话清扫：{} 行", swept);
            }
        } catch (RuntimeException e) {
            // 尽力而为：本轮失败不炸定时器，下轮再收敛
            log.warn("[session] 清扫轮未成（下轮再试）", e);
        }
    }
}

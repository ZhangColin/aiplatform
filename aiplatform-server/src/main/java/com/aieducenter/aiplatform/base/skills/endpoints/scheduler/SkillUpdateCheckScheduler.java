package com.aieducenter.aiplatform.base.skills.endpoints.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.skills.application.BackofficeSkillAppService;

/**
 * 技能更新检查扫描轮（#250，ADR-0021「有新版」标记）：定期只读探查全部已装
 * 来源包的远端 HEAD（{@code git ls-remote}，不 clone）——与装时版本不同即在
 * 清单打「有新版」标记；更新永远显式点（POST /update），本扫描永不写条目行。
 * 照 {@code WorkspaceHibernationScheduler} 先例（全库 @Scheduled 房规）。
 *
 * <p>节奏：默认每 1 小时一轮（{@code app.skills.update-check-interval}，Duration
 * 形如 {@code 1h}）；启动不即查（initialDelay＝间隔）——外部仓库网络查询不挡
 * 启动，测试上下文也不触发（验收走直调应用服务）。逐包静默降级在应用服务内
 * 自持（坏远端不拖垮整轮、不抛入运行面）；本层再兜一层——轮级异常只记日志，
 * 留待下轮。单实例部署不加锁：检查幂等收敛，无写竞争面。</p>
 */
@Component
public class SkillUpdateCheckScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SkillUpdateCheckScheduler.class);

    private final BackofficeSkillAppService appService;

    public SkillUpdateCheckScheduler(BackofficeSkillAppService appService) {
        this.appService = appService;
    }

    /** 定时检查轮：逐包只读查远端 → 打/灭「有新版」标记（标记数只作日志面）。 */
    @Scheduled(fixedDelayString = "${app.skills.update-check-interval:1h}",
            initialDelayString = "${app.skills.update-check-interval:1h}")
    public void scanRound() {
        try {
            int marked = appService.checkRemoteUpdates();
            if (marked > 0) {
                logger.info("[skill-update] 检查轮收口：{} 个来源包有新版（显式更新走后台）", marked);
            }
        }
        catch (RuntimeException e) {
            // 尽力而为：本轮失败不炸定时器，下轮再收敛（逐包降级已在服务内自持）
            logger.warn("[skill-update] 检查轮未成（下轮再试）", e);
        }
    }
}

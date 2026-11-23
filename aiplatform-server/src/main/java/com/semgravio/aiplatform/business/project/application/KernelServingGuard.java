package com.semgravio.aiplatform.business.project.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.workspace.application.ConvergenceFace;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceConvergenceAppService;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 开发服务器常驻守卫（#597，#593 用户故事 14／ADR-0054「开发服务器由平台起、常驻，
 * 模型不杀不起」）：内核跑次的平台侧起服面。开发服务器（next dev 于容器
 * 0.0.0.0:8081）由平台经 {@code DockerEnvironmentBackend.startApp} 拉起、幂等常驻
 * （appStartCommand 的 nohup 形态，日志 /workspace/.app.log）——本件是内核跑次
 * 起手位的起服委托：{@link #ensureServing} 走同步 ADMIN 收敛面（按需唤醒休眠/
 * 停止态容器＋健康态幂等起服；镜像 #355 续跑起手同款配对），尽力而为（收敛不成
 * 只告警——收尾判据的 8081 探活仍是硬门，起服失败最终由探活如实判红，不误杀跑次）。
 *
 * <p><b>模型侧无杀／起服务路径</b>（本形态的两条不变量，结构性兑现）：① 内核工具面
 * 恰四件（write_file/read_file/edit_file/bash，{@code KernelTools.NAMES}）——无
 * 服务控制件可发；装配「恰四件」不变量由 KernelAgentAssemblyTest 钉守（构建后工具面
 * 收口）。② 提示词纪律「不要停掉或重启它」由内核环境句
 * （{@code KernelSystemPrompt.SERVER_NOTE}）承载，KernelSystemPromptTest 逐字钉守。
 * 模型可经 bash 跑任意命令是四件之 bash 的固有面（180s 超时），纪律面兜底、
 * 结构面无服务件可点——护栏口径（ADR-0054）照此定界。</p>
 *
 * <p><b>阶段四预留口径</b>（#590 判决 6，ADR-0056 回滚面）：重启服务须带 build 或
 * 统一开发服务器形态，<b>不得拿旧构建产物（.next 残留）冒充回滚结果</b>——本件只
 * 保「平台起、幂等、常驻」，重启/回滚带的起服形态归阶段四的跑前快照链与回滚面，
 * 届时在此口径上施工。</p>
 */
@Slf4j
@Component
class KernelServingGuard {

    private final WorkspaceConvergenceAppService convergence;

    KernelServingGuard(WorkspaceConvergenceAppService convergence) {
        this.convergence = convergence;
    }

    /**
     * 确保开发服务器在跑：同步 ADMIN 收敛（按需唤醒＋幂等起服）。尽力而为——
     * 收敛不成（工作区异常态等）告警后照常返回：收尾判据的 8081 探活仍是硬门，
     * 起服失败最终由探活如实判红（#355 续跑起手同款口径，不炸跑次）。
     */
    void ensureServing(Long workspaceId) {
        try {
            convergence.convergeBlocking(WorkspaceId.of(Long.toString(workspaceId)),
                    ConvergenceFace.ADMIN, /* startAppOnWake= */ true);
        }
        catch (RuntimeException e) {
            log.warn("[kernel] 工作区 {} 开发服务器确保未成（探活仍为收口硬门）：{}",
                    workspaceId, e.toString());
        }
    }
}

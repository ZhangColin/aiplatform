package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.semgravio.aiplatform.base.workspace.application.ConvergenceFace;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceConvergenceAppService;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 开发服务器常驻守卫（#597，#593 用户故事 14／ADR-0054「开发服务器由平台起、常驻，
 * 模型不杀不起」）：内核跑次的平台侧起服面——ensureServing 走同步 ADMIN 收敛
 * （按需唤醒＋幂等起服），模型侧结构性无杀／起服务路径（内核工具面恰四件，
 * write_file/read_file/edit_file/bash，无服务控制件——装配不变量由
 * KernelAgentAssemblyTest 钉守；提示词纪律「不要停掉或重启它」由
 * KernelSystemPromptTest 钉守）。本类只断「平台侧经 ADMIN 同步面幂等起服＋尽力
 * 而为」的委托行为（收敛面替身）。
 */
class KernelServingGuardTest {

    private final WorkspaceConvergenceAppService convergence =
            Mockito.mock(WorkspaceConvergenceAppService.class);
    private final KernelServingGuard guard = new KernelServingGuard(convergence);

    @Test
    void given_serving_guard_when_ensure_then_admin_blocking_convergence_with_app_start() {
        // ADMIN 同步面＋startAppOnWake=true：按需唤醒（休眠/停止态）并在健康态幂等
        // 起服——「平台起、常驻」的兑现形态（镜像 #355 续跑起手同款配对）
        guard.ensureServing(42L);

        verify(convergence).convergeBlocking(WorkspaceId.of("42"),
                ConvergenceFace.ADMIN, true);
    }

    @Test
    void given_convergence_fails_when_ensure_then_best_effort_swallowed() {
        // 尽力而为（#355 同款口径）：收敛不成（工作区异常态等）不炸跑次——
        // 收尾判据的 8081 探活仍是硬门，起服失败最终由探活如实判红
        doThrow(new IllegalStateException("WSP_002 工作区不存在"))
                .when(convergence).convergeBlocking(Mockito.any(), Mockito.any(), Mockito.anyBoolean());

        assertThatCode(() -> guard.ensureServing(42L)).doesNotThrowAnyException();
    }
}

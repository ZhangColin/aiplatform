package com.semgravio.aiplatform.business.project.application;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;

/**
 * 生成内核收尾判据（#597，ADR-0054／CONTEXT.md 生成词条「机器面唯一硬判据＝build ✓＋
 * 探活 ✓」）：{@code pnpm build} 过＋8081 探活有响应才判完成——与内核完成判据句
 * （{@code KernelSystemPrompt.COMPLETION_CRITERIA}：「完成后用 pnpm build 验证可构建，
 * 用 curl http://localhost:8081 确认有响应才算完成」）同口径，平台侧机器核验面。
 *
 * <p>双件逐件记录（{@link KernelClosingResult}）：build 腿经沙箱宿主执行缝跑容器内
 * {@code pnpm build}（exit 0＝可构建）；探活腿<b>消费沙箱宿主现成探针件</b>
 * {@link ClosingProbes#requireReachable}——8081 探活＝预览转可用的门（现成探针正本
 * 在 {@link GenerationAppService#CLOSING_PROBE}，curl 容器内回环 8081，exitCode 0＝
 * 有 HTTP 应答；票文写 0.0.0.0 是服务绑定址，探活打回环同口的既有平台探针）。
 * 「build 过＋探活有响应才判完成」由 {@link KernelClosingResult#complete()} 单点兑现
 * （收口判据唯一化：除此双件外无其他机器判据——入口文件完整性等旧栈判据属旧链）。</p>
 *
 * <p>失败语义分形：build 命令非 0 退出＝判据红（结果记录、不抛）；exec 缝环境故障
 * （工作区不可达等）＝如实上抛——不吞成判据红，免得把环境故障误呈现为「构建失败」；
 * 探活腿沿用现成探针件的「不可达即 IllegalStateException」语义，本件捕获记为判据红。
 * exec 缝无超时候（DockerEnvironmentBackend.runCapture 语义）——build 时长即命令
 * 时长（Next.js 构建分钟级），判据调用方在生成车道上，挂起由运营护栏
 * （45 分钟腿）兜底。</p>
 *
 * <p>形态备注（#593 用户故事 14／#597）：开发服务器由平台起、常驻（
 * {@link KernelServingGuard}），判据不含「服务在跑」以外的常驻断言——「服务常驻」
 * 从判据撤出是 ADR-0054 决议（常驻是平台职责，判据只认 build✓＋探活✓）。</p>
 */
@Component
class KernelClosingCriteria {

    /** build 判据命令：容器内工作区根跑 pnpm build（exit 0＝可构建；路径平台固定，无用户可控片段）。 */
    static final String BUILD_PROBE = "cd /workspace && pnpm build";

    private final WorkspaceLifecycleAppService workspaceLifecycle;
    private final ClosingProbes closingProbes;

    KernelClosingCriteria(WorkspaceLifecycleAppService workspaceLifecycle,
            ClosingProbes closingProbes) {
        this.workspaceLifecycle = workspaceLifecycle;
        this.closingProbes = closingProbes;
    }

    /**
     * 双件核验：build 腿先跑（结果记录），探活腿照跑（逐件结果都要——收尾卡按件
     * 呈现）；{@code complete()=双件齐过} 才判完成。
     */
    KernelClosingResult assess(Long workspaceId) {
        ExecResultResponse build = workspaceLifecycle.exec(Long.toString(workspaceId),
                new WorkspaceExecCommand(BUILD_PROBE));
        boolean buildPassed = build.exitCode() == 0;
        boolean probePassed;
        try {
            closingProbes.requireReachable(workspaceId);
            probePassed = true;
        }
        catch (IllegalStateException unreachable) {
            probePassed = false;
        }
        return new KernelClosingResult(buildPassed, probePassed);
    }

    /** 收尾判据逐件结果：双件齐过 {@code complete()} 才真——「才判完成」的兑现点。 */
    record KernelClosingResult(boolean buildPassed, boolean probePassed) {

        boolean complete() {
            return buildPassed && probePassed;
        }
    }
}

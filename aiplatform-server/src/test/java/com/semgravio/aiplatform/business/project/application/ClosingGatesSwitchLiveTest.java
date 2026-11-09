package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;

/**
 * 收口三硬门开关两态活体（#433，#432 Step 1 的 live 车道防腐面——闸代码不改探针
 * 形态，本类锚「真容器 git 事实 × 开关两态」的外部判定行为；docker daemon 不在
 * 整类跳过，对偶 {@link ModelRoutingFileCountProbeLiveTest} 口径）：
 *
 * <ul>
 * <li><b>产出证明闸·闸开</b>：段起点以来零 diff（基线已提交、无新文件）且无确认
 *     → {@code proof()} 抛拒绝回执（空转片结构性拒绝，真 git diff 探针）；补一个
 *     应用文件提交 → 新判定件 {@code proof()} 放行——探针在真容器里的 shell 形
 *     本身两态都被锚到；</li>
 * <li><b>产出证明闸·闸关</b>（executor 行 {@code proof_of_output_gate_enabled}=
 *     false，运营面直写）——同现场零 diff 直放行（不抛、无拒绝史标注）；</li>
 * <li><b>验证在场闸·两态</b>：闸开无登记抛拒绝回执、有登记放行；闸关无登记也
 *     放行——同一容器工作区键上判定；</li>
 * <li><b>整轨终验闸·闸关</b>：journeys 在场也返回跳过形（不起终验 run、不拦
 *     「生成完成」）。闸开态＝现行为由 GenerationAppServiceTest 编排缝锚定
 *     （起终验 run 需真智能体边界，live 车道不重复承载）。</li>
 * </ul>
 *
 * 开关读点动态查库（不发版）：本类直写库行即切态，跑完清理（不留运营态）。
 */
@IntegrationTest
@Tag("live")
class ClosingGatesSwitchLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 600;

    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @Autowired
    private ProofOfOutputGate proofGate;

    @Autowired
    private VerificationPresenceGate presenceGate;

    @Autowired
    private VerificationFacts verificationFacts;

    @Autowired
    private TrackFinalVerificationAppService finalVerification;

    @Autowired
    private com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String workspaceId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_agent_configs WHERE agent_key = 'executor'");
        if (workspaceId != null) {
            try {
                workspaceLifecycleAppService.destroy(workspaceId);
            }
            catch (RuntimeException e) {
                // 物理清理尽力而为（Docker 残留可重试销毁）
            }
            jdbcTemplate.update("DELETE FROM prj_projects WHERE workspace_id = ?",
                    Long.parseLong(workspaceId));
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_real_git_workspace_when_gate_switched_then_proof_and_presence_follow_switch() {
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();
        String workspaceKey = workspaceId;

        // 段起点锚现场：git 惰性初始化＋基线提交（真容器——产出证明闸的探针面）
        exec("cd /workspace && git init -q -b main ."
                + " && git config user.name probe && git config user.email probe@local");
        exec("cd /workspace && printf 'baseline\\n' > README.md"
                + " && git add -A && git commit -qm baseline");

        // ---------- 闸开（缺省＝现行为）：两闸都拦 ----------
        givenGateSwitches(true, true);

        // 产出证明·零 diff（基线提交后无新文件）：结构性拒绝（真 git diff 探针）
        ProofOfOutputGate.Checking idleChecking = proofGate.forRun(Long.parseLong(workspaceId));
        assertThatThrownBy(idleChecking::proof)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("产出证明未过");

        // 验证在场·无登记：拒绝回执
        assertThatThrownBy(() -> presenceGate.requirePresence(workspaceKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("验证在场未过");

        // 补做：先起判定件（段起点锚＝当前 HEAD——对偶真实 run 的起跑捕获），再长
        // 应用文件提交——收口核验时 diff＞0 放行（外部判定面的放行腿）
        ProofOfOutputGate.Checking workedChecking = proofGate.forRun(Long.parseLong(workspaceId));
        exec("cd /workspace && mkdir -p src"
                + " && printf 'export const probe = 1;\\n' > src/probe.ts"
                + " && git add -A && git commit -qm app-file");
        verificationFacts.record(workspaceKey, List.of(
                new VerificationFacts.Check("首页主径可走通（刷新仍在）", "browser", true, null)));
        assertThat(workedChecking.proof())
                .as("闸开·有 diff：放行且无拒绝史标注（null）").isNull();
        assertThatCode(() -> presenceGate.requirePresence(workspaceKey))
                .as("闸开·有登记：放行").doesNotThrowAnyException();

        // ---------- 闸关（运营面开关）：两闸都放行 ----------
        givenGateSwitches(false, false);

        // 零 diff 现场再起一场（新段起点捕获＝当前 HEAD）：闸关直放行、不累计拒绝史
        assertThat(proofGate.forRun(Long.parseLong(workspaceId)).proof())
                .as("闸关·零 diff：放行（空转片不再被拒）").isNull();
        assertThatCode(() -> presenceGate.requirePresence(workspaceKey))
                .as("闸关·无登记：放行").doesNotThrowAnyException();

        // 整轨终验闸·闸关（final_verification_gate_enabled 缺省 TRUE——本节只写两
        // 列开关，终验列保持缺省闸开；直写终验列= false 复用同一通道）： journeys
        // 在场也不起终验 run、不拦「生成完成」——返回跳过形（无 verifyRunId、
        // 无 run 终态失败）。闸开态＝现行为面由 GenerationAppServiceTest 编排缝
        // 锚定（起终验 run 需真智能体边界，live 车道不重复承载）
        jdbcTemplate.update("""
                UPDATE prj_agent_configs SET final_verification_gate_enabled = FALSE
                WHERE agent_key = 'executor'
                """);
        var project = com.semgravio.aiplatform.business.project.domain.aggregate.Project
                .create("闸关终验活体项目", null, null, Long.parseLong(workspaceId), 1L, null);
        TrackFinalVerificationAppService.Result result =
                finalVerification.verifyTrack(projectRepository.save(project),
                        List.of(new BuildPlan.CoreJourney("买家", "首页", "注册登录→下单支付")));
        assertThat(result.passed()).as("闸关·终验跳过形：passed").isTrue();
        assertThat(result.runTerminallyFailed()).as("闸关·终验跳过形：无 run 终态失败").isFalse();
        assertThat(result.verifyRunId()).as("闸关·终验跳过形：不起终验 run（无锚）").isNull();
    }

    /** 运营面开关直写（live 面不发版切态的唯一通道；缺省行缺省 true＝闸开）。 */
    private void givenGateSwitches(boolean presenceEnabled, boolean proofEnabled) {
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs
                    (agent_key, web_search_enabled, fetch_url_enabled, routing_enabled,
                     verification_presence_gate_enabled, proof_of_output_gate_enabled)
                VALUES ('executor', TRUE, TRUE, TRUE, ?, ?)
                ON CONFLICT (agent_key) DO UPDATE SET
                    verification_presence_gate_enabled
                        = EXCLUDED.verification_presence_gate_enabled,
                    proof_of_output_gate_enabled = EXCLUDED.proof_of_output_gate_enabled
                """, presenceEnabled, proofEnabled);
    }

    private String exec(String command) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(workspaceId,
                new WorkspaceExecCommand(command));
        assertThat(result.exitCode()).as("探针辅助命令应成功: %s", command).isZero();
        return result.stdout() == null ? "" : result.stdout();
    }

    private static boolean dockerAvailable() {
        try {
            // ②族纪律：不要输出就 DISCARD 直落 NUL——未读管道＋waitFor 在 Windows
            // 必死锁（docker info 输出可超匿名管道容量）
            return new ProcessBuilder("docker", "info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        }
        catch (Exception e) {
            return false;
        }
    }
}

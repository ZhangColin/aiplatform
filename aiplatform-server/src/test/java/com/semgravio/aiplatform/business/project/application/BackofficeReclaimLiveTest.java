package com.semgravio.aiplatform.business.project.application;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceProvisionAppService;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceLostMark;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceLostMarkRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceReclaimActionRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.semgravio.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeReclaimResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectType;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一键回收活体验收（#490 AC，真 docker daemon＋真库——非假面；直驱与生产 REST
 * 同一的组合应用服务）：项目级（删后四面〔注册表行/项目行/容器/卷〕对账零残留，
 * 独立 docker CLI 对账）、工作区级（单台三清、项目行不动）、生成中项目（在飞
 * run 先级联停止再回收，无僵尸任务——停止面真 CodingRunTrack）。在册失联裁决
 * 出口（#488 lostAt→#490）：失联标记行随注册行 FK 级联消亡。
 *
 * <p>测试卫生（硬约束）：只造/只清本用例自生成编号的现场（真容器/真卷 TSID
 * 生成不撞既有 ws/vol-ws 历史），收尾全部自清（含留痕行——不淹其他缝）；不动
 * 本机任何既有资源。daemon 不在本机时跳过。</p>
 */
@IntegrationTest
@Tag("live")
class BackofficeReclaimLiveTest {

    private static final Operator OPERATOR = new Operator("900501", "运营·回收live");

    @Autowired
    private BackofficeReclaimAppService reclaimAppService;

    @Autowired
    private WorkspaceProvisionAppService provisioner;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceLostMarkRepository lostMarkRepository;

    @Autowired
    private WorkspaceReclaimActionRepository reclaimActionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private CodingRunTrack codingRunTrack;

    @Autowired
    private DockerEnvironmentBackend backend;

    /** 本用例现场锚（自造；收尾自清）。 */
    private WorkspaceId workspaceId;
    private Long projectId;

    @AfterEach
    void cleanup() {
        if (workspaceId != null) {
            // 回收已尽力：清掉测试可能留下的物理残面（无管道纪律——DISCARD 直落 NUL）
            dockerDiscard("rm", "-f", "ws-" + workspaceId.value());
            dockerDiscard("volume", "rm", "vol-ws-" + workspaceId.value());
            workspaceRepository.findById(workspaceId.id())
                    .ifPresent(workspaceRepository::delete);
        }
        if (projectId != null) {
            codingRunTrack.end(projectId);   // 在途登记随用例清（真 bean 单例）
            projectRepository.findById(projectId).ifPresent(projectRepository::delete);
        }
        reclaimActionRepository.deleteAll();
    }

    // ---------- AC：项目级回收——删后四面（注册表行/项目行/容器/卷）对账零残留 ----------

    @Test
    void given_real_project_when_reclaim_project_then_four_faces_zero_residue() {
        requireDockerDaemon();
        Workspace workspace = provisionReadyWorkspace();
        projectId = seedProject(workspace.getId());

        BackofficeReclaimResponse response =
                reclaimAppService.reclaimProject(projectId, OPERATOR, LocalDateTime.now());

        // 四面零残留：回执判据＋独立 docker CLI 对账（不复用被测通道的探查）
        assertThat(response.scope()).isEqualTo("PROJECT");
        assertThat(response.residueFree()).as("四面零残留（回执判据）").isTrue();
        assertThat(dockerExists("container", "ws-" + workspaceId.value()))
                .as("容器面：独立 docker inspect 对账不存在").isFalse();
        assertThat(dockerExists("volume", "vol-ws-" + workspaceId.value()))
                .as("卷面：独立 docker volume inspect 对账不存在").isFalse();
        assertThat(workspaceRepository.findById(workspace.getId())).isEmpty();
        assertThat(projectRepository.findById(projectId)).isEmpty();
        // append-only 留痕：操作者＋四面事实全量落档
        assertThat(reclaimActionRepository.findAll()).hasSize(1);
        assertThat(reclaimActionRepository.findAll().get(0).allClean()).isTrue();
        assertThat(reclaimActionRepository.findAll().get(0).getOperatorId())
                .isEqualTo("900501");
    }

    // ---------- AC：工作区级回收——单台三清，项目其余部分不动 ----------

    @Test
    void given_linked_project_when_reclaim_workspace_then_three_clean_project_untouched() {
        requireDockerDaemon();
        Workspace workspace = provisionReadyWorkspace();
        projectId = seedProject(workspace.getId());

        BackofficeReclaimResponse response = reclaimAppService.reclaimWorkspace(
                workspaceId.value(), OPERATOR, LocalDateTime.now());

        assertThat(response.scope()).isEqualTo("WORKSPACE");
        assertThat(response.projectRow()).isEqualTo("UNTOUCHED");
        assertThat(response.residueFree()).as("三面零残留（回执判据）").isTrue();
        assertThat(dockerExists("container", "ws-" + workspaceId.value())).isFalse();
        assertThat(dockerExists("volume", "vol-ws-" + workspaceId.value())).isFalse();
        assertThat(workspaceRepository.findById(workspace.getId())).isEmpty();
        assertThat(projectRepository.findById(projectId))
                .as("项目行不动（处置单点残留而不动整个项目）").isPresent();
        assertThat(reclaimActionRepository.findAll()).hasSize(1);
    }

    // ---------- AC：生成中项目回收——在飞 run 先级联停止再回收，无僵尸任务 ----------

    @Test
    void given_generating_project_when_reclaim_then_run_stopped_first_then_clean() {
        requireDockerDaemon();
        Workspace workspace = provisionReadyWorkspace();
        projectId = seedProject(workspace.getId());
        assertThat(codingRunTrack.begin(projectId)).isTrue();   // 模拟在飞编码 run
        assertThat(codingRunTrack.stopRequested(projectId)).isFalse();

        BackofficeReclaimResponse response =
                reclaimAppService.reclaimProject(projectId, OPERATOR, LocalDateTime.now());

        assertThat(response.runsStopped()).as("在飞 run 被级联停止（承 #477）").isTrue();
        assertThat(codingRunTrack.stopRequested(projectId))
                .as("停止面已置停——无僵尸任务对着已删容器自救").isTrue();
        assertThat(response.residueFree()).isTrue();
        assertThat(dockerExists("container", "ws-" + workspaceId.value())).isFalse();
        assertThat(dockerExists("volume", "vol-ws-" + workspaceId.value())).isFalse();
        assertThat(projectRepository.findById(projectId)).isEmpty();
    }

    // ---------- 在册失联裁决出口（#488 lostAt → #490 人工确认后回收） ----------

    @Test
    void given_lost_marked_row_when_reclaim_workspace_then_mark_cascaded_away() {
        // 空壳物理（容器/卷俱无——裁决面最常见的静止现场）：回收即删行，失联标记
        // 行随注册行 FK 级联消亡（#488 通道，勿另写清理）
        requireDockerDaemon();
        workspaceId = WorkspaceId.generate();
        workspaceRepository.save(Workspace.registerPending(workspaceId, EnvKind.DEV));
        lostMarkRepository.save(WorkspaceLostMark.mark(workspaceId,
                LocalDateTime.now().minusHours(2)));

        BackofficeReclaimResponse response = reclaimAppService.reclaimWorkspace(
                workspaceId.value(), OPERATOR, LocalDateTime.now());

        assertThat(response.projectId()).as("无所属（项目行已删）如实 null").isNull();
        assertThat(response.residueFree()).isTrue();
        assertThat(workspaceRepository.findById(workspaceId.id())).isEmpty();
        assertThat(lostMarkRepository.findById(workspaceId.id()))
                .as("失联标记随注册行级联消亡（裁决闭环）").isEmpty();
    }

    // ---------- 活体编排 ----------

    /** 置备一台真容器真卷的 READY 工作区（#485 live 同款：registerPending＋同步幂等置备）。 */
    private Workspace provisionReadyWorkspace() {
        workspaceId = WorkspaceId.generate();
        workspaceRepository.save(Workspace.registerPending(workspaceId, EnvKind.DEV));
        provisioner.provisionForWake(workspaceId, EnvKind.DEV);
        Workspace workspace = workspaceRepository.findById(workspaceId.id()).orElseThrow();
        assertThat(backend.containerState(workspace.toHandle()))
                .as("现场就位：真容器在跑").isEqualTo(ContainerState.RUNNING);
        return workspace;
    }

    private Long seedProject(Long wsId) {
        // 带账户属主：删除级联尾部 SSE 通知要求归属路由键至少其一
        Project project = projectRepository.save(Project.create(
                "回收live对象", ProjectType.WEBSITE, null, wsId, 900601L, null));
        return project.getId();
    }

    /** 独立 docker CLI 存在性对账（不复用被测通道）：exit 0＝在、非 0＝不在。 */
    private boolean dockerExists(String kind, String name) {
        String[] cmd = {"docker", kind, "inspect", name};
        try {
            return new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        } catch (Exception e) {
            throw new IllegalStateException("docker 对账命令失败: " + String.join(" ", cmd), e);
        }
    }

    /** docker CLI 直发（无输出需求：DISCARD 直落 NUL——②族管道纪律，无管道无死锁）。 */
    private void dockerDiscard(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker 命令被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("docker 命令失败: " + String.join(" ", cmd), e);
        }
    }

    private static void requireDockerDaemon() {
        boolean available;
        try {
            available = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "本机 docker daemon 不在，跳过活体验收");
    }
}

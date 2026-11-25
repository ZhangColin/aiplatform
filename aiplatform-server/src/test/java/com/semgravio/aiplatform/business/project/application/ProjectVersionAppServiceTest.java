package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionDetailResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionRollbackStartResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 版本 API 应用服务（#91 版本层地基；#605 起收口成版提交退役；#627 异步回滚）：
 * 版本列表（git log 即正本，零版本 = 空列表非错误；成员判据＝版本标记/回滚标记）、
 * 版本详情（closing 恒空）、回到某版（异步：restore tracked → 停旧进程 → 全新
 * build → 起服 → 探活 → 成版；只拦 run 在途、脏树允许覆盖）；ref 为用户可控入参
 * ——非 hash 形态 404 且不触工作区（shell 注入防线）。
 */
@IntegrationTest
class ProjectVersionAppServiceTest {

    private static final long OWNER = 3897654321098765432L;
    private static final String HASH_1 = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";
    private static final String HASH_2 = "c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2";
    private static final String FS = "\u001f";
    private static final String RS = "\u001e";

    @Autowired
    private ProjectVersionAppService appService;

    @Autowired
    private ConversationHistoryAppService conversationHistory;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private KernelServingGuard kernelServingGuard;

    @MockitoBean
    private ClosingProbes closingProbes;

    @MockitoBean
    private EventsAppService eventsAppService;

    /** 成版后的需求文档入库触发面（#640）：异步入库归知识编排自身的测试，本类只验触发连线。 */
    @MockitoBean
    private ProjectKnowledgeAppService projectKnowledgeAppService;

    @Autowired
    private CodingRunTrack codingRunTrack;

    @BeforeEach
    void runRollbackTaskInline() {
        // 回滚是异步动作：测试里让提交任务同步跑完，直接在应用服务边界断言可观测行为
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return CompletableFuture.completedFuture(null);
        }).when(sessionExecutor).submitGeneration(anyString(), any(Runnable.class));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 版本列表 ----------

    @Test
    void given_no_closing_yet_when_list_then_empty_not_error() {
        Long projectId = persistedProject("9902");
        stubExec((command, out) -> out.exitCode = 3);

        assertThat(appService.list(projectId)).isEmpty();
    }

    @Test
    void given_two_rollback_versions_when_list_then_versions_newest_first() {
        Long projectId = persistedProject("9903");
        stubExec((command, out) -> out.stdout =
                rollbackLogLine(HASH_2, 1700000200L, "回滚到「更新了系统」", "deadbeef")
                        + rollbackLogLine(HASH_1, 1700000100L, "回滚到「首次生成了系统」", "feedface"));

        List<VersionResponse> versions = appService.list(projectId);

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).commitHash()).isEqualTo(HASH_2);
        assertThat(versions.get(0).subject()).isEqualTo("回滚到「更新了系统」");
        assertThat(versions.get(0).runId()).isNull();
        assertThat(versions.get(0).committedAt()).isNotNull();
        assertThat(versions.get(1).commitHash()).isEqualTo(HASH_1);
        assertThat(versions.get(1).runId()).isNull();
    }

    // ---------- 确认用于签约（#629 商业面改接用户面版本） ----------

    @Test
    void given_platform_versions_when_confirm_then_pointer_set_and_response_confirmed() {
        // 确认动作标记当前用户面版本：指针落项目单指针（版本号＋确认时间），回执
        // confirmed=true；不产生新版本（版本序列零改动）
        Long projectId = persistedProject("9930");
        stubExec((command, out) -> out.stdout =
                platformLogLine(HASH_2, 1700000200L, "把按钮改成蓝色", 2, "run-2")
                        + platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1"));

        VersionResponse confirmed = appService.confirm(projectId, 2);

        assertThat(confirmed.versionNo()).isEqualTo(2);
        assertThat(confirmed.current()).isTrue();
        assertThat(confirmed.confirmed()).isTrue();
        Project project = projectRepository.findById(projectId).orElseThrow();
        assertThat(project.getConfirmedVersionNo()).isEqualTo(2);
        assertThat(project.getConfirmedVersionAt()).isNotNull();
        // 回执不产生版本行（版本正本＝git log，确认不动它）
        assertThat(recordedCommands).noneMatch(command -> command.contains("git commit"));
    }

    @Test
    void given_confirmed_v1_when_confirm_v2_then_pointer_moves() {
        Long projectId = persistedProject("9931");
        stubExec((command, out) -> out.stdout =
                platformLogLine(HASH_2, 1700000200L, "把按钮改成蓝色", 2, "run-2")
                        + platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1"));
        appService.confirm(projectId, 1);

        appService.confirm(projectId, 2);

        // 换选＝指针随移（旧标记自然失效）
        assertThat(projectRepository.findById(projectId).orElseThrow()
                .getConfirmedVersionNo()).isEqualTo(2);
    }

    @Test
    void given_same_version_when_confirm_twice_then_idempotent_pointer_unchanged() {
        Long projectId = persistedProject("9932");
        stubExec((command, out) -> out.stdout =
                platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1"));
        appService.confirm(projectId, 1);

        appService.confirm(projectId, 1);

        assertThat(projectRepository.findById(projectId).orElseThrow()
                .getConfirmedVersionNo()).isEqualTo(1);
    }

    @Test
    void given_unknown_version_when_confirm_then_404() {
        Long projectId = persistedProject("9933");
        stubExec((command, out) -> out.stdout =
                platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1"));

        assertThatThrownBy(() -> appService.confirm(projectId, 7))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_NOT_FOUND.message());
        // 拦截零副作用：指针不落
        assertThat(projectRepository.findById(projectId).orElseThrow()
                .getConfirmedVersionNo()).isNull();
    }

    @Test
    void given_archived_project_when_confirm_then_409() {
        Long projectId = persistedProject("9934");
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.archive();
        projectRepository.save(project);
        stubExec((command, out) -> out.stdout =
                platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1"));

        assertThatThrownBy(() -> appService.confirm(projectId, 1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
    }

    @Test
    void given_missing_project_when_confirm_then_404() {
        assertThatThrownBy(() -> appService.confirm(-1L, 1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 版本详情（锚定收尾卡） ----------

    @Test
    void given_unknown_ref_when_detail_then_404() {
        Long projectId = persistedProject("9905");
        stubExec((command, out) -> out.exitCode = 3);

        assertThatThrownBy(() -> appService.detail(projectId, "beefbeef"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_NOT_FOUND.message());
    }

    @Test
    void given_malformed_ref_when_detail_then_404_without_touching_workspace() {
        Long projectId = persistedProject("9906");

        assertThatThrownBy(() -> appService.detail(projectId, "main; rm -rf /"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_NOT_FOUND.message());
        verify(workspaceLifecycleAppService, never()).exec(anyString(), any());
    }

    @Test
    void given_missing_project_when_list_or_detail_then_404() {
        assertThatThrownBy(() -> appService.list(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
        assertThatThrownBy(() -> appService.detail(-1L, HASH_1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 回到某版（#627 异步回滚） ----------

    @Test
    void given_valid_ref_when_rollback_then_async_sequence_and_version_recorded() {
        Long projectId = persistedProject("9910");
        when(workspaceLifecycleAppService.exposePreview(anyString()))
                .thenReturn(URI.create("http://preview.local/"));
        stubExec((command, out) -> {
            if (command.contains("git log -1")) {
                out.stdout = platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1");
            } else if (command.contains("curl")) {
                out.exitCode = 1; // 停旧进程的等待：8081 已不可达
            } else if (command.contains("git log --format")) {
                out.stdout = platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1");
            } else if (command.contains("git commit")) {
                out.stdout = "newhash";
            }
        });

        VersionRollbackStartResponse started = appService.rollback(projectId, HASH_1);

        assertThat(started.label()).isEqualTo("回到 v1");
        assertThat(started.runId()).isNotBlank();
        // 顺序：寻址 → restore tracked → 停旧进程 → 探活到停 → 全新 build → 成版（读列表→提交）
        assertThat(recordedCommands).hasSize(7);
        assertThat(recordedCommands.get(0)).contains("git log -1").contains(HASH_1);
        assertThat(recordedCommands.get(1))
                .contains("git restore --source='" + HASH_1 + "'").contains("--staged --worktree");
        assertThat(recordedCommands.get(2)).contains("pkill").contains("[n]ext");
        assertThat(recordedCommands.get(3)).contains("curl");
        assertThat(recordedCommands.get(4)).contains("rm -rf .next").contains("pnpm build");
        assertThat(recordedCommands.get(5)).contains("git log --format");
        assertThat(recordedCommands.get(6)).contains("git commit")
                .contains("-m '回到 v1'")
                .contains("Platform-Version: 2")
                .contains("Run-Id: " + started.runId());
        // 只拦 run 在途——不拦脏树：绝不查 git status
        assertThat(recordedCommands).noneMatch(command -> command.contains("git status"));
        // 工作消息起点：标题「回到版本 v1」、agent=executor（前端起工作消息）
        ArgumentCaptor<Map<String, Object>> eventPayload = payloadCaptor();
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_START), eventPayload.capture());
        Map<String, Object> startedPayload = eventPayload.getValue();
        assertThat(startedPayload.get(AgentEventTypes.RUN_FIELD)).isEqualTo(started.runId());
        assertThat(startedPayload.get(AgentEventTypes.AGENT_FIELD)).isEqualTo("executor");
        assertThat(sliceTitle(startedPayload)).isEqualTo("回到版本 v1");
        // 收口：run-finish 不携 closing（平台动作不出收尾卡）
        ArgumentCaptor<Map<String, Object>> finishPayload = payloadCaptor();
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH), finishPayload.capture());
        assertThat(finishPayload.getValue()).doesNotContainKey(AgentEventTypes.CLOSING_FIELD);
        // preview-ready 推送（成功面）
        ArgumentCaptor<Map<String, Object>> previewPayload = payloadCaptor();
        verify(eventsAppService).publishNotification(eq(ProjectEventTypes.PREVIEW_READY),
                previewPayload.capture());
        assertThat(previewPayload.getValue().get(ProjectEventTypes.URL_FIELD))
                .isEqualTo("http://preview.local/");
        assertThat(codingRunTrack.isInFlight(projectId)).isFalse();
        // #640：回到某版成功即新出一个用户面版本——需求文档入库同样触发
        verify(projectKnowledgeAppService).ingestPrdOnVersionAsync(projectId);
    }

    @Test
    void given_unknown_ref_when_rollback_then_404() {
        Long projectId = persistedProject("9911");
        stubExec((command, out) -> out.exitCode = 3);

        assertThatThrownBy(() -> appService.rollback(projectId, "beefbeef"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_NOT_FOUND.message());
    }

    @Test
    void given_malformed_ref_when_rollback_then_404_without_touching_workspace() {
        Long projectId = persistedProject("9912");

        assertThatThrownBy(() -> appService.rollback(projectId, "main; rm -rf /"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_NOT_FOUND.message());
        verify(workspaceLifecycleAppService, never()).exec(anyString(), any());
    }

    @Test
    void given_run_in_flight_when_rollback_then_409_before_restore() {
        Long projectId = persistedProject("9914");
        stubExec((command, out) -> out.stdout = platformLogLine(HASH_1, 1700000100L,
                "做一个预约系统", 1, "run-1"));
        codingRunTrack.begin(projectId);

        try {
            assertThatThrownBy(() -> appService.rollback(projectId, HASH_1))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining(ProjectMessage.VERSION_ROLLBACK_RUN_IN_FLIGHT.message());
        } finally {
            codingRunTrack.end(projectId);
        }
        // 在途守卫在寻址后即拒：只过 show，未触 restore（不丢在途改动）
        assertThat(recordedCommands).hasSize(1);
        assertThat(recordedCommands.get(0)).contains("git log -1").contains(HASH_1);
        verify(sessionExecutor, never()).submitGeneration(anyString(), any(Runnable.class));
    }

    @Test
    void given_archived_project_when_rollback_then_409_before_restore() {
        Long projectId = persistedProject("9916");
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.archive();
        projectRepository.save(project);
        stubExec((command, out) -> out.stdout = platformLogLine(HASH_1, 1700000100L,
                "做一个预约系统", 1, "run-1"));

        assertThatThrownBy(() -> appService.rollback(projectId, HASH_1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThat(recordedCommands).hasSize(1);
        verify(sessionExecutor, never()).submitGeneration(anyString(), any(Runnable.class));
    }

    @Test
    void given_build_fails_when_rollback_then_run_failed_with_two_paths_and_no_version() {
        Long projectId = persistedProject("9913");
        stubExec((command, out) -> {
            if (command.contains("git log -1")) {
                out.stdout = platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1");
            } else if (command.contains("curl")) {
                out.exitCode = 1;
            } else if (command.contains("pnpm build")) {
                out.exitCode = 1;
                out.stderr = "Type error: boom";
            }
        });

        appService.rollback(projectId, HASH_1);

        // 失败不记版本、不推 preview-ready
        assertThat(recordedCommands).noneMatch(command -> command.contains("git commit"));
        verify(eventsAppService, never()).publishNotification(eq(ProjectEventTypes.PREVIEW_READY), anyMap());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH), anyMap());
        // 复用 run-failed 明确展示：原因 + 两条路
        ArgumentCaptor<Map<String, Object>> failed = payloadCaptor();
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), failed.capture());
        String message = String.valueOf(failed.getValue().get(AgentEventTypes.ERROR_MESSAGE_FIELD));
        assertThat(message)
                .contains("没能回到 v1")
                .contains("Type error: boom")
                .contains("最新版")
                .contains("修改意见");
        assertThat(codingRunTrack.isInFlight(projectId)).isFalse();
    }

    @Test
    void given_probe_fails_when_rollback_then_run_failed_and_no_version() {
        Long projectId = persistedProject("9917");
        stubExec((command, out) -> {
            if (command.contains("git log -1")) {
                out.stdout = platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1");
            } else if (command.contains("curl")) {
                out.exitCode = 1;
            }
        });
        doThrow(new IllegalStateException("8081 不可达（curl 退出码 7）"))
                .when(closingProbes).requireReachable(any());

        appService.rollback(projectId, HASH_1);

        assertThat(recordedCommands).noneMatch(command -> command.contains("git commit"));
        ArgumentCaptor<Map<String, Object>> failed = payloadCaptor();
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), failed.capture());
        assertThat(String.valueOf(failed.getValue().get(AgentEventTypes.ERROR_MESSAGE_FIELD)))
                .contains("应用没有起来");
        assertThat(codingRunTrack.isInFlight(projectId)).isFalse();
    }

    /** {@code Map<String,Object>} 的捕获器（编译期泛型擦除的样板收口）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> payloadCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    /** run-start 载荷的 slice.title（工作消息头部标题）。 */
    @SuppressWarnings("unchecked")
    private static String sliceTitle(Map<String, Object> payload) {
        Object slice = payload.get(AgentEventTypes.SLICE_FIELD);
        return slice instanceof Map<?, ?> map ? (String) map.get("title") : null;
    }

    // ---------- 自动成版与单轴时间线（#626） ----------

    @Test
    void given_existing_versions_when_commit_user_version_then_next_number_with_platform_and_run_trailers() {
        // 已有 v1/v2 → 新版本号 v3；提交说明携用户那句话＋Platform-Version＋Run-Id
        Long projectId = persistedProject("9920");
        Project project = projectRepository.findById(projectId).orElseThrow();
        stubExec((command, out) -> {
            if (command.contains("git log --format")) {
                out.stdout = platformLogLine(HASH_2, 1700000200L, "把按钮改成蓝色", 2, "run-2")
                        + platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1");
            } else if (command.contains("git commit")) {
                out.stdout = "abcdef";

            }
        });

        Integer versionNo = appService.commitUserVersion(project, "run-3", "加导出功能");

        assertThat(versionNo).isEqualTo(3);
        String commit = recordedCommands.stream()
                .filter(command -> command.contains("git commit")).findFirst().orElseThrow();
        assertThat(commit)
                .contains("git add -A")
                .contains("-m '加导出功能'")
                .contains("Platform-Version: 3")
                .contains("Run-Id: run-3");
        // #640：用户面版本提交成功即触发一次需求文档入库（异步队列由知识编排自持）
        verify(projectKnowledgeAppService).ingestPrdOnVersionAsync(projectId);
    }

    @Test
    void given_prd_ingest_fails_when_commit_user_version_then_version_still_recorded() {
        // #640：入库只是尽力而为——触发抛错也绝不反噬成版（版本号照常返回）
        Long projectId = persistedProject("9923");
        Project project = projectRepository.findById(projectId).orElseThrow();
        stubExec((command, out) -> {
            if (command.contains("git log --format")) {
                out.stdout = "";
            } else if (command.contains("git commit")) {
                out.stdout = "abcdef";
            }
        });
        doThrow(new RuntimeException("入库触发挂了"))
                .when(projectKnowledgeAppService).ingestPrdOnVersionAsync(any());

        assertThat(appService.commitUserVersion(project, "run-1", "做一个预约系统")).isEqualTo(1);
    }

    @Test
    void given_commit_exec_fails_when_commit_user_version_then_degraded_null_not_thrown() {
        // 成版失败降级：提交非零退出只返回 null，绝不反噬生成
        Long projectId = persistedProject("9921");
        Project project = projectRepository.findById(projectId).orElseThrow();
        stubExec((command, out) -> {
            if (command.contains("git log --format")) {
                out.stdout = platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1");
            } else if (command.contains("git commit")) {
                out.exitCode = 128;
            }
        });

        assertThat(appService.commitUserVersion(project, "run-2", "加导出功能")).isNull();
        // #640：没有成版就没有新版本可入库——触发不发出
        verify(projectKnowledgeAppService, never()).ingestPrdOnVersionAsync(any());
    }

    @Test
    void given_platform_versions_when_list_then_current_is_first_and_confirmed_maps_pointer() {
        // 时间线标位：首条＝当前版；confirmed 按项目确认指针映射版本号
        Long projectId = persistedProject("9922");
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.confirmVersion(1);
        projectRepository.save(project);
        stubExec((command, out) -> out.stdout =
                platformLogLine(HASH_2, 1700000200L, "把按钮改成蓝色", 2, "run-2")
                        + platformLogLine(HASH_1, 1700000100L, "做一个预约系统", 1, "run-1"));

        List<VersionResponse> versions = appService.list(projectId);

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isEqualTo(2);
        assertThat(versions.get(0).current()).isTrue();
        assertThat(versions.get(0).confirmed()).isFalse();
        assertThat(versions.get(0).runId()).isEqualTo("run-2");
        assertThat(versions.get(1).versionNo()).isEqualTo(1);
        assertThat(versions.get(1).current()).isFalse();
        assertThat(versions.get(1).confirmed()).isTrue();
    }

    // ---------- 观测件 ----------

    /** git log 单行的用户面版本变体（Platform-Version ＋ Run-Id trailers，#626）。 */
    private static String platformLogLine(String hash, long at, String subject, int versionNo,
            String runId) {
        return hash + FS + at + FS + subject + FS
                + "Platform-Version: " + versionNo + "\nRun-Id: " + runId + "\n" + RS;
    }

    private Long persistedProject(String workspaceId) {
        Project project = Project.create("版本项目", null, Long.parseLong(workspaceId), OWNER, null);
        project.markPrdProduced();
        project.markGenerated();
        return projectRepository.save(project).getId();
    }

    /** git log 单行的回滚变体（Rollback-From trailer，runId 空）。 */
    private static String rollbackLogLine(String hash, long at, String subject, String rollbackFrom) {
        return hash + FS + at + FS + subject + FS + "Rollback-From: " + rollbackFrom + "\n" + RS;
    }

    /** exec 通道脚本化：按命令内容写结果（缺省 exit 0 空输出）；命令全量录制。 */
    private void stubExec(ExecScript script) {
        recordedCommands.clear();
        doAnswer(invocation -> {
            WorkspaceExecCommand command = invocation.getArgument(1);
            recordedCommands.add(command.command());
            ExecOut out = new ExecOut();
            script.apply(command.command(), out);
            return new ExecResultResponse(out.stdout, out.stderr, out.exitCode);
        }).when(workspaceLifecycleAppService).exec(anyString(), any());
    }

    private final List<String> recordedCommands = new java.util.ArrayList<>();

    private interface ExecScript {
        void apply(String command, ExecOut out);
    }

    private static final class ExecOut {
        String stdout = "";
        String stderr = "";
        int exitCode = 0;
    }
}

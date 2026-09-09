package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 版本 API 应用服务（#91 版本层地基）：收口自动成版（幂等 init + 提交携 Run-Id
 * trailer，失败 quietly 不绑架 run 收口）、版本列表（git log 即正本，零版本 = 空
 * 列表非错误）、版本详情锚定收尾卡（Run-Id 联接对话史 closing 条目）；ref 为
 * 用户可控入参——非 hash 形态 404 且不触工作区（shell 注入防线）。
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

    @Autowired
    private CodingRunTrack codingRunTrack;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 收口自动成版 ----------

    @Test
    void given_closing_facts_when_commit_at_closing_then_repo_ensured_and_hash_returned() {
        Long projectId = persistedProject("9900");
        stubExec((command, out) -> {
            if (command.contains("git commit")) {
                out.stdout = HASH_1 + "\n";
            }
        });

        String hash = appService.commitAtClosing(
                projectRepository.findById(projectId).orElseThrow(), "222", "更新了系统");

        assertThat(hash).isEqualTo(HASH_1);
        // 两道命令过 exec 通道：幂等 init（含 .git/info/exclude 落位）→ 成版提交（摘要主题 +
        // Run-Id trailer 锚定收尾卡 + --allow-empty 保 1:1）
        List<String> commands = recordedCommands;
        assertThat(commands).hasSize(2);
        assertThat(commands.get(0)).contains("git init").contains("> .git/info/exclude");
        assertThat(commands.get(1))
                .contains("git add -A")
                .contains("--allow-empty")
                .contains("-m '更新了系统'")
                .contains("Run-Id: 222");
    }

    @Test
    void given_exec_failure_when_commit_at_closing_then_null_and_run_not_taken_down() {
        Long projectId = persistedProject("9901");
        stubExec((command, out) -> out.exitCode = 128);

        String hash = appService.commitAtClosing(
                projectRepository.findById(projectId).orElseThrow(), "222", "更新了系统");

        // 成版失败 quietly（照 recordClosing 先例）：不抛、返回 null（version 键缺省）
        assertThat(hash).isNull();
    }

    // ---------- 版本列表 ----------

    @Test
    void given_no_closing_yet_when_list_then_empty_not_error() {
        Long projectId = persistedProject("9902");
        stubExec((command, out) -> out.exitCode = 3);

        assertThat(appService.list(projectId)).isEmpty();
    }

    @Test
    void given_two_closings_when_list_then_versions_newest_first() {
        Long projectId = persistedProject("9903");
        stubExec((command, out) -> out.stdout =
                logLine(HASH_2, 1700000200L, "更新了系统", "222")
                        + logLine(HASH_1, 1700000100L, "首次生成了系统", "111"));

        List<VersionResponse> versions = appService.list(projectId);

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).commitHash()).isEqualTo(HASH_2);
        assertThat(versions.get(0).subject()).isEqualTo("更新了系统");
        assertThat(versions.get(0).runId()).isEqualTo("222");
        assertThat(versions.get(0).committedAt()).isNotNull();
        assertThat(versions.get(1).commitHash()).isEqualTo(HASH_1);
        assertThat(versions.get(1).runId()).isEqualTo("111");
    }

    // ---------- 版本详情（锚定收尾卡） ----------

    @Test
    void given_version_with_closing_card_when_detail_then_anchored_closing_payload() {
        Long projectId = persistedProject("9904");
        stubExec((command, out) -> out.stdout = logLine(HASH_1, 1700000100L, "更新了系统", "222"));
        Map<String, Object> closingPayload = Map.of(
                "summary", "更新了系统", "systemChanged", true, "files", List.of(), "durationMs", 100L);
        conversationHistory.recordClosing(projectId, "222", closingPayload);

        VersionDetailResponse detail = appService.detail(projectId, HASH_1);

        assertThat(detail.commitHash()).isEqualTo(HASH_1);
        assertThat(detail.runId()).isEqualTo("222");
        assertThat(detail.closing())
                .containsEntry("summary", "更新了系统")
                .containsEntry("systemChanged", true);
    }

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

    // ---------- 回滚到此 ----------

    @Test
    void given_valid_ref_when_rollback_then_appends_new_version_anchored_to_source() {
        Long projectId = persistedProject("9910");
        stubExec((command, out) -> {
            if (command.contains("git status")) {
                out.stdout = ""; // 工作树干净（并发守卫放行）
            } else if (command.contains("git restore")) {
                out.stdout = HASH_2 + "\n";
            } else if (command.contains(HASH_2)) {
                out.stdout = rollbackLogLine(HASH_2, 1700000200L, "回滚到「首次生成了系统」", HASH_1);
            } else {
                out.stdout = logLine(HASH_1, 1700000100L, "首次生成了系统", "111");
            }
        });

        VersionResponse rolled = appService.rollback(projectId, HASH_1);

        assertThat(rolled.commitHash()).isEqualTo(HASH_2);
        assertThat(rolled.rollbackFrom()).isEqualTo(HASH_1);
        assertThat(rolled.runId()).isNull();
        assertThat(rolled.subject()).isEqualTo("回滚到「首次生成了系统」");
        // 寻址守卫（show 目标）→ 脏树守卫（status）→ rollbackCommand（restore + Rollback-From）
        // → 回读新版本（show）
        assertThat(recordedCommands).hasSize(4);
        assertThat(recordedCommands.get(0)).contains("git log -1").contains(HASH_1);
        assertThat(recordedCommands.get(1)).contains("git status --porcelain");
        assertThat(recordedCommands.get(2))
                .contains("git restore").contains("Rollback-From: " + HASH_1);
        assertThat(recordedCommands.get(3)).contains("git log -1").contains(HASH_2);
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
    void given_exec_failure_when_rollback_then_environment_failure_not_quiet() {
        Long projectId = persistedProject("9913");
        stubExec((command, out) -> {
            if (command.contains("git status")) {
                out.stdout = ""; // 脏树守卫放行，让 restore 失败走到环境故障
            } else if (command.contains("git restore")) {
                out.exitCode = 128;
            } else {
                out.stdout = logLine(HASH_1, 1700000100L, "首次生成了系统", "111");
            }
        });

        assertThatThrownBy(() -> appService.rollback(projectId, HASH_1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());
    }

    @Test
    void given_run_in_flight_when_rollback_then_409_before_restore() {
        Long projectId = persistedProject("9914");
        stubExec((command, out) -> out.stdout = logLine(HASH_1, 1700000100L, "首次生成了系统", "111"));
        codingRunTrack.begin(projectId);

        assertThatThrownBy(() -> appService.rollback(projectId, HASH_1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_ROLLBACK_RUN_IN_FLIGHT.message());
        // 在途守卫在寻址后即拒：只过 show，未触脏树检查 / restore（不丢在途改动）
        assertThat(recordedCommands).hasSize(1);
        assertThat(recordedCommands.get(0)).contains("git log -1").contains(HASH_1);
    }

    @Test
    void given_dirty_tree_when_rollback_then_409_not_silent_discard() {
        Long projectId = persistedProject("9915");
        stubExec((command, out) -> {
            if (command.contains("git status")) {
                out.stdout = " M src/app.js"; // 未提交 tracked 改动（如失败 run 残留）
            } else {
                out.stdout = logLine(HASH_1, 1700000100L, "首次生成了系统", "111");
            }
        });

        assertThatThrownBy(() -> appService.rollback(projectId, HASH_1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_ROLLBACK_DIRTY_TREE.message());
        // 脏树守卫即拒：show + status 两命令，未触 restore（不静默丢弃）
        assertThat(recordedCommands).hasSize(2);
        assertThat(recordedCommands.get(1)).contains("git status --porcelain");
    }

    @Test
    void given_status_exec_failure_when_rollback_then_environment_failure() {
        Long projectId = persistedProject("9916");
        stubExec((command, out) -> {
            if (command.contains("git status")) {
                out.exitCode = 128;
            } else {
                out.stdout = logLine(HASH_1, 1700000100L, "首次生成了系统", "111");
            }
        });

        assertThatThrownBy(() -> appService.rollback(projectId, HASH_1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());
    }

    // ---------- 观测件 ----------

    private Long persistedProject(String workspaceId) {
        Project project = Project.create("版本项目", null, Long.parseLong(workspaceId), OWNER);
        project.markPrdProduced();
        project.markGenerated();
        return projectRepository.save(project).getId();
    }

    /** git log 单行（字段 \u001f 分隔、记录 \u001e 收尾——与 WorkspaceVersions 格式同源）。 */
    private static String logLine(String hash, long at, String subject, String runId) {
        return hash + FS + at + FS + subject + FS + "Run-Id: " + runId + "\n" + RS;
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
            return new ExecResultResponse(out.stdout, "", out.exitCode);
        }).when(workspaceLifecycleAppService).exec(anyString(), any());
    }

    private final List<String> recordedCommands = new java.util.ArrayList<>();

    private interface ExecScript {
        void apply(String command, ExecOut out);
    }

    private static final class ExecOut {
        String stdout = "";
        int exitCode = 0;
    }
}

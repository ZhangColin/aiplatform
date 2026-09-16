package com.aieducenter.aiplatform.business.project.endpoints.scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceHibernationAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceProperties;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceScanFact;
import com.aieducenter.aiplatform.business.project.application.CodingRunTrack;
import com.aieducenter.aiplatform.business.project.application.VersionSnapshotAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 休眠扫描组合根（#171 → #197 正名）：组合根测试——项目域事实（run 在途 / 已生成）
 * 随扫描递入 base.workspace（不反向依赖）、归档项目同一判定（无特殊分支）、闲置
 * 快照同一轮清扫。总开关已内移 base 扫描入口，本层不再读开关（关闭与否是 base
 * 扫描的内政）。@Scheduled 装配以反射断言 + 应用服务直调验证（不等真实定时）。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceHibernationSchedulerTest {

    @Mock
    private WorkspaceHibernationAppService hibernationAppService;

    @Mock
    private VersionSnapshotAppService snapshotAppService;

    @Mock
    private ProjectRepository projectRepository;

    private final CodingRunTrack codingRunTrack = new CodingRunTrack();

    private final WorkspaceProperties properties = new WorkspaceProperties();

    @Test
    void given_projects_when_scan_round_then_facts_passed_with_run_and_generated_flags() {
        Project generated = project(4201L, 1L, true, false);
        Project archivedUngenerated = project(4202L, 2L, false, true);
        Project runningGeneration = project(4203L, 3L, true, false);
        when(projectRepository.findAll()).thenReturn(
                List.of(generated, archivedUngenerated, runningGeneration));
        codingRunTrack.begin(4203L);   // 生成 run 在途

        newScheduler().scanRound();

        // 消费方申报事实：run 在途恒活跃、已生成唤醒拉应用；归档项目同一判定（无筛选）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, WorkspaceScanFact>> facts =
                ArgumentCaptor.forClass(Map.class);
        verify(hibernationAppService).scanOnce(facts.capture(), any(LocalDateTime.class));
        assertThat(facts.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, new WorkspaceScanFact(false, true),
                2L, new WorkspaceScanFact(false, false),
                3L, new WorkspaceScanFact(true, true)));
        // 同轮清扫闲置快照（同一闲置阈值）
        verify(snapshotAppService).sweepIdleViews(any(LocalDateTime.class),
                eq(Duration.ofMinutes(60)));
    }

    @Test
    void given_no_projects_when_scan_round_then_scanned_with_empty_facts() {
        when(projectRepository.findAll()).thenReturn(List.of());

        newScheduler().scanRound();

        verify(hibernationAppService).scanOnce(anyMap(), any(LocalDateTime.class));
        verify(snapshotAppService).sweepIdleViews(any(LocalDateTime.class),
                eq(Duration.ofMinutes(60)));
    }

    @Test
    void given_scheduler_class_when_wiring_then_scheduled_annotation_present() {
        // 装配验证（AC）：@Scheduled 真挂在扫描轮上（摘掉注解 = 定时消失，此测兜底）；
        // 默认 5 分钟固定延迟、首轮延迟一个间隔（启动 ApplicationRunner 先对账一次）
        Scheduled scheduled;
        try {
            scheduled = WorkspaceHibernationScheduler.class
                    .getMethod("scanRound").getAnnotation(Scheduled.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${app.workspace.hibernation-scan-interval:5m}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${app.workspace.hibernation-scan-interval:5m}");
        // 启动期对账一次：实现 ApplicationRunner（run → 同一扫描轮）
        assertThat(ApplicationRunner.class)
                .isAssignableFrom(WorkspaceHibernationScheduler.class);
    }

    @Test
    void given_startup_when_run_then_scan_round_executed() {
        when(projectRepository.findAll()).thenReturn(List.of());

        newScheduler().run(mock(ApplicationArguments.class));

        verify(hibernationAppService).scanOnce(anyMap(), any(LocalDateTime.class));
    }

    // ---------- 测试数据 ----------

    /** 聚合桩：JPA 落库前的 id 不可构造，纯组合测试只桩申报事实读到的 getter——
     * 归档位（archivedAt）不桩：扫描根本不读它（归档无特殊分支），形参仅让用例
     * 行读得出「这是归档项目」。 */
    @SuppressWarnings("unused")
    private Project project(long id, long workspaceId, boolean generated, boolean archived) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getWorkspaceId()).thenReturn(workspaceId);
        when(project.getGeneratedAt()).thenReturn(generated ? LocalDateTime.now() : null);
        return project;
    }

    private WorkspaceHibernationScheduler newScheduler() {
        return new WorkspaceHibernationScheduler(hibernationAppService, snapshotAppService,
                codingRunTrack, projectRepository, properties);
    }
}

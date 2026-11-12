package com.semgravio.aiplatform.base.workspace.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.semgravio.aiplatform.backoffice.BackofficeSeamTest;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.HostObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceLostMark;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceSweepAction;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.EnvironmentUsage;
import com.semgravio.aiplatform.base.workspace.domain.model.OrphanSweepReport;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.semgravio.aiplatform.base.workspace.domain.repository.HostObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceLostMarkRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceSweepActionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台资源面（#489，/api/backoffice/resources overview/sweep）在 {@code #152}
 * seam 上全绿：MockMvc 穿完整过滤链（签名验签 → 会话豁免 → @RequireSignature
 * 强制闸）→ 真应用服务（base.workspace 资源面用例）→ aiplatform_test 真库
 * （wsp_host_observation/wsp_lost_marks/wsp_sweep_actions 真行）。#487 治超时
 * 口径延伸到资源大盘：<b>overview 零 docker 调用</b>——{@code EnvironmentBackend}
 * 以 {@code @MockitoBean} 收口且全程零交互（verifyNoInteractions 钉死）。sweep
 * 端口假面按报告应答（准绳/留痕断言走真库）；真 daemon 对账（df/system df 一致、
 * 自造无主现场收编）归 {@code BackofficeResourceFaceLiveTest}。
 */
@BackofficeSeamTest
class BackofficeResourceSeamTest {

    /** 夹具沙箱号段（避开他票夹具段）。 */
    private static final long WS_REGISTERED = 730101L;   // 在册行（保留集锚）
    private static final long WS_LOST = 730102L;         // 在册失联锚（标记行）

    private static final String OPERATOR_ID = "900001";
    private static final String OPERATOR_NAME = "运营同学";

    private static final LocalDateTime SAMPLED_AT = LocalDateTime.of(2026, 11, 13, 12, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private HostObservationSampleRepository hostRepository;

    @Autowired
    private WorkspaceLostMarkRepository lostMarkRepository;

    @Autowired
    private WorkspaceSweepActionRepository sweepActionRepository;

    /** docker 依赖收口（#489：overview 零交互钉死；sweep 假面按报告应答）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    @AfterEach
    void tearDown() {
        sweepActionRepository.deleteAll();
        lostMarkRepository.deleteAll();
        hostRepository.deleteById(HostObservationSample.SINGLETON_ID);
        workspaceRepository.deleteAll();
    }

    // ---------- overview：纯读库零 docker、契约直读 ----------

    @Test
    void given_sampled_frame_and_marks_when_signed_overview_then_faces_and_zero_docker()
            throws Exception {
        seedHostFrame();
        seedWorkspace(WS_LOST);
        lostMarkRepository.save(WorkspaceLostMark.mark(
                WorkspaceId.of(Long.toString(WS_LOST)),
                LocalDateTime.of(2026, 11, 12, 8, 0)));
        seedWorkspace(WS_REGISTERED);

        signedGet("/api/backoffice/resources/overview")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 主机盘面：锚路径＋总量＋可用＋派生已用
                .andExpect(jsonPath("$.data.host.path").exists())
                .andExpect(jsonPath("$.data.host.totalBytes").value(100_000_000_000L))
                .andExpect(jsonPath("$.data.host.usableBytes").value(40_000_000_000L))
                .andExpect(jsonPath("$.data.host.usedBytes").value(60_000_000_000L))
                // docker 四类用量＋无主计数（采样帧原数）
                .andExpect(jsonPath("$.data.docker.imageCount").value(14))
                .andExpect(jsonPath("$.data.docker.imageSizeBytes").value(7_750_000_000L))
                .andExpect(jsonPath("$.data.docker.containerCount").value(6))
                .andExpect(jsonPath("$.data.docker.volumeCount").value(73))
                .andExpect(jsonPath("$.data.docker.buildCacheSizeBytes").value(5_000_000_000L))
                .andExpect(jsonPath("$.data.orphans.containerCount").value(1))
                .andExpect(jsonPath("$.data.orphans.volumeCount").value(2))
                // 新鲜度标注＋在册失联计数（待裁决清单规模）
                .andExpect(jsonPath("$.data.sampledAt").exists())
                .andExpect(jsonPath("$.data.lostWorkspaceCount").value(1))
                // 从未手动清扫：lastSweep 如实 null
                .andExpect(jsonPath("$.data.lastSweep").doesNotExist());

        // AC：请求路径零 docker 调用（观测纯读库——探查混进来即红）
        verifyNoInteractions(environmentBackend);
    }

    @Test
    void given_sweep_trace_when_signed_overview_then_last_sweep_visible() throws Exception {
        seedWorkspace(WS_REGISTERED);
        seedHostFrame();
        when(environmentBackend.sweepOrphanWorkspaceContainers(any(), any()))
                .thenReturn(new OrphanSweepReport(List.of(new OrphanSweepReport.Item(
                        OrphanSweepReport.Kind.CONTAINER, "ws-55",
                        OrphanSweepReport.Disposition.SWEPT, null, null))));
        when(environmentBackend.sweepOrphanWorkspaceVolumes(any(), any()))
                .thenReturn(new OrphanSweepReport(List.of()));
        signedPost("/api/backoffice/resources/sweep");

        // 大盘带最近一次清扫（收编结果面：留痕行直读）
        signedGet("/api/backoffice/resources/overview")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastSweep.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.lastSweep.operatorName").value(OPERATOR_NAME))
                .andExpect(jsonPath("$.data.lastSweep.registryReachable").value(true))
                .andExpect(jsonPath("$.data.lastSweep.sweptContainers").value(1))
                .andExpect(jsonPath("$.data.lastSweep.sweptVolumes").value(0));
    }

    @Test
    void given_never_sampled_when_signed_overview_then_faces_absent_as_is() throws Exception {
        signedGet("/api/backoffice/resources/overview")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 未采样的首个扫描窗内：各面如实 null（「没有帧」≠「零」）
                .andExpect(jsonPath("$.data.host").doesNotExist())
                .andExpect(jsonPath("$.data.docker").doesNotExist())
                .andExpect(jsonPath("$.data.orphans").doesNotExist())
                .andExpect(jsonPath("$.data.sampledAt").doesNotExist())
                .andExpect(jsonPath("$.data.lastSweep").doesNotExist())
                .andExpect(jsonPath("$.data.lostWorkspaceCount").value(0));

        verifyNoInteractions(environmentBackend);
    }

    // ---------- sweep：逐项报告＋留痕 ----------

    @Test
    void given_registered_and_orphans_when_signed_sweep_then_itemized_report_and_trace()
            throws Exception {
        seedWorkspace(WS_REGISTERED);
        when(environmentBackend.sweepOrphanWorkspaceContainers(any(), any()))
                .thenReturn(new OrphanSweepReport(List.of(
                        new OrphanSweepReport.Item(OrphanSweepReport.Kind.CONTAINER,
                                "ws-" + 55, OrphanSweepReport.Disposition.SWEPT, null,
                                SAMPLED_AT.minusHours(1)),
                        new OrphanSweepReport.Item(OrphanSweepReport.Kind.CONTAINER,
                                "ws-" + WS_REGISTERED, OrphanSweepReport.Disposition.SKIPPED,
                                OrphanSweepReport.SkipReason.REGISTERED, null))));
        when(environmentBackend.sweepOrphanWorkspaceVolumes(any(), any()))
                .thenReturn(new OrphanSweepReport(List.of(
                        new OrphanSweepReport.Item(OrphanSweepReport.Kind.VOLUME,
                                "vol-ws-" + 77, OrphanSweepReport.Disposition.SKIPPED,
                                OrphanSweepReport.SkipReason.GRACE_WINDOW,
                                SAMPLED_AT.plusMinutes(3)))));

        signedPost("/api/backoffice/resources/sweep")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.registryReachable").value(true))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.operatorName").value(OPERATOR_NAME))
                .andExpect(jsonPath("$.data.graceLine").exists())
                .andExpect(jsonPath("$.data.sweptContainers").value(1))
                .andExpect(jsonPath("$.data.sweptVolumes").value(0))
                .andExpect(jsonPath("$.data.skippedCount").value(2))
                // 逐项处置（AC）：收编了什么/跳过什么为什么（在册/宽限窗）
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[*].identifier", containsInAnyOrder(
                        "ws-55", "ws-730101", "vol-ws-77")))
                .andExpect(jsonPath("$.data.items[*].disposition", containsInAnyOrder(
                        "SWEPT", "SKIPPED", "SKIPPED")))
                // SWEPT 行 skipReason 为 null（收编无跳过原因——如实序列化）
                .andExpect(jsonPath("$.data.items[*].skipReason", containsInAnyOrder(
                        nullValue(String.class), equalTo("REGISTERED"), equalTo("GRACE_WINDOW"))));

        // append-only 留痕（真库断言）：操作者＋报告 JSON 全量落档
        List<WorkspaceSweepAction> traces = sweepActionRepository.findAll();
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getOperatorId()).isEqualTo(OPERATOR_ID);
        assertThat(traces.get(0).isRegistryReachable()).isTrue();
        assertThat(traces.get(0).getSweptContainers()).isEqualTo(1);
        assertThat(traces.get(0).getItemsJson()).contains("ws-55", "vol-ws-77");
    }

    // ---------- 签名负例 ----------

    @Test
    void given_no_signature_headers_when_call_resources_then_401_signature_required()
            throws Exception {
        // 全裸请求落到类级 @RequireSignature 强制闸：无用户会话也无签名 → 401
        mockMvc.perform(get("/api/backoffice/resources/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
        mockMvc.perform(post("/api/backoffice/resources/sweep"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // -------- 夹具 --------

    /** 签名 GET（无体）。 */
    private ResultActions signedGet(String pathWithQuery) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null));
    }

    /** 签名 POST（无体清扫）＋操作者透传头（留痕锚）。 */
    private ResultActions signedPost(String path) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                post(path).contentType(MediaType.APPLICATION_JSON), path, "")
                .header("X-User-Id", OPERATOR_ID)
                .header("X-User-Name", OPERATOR_NAME));
    }

    /** 落主机观测帧（全机一行固定值——大盘契约的采样侧夹具）。 */
    private void seedHostFrame() {
        hostRepository.save(HostObservationSample.first().resample(
                "/opt/aiplatform", 100_000_000_000L, 40_000_000_000L,
                new EnvironmentUsage(
                        new EnvironmentUsage.Usage(14, 7_750_000_000L),
                        new EnvironmentUsage.Usage(6, 151_600L),
                        new EnvironmentUsage.Usage(73, 845_900_000L),
                        new EnvironmentUsage.Usage(2, 5_000_000_000L),
                        Set.of(), Set.of()),
                1L, 2L, SAMPLED_AT));
    }

    /** 落一行 READY 工作区（保留集锚——在册项不进收编面）。 */
    private void seedWorkspace(long id) {
        WorkspaceId workspaceId = WorkspaceId.of(Long.toString(id));
        Workspace pending = Workspace.registerPending(workspaceId, EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(WorkspaceHandle.dev(workspaceId,
                "ws-" + id, "previewnet")));
        workspaceRepository.save(pending);
    }
}
